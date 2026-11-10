package com.fieldops.mobile;

import com.fieldops.shared.error.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sync do técnico: push com recibo por {@code operationId}, e pull por cursor opaco
 * ({@code instant|uuid}) com merge de inspeções, respostas e tombstones.
 */
@Service
public class MobileSyncService {
  static final int PULL_PAGE_SIZE = 100;
  private static final UUID BOOTSTRAP_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
  private static final Instant BOOTSTRAP_AT = Instant.parse("0001-01-01T00:00:00.000Z");
  private static final DateTimeFormatter CURSOR_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public MobileSyncService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public PullResult pull(UUID technicianId, String rawCursor) {
    Instant serverTime = Instant.now();
    boolean hadCursor = rawCursor != null && !rawCursor.isBlank();
    Cursor cursor = parseCursor(rawCursor);
    List<ChangeCandidate> candidates = new ArrayList<>();
    candidates.addAll(loadInspectionChanges(technicianId, cursor));
    candidates.addAll(loadResponseChanges(technicianId, cursor));
    candidates.addAll(loadTombstoneChanges(technicianId, cursor));
    candidates.sort(Comparator
        .comparing(ChangeCandidate::updatedAt)
        .thenComparing(ChangeCandidate::streamKey));

    boolean hasMore = candidates.size() > PULL_PAGE_SIZE;
    List<ChangeCandidate> page = hasMore ? candidates.subList(0, PULL_PAGE_SIZE) : candidates;
    attachInspectionItems(page);

    String nextCursor;
    if (page.isEmpty()) {
      nextCursor = hadCursor ? rawCursor.trim() : formatCursor(serverTime, BOOTSTRAP_ID);
    } else {
      ChangeCandidate last = page.getLast();
      nextCursor = formatCursor(last.updatedAt(), last.streamKey());
    }
    List<SyncChange> changes = page.stream()
        .map(c -> new SyncChange(c.entityType(), c.entityId(), c.operation(), c.updatedAt(), c.payload()))
        .toList();
    return new PullResult(changes, nextCursor, serverTime, hasMore);
  }

  public PushResult push(UUID technicianId, PushCommand command) {
    LinkedHashMap<UUID, Operation> byId = new LinkedHashMap<>();
    for (Operation operation : command.operations()) {
      if (byId.put(operation.operationId(), operation) != null) {
        throw new com.fieldops.shared.error.ApiException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "VALIDATION_ERROR",
            "operationId must be unique in the batch");
      }
    }

    Map<UUID, Receipt> stored = loadReceipts(byId.keySet());
    Map<UUID, OperationResult> done = new HashMap<>();
    Set<UUID> applied = new HashSet<>();
    for (Receipt receipt : stored.values()) {
      if ("CONFLICT".equals(receipt.status())) continue;
      done.put(receipt.operationId(), result(receipt.operationId(), Status.ALREADY_APPLIED, receipt.entityVersion()));
      if ("APPLIED".equals(receipt.status())) applied.add(receipt.operationId());
    }

    List<Operation> fresh = byId.values().stream().filter(operation -> !done.containsKey(operation.operationId())).toList();
    Set<UUID> freshIds = new HashSet<>();
    for (Operation operation : fresh) freshIds.add(operation.operationId());

    Map<UUID, List<UUID>> dependents = new HashMap<>();
    Map<UUID, Integer> indegree = new HashMap<>();
    for (Operation operation : fresh) indegree.put(operation.operationId(), 0);

    Set<UUID> blocked = new HashSet<>();
    for (Operation operation : fresh) {
      for (UUID dependencyId : dependencies(operation)) {
        if (dependencyId.equals(operation.operationId()) || (!freshIds.contains(dependencyId) && !applied.contains(dependencyId))) {
          blocked.add(operation.operationId());
          break;
        }
        if (freshIds.contains(dependencyId)) {
          dependents.computeIfAbsent(dependencyId, ignored -> new ArrayList<>()).add(operation.operationId());
          indegree.merge(operation.operationId(), 1, Integer::sum);
        }
      }
    }

    for (UUID operationId : List.copyOf(blocked)) {
      markDependencyFailed(technicianId, command.deviceId(), byId, dependents, done, operationId);
    }

    ArrayDeque<UUID> ready = new ArrayDeque<>();
    for (Map.Entry<UUID, Integer> entry : indegree.entrySet()) {
      if (entry.getValue() == 0 && !done.containsKey(entry.getKey())) ready.add(entry.getKey());
    }

    while (!ready.isEmpty()) {
      UUID operationId = ready.removeFirst();
      if (done.containsKey(operationId)) continue;
      Operation operation = byId.get(operationId);
      boolean dependenciesApplied = dependencies(operation).stream().allMatch(applied::contains);
      if (!dependenciesApplied) {
        markDependencyFailed(technicianId, command.deviceId(), byId, dependents, done, operationId);
        continue;
      }
      OperationResult result = apply(technicianId, command.deviceId(), operation);
      done.put(operationId, result);
      if (result.status() == Status.APPLIED) {
        applied.add(operationId);
        for (UUID dependent : dependents.getOrDefault(operationId, List.of())) {
          if (done.containsKey(dependent)) continue;
          int remaining = indegree.merge(dependent, -1, Integer::sum);
          if (remaining == 0) ready.add(dependent);
        }
      } else {
        for (UUID dependent : dependents.getOrDefault(operationId, List.of())) {
          markDependencyFailed(technicianId, command.deviceId(), byId, dependents, done, dependent);
        }
      }
    }

    for (Operation operation : fresh) {
      if (!done.containsKey(operation.operationId())) {
        markDependencyFailed(technicianId, command.deviceId(), byId, dependents, done, operation.operationId());
      }
    }

    List<OperationResult> ordered = new ArrayList<>();
    for (Operation operation : command.operations()) ordered.add(done.get(operation.operationId()));
    return new PushResult(ordered, Instant.now());
  }

  private OperationResult apply(UUID technicianId, UUID deviceId, Operation operation) {
    OperationResult result = transactions.execute(status -> {
      Receipt existing = findReceipt(operation.operationId());
      if (existing != null && !"CONFLICT".equals(existing.status())) {
        return result(operation.operationId(), Status.ALREADY_APPLIED, existing.entityVersion());
      }
      try {
        OperationResult evaluated = evaluate(technicianId, operation);
        if (evaluated.status() == Status.APPLIED) {
          insertReceipt(technicianId, deviceId, operation, Status.APPLIED, evaluated.entityVersion());
        }
        return evaluated;
      } catch (ConflictRollback | DataIntegrityViolationException ex) {
        status.setRollbackOnly();
        return ex instanceof ConflictRollback
            ? result(operation.operationId(), Status.CONFLICT, null)
            : result(operation.operationId(), Status.REJECTED, null);
      }
    });
    if (result.status() == Status.CONFLICT) {
      return conflictWithSnapshot(operation);
    }
    Receipt stored = findReceipt(operation.operationId());
    if (stored != null && !"CONFLICT".equals(stored.status())) {
      return result.status() == Status.APPLIED
          ? result
          : result(operation.operationId(), Status.ALREADY_APPLIED, stored.entityVersion());
    }
    if (result.status() != Status.APPLIED && result.status() != Status.ALREADY_APPLIED) {
      storeTerminal(technicianId, deviceId, operation, result.status());
    }
    return result;
  }

  private OperationResult evaluate(UUID technicianId, Operation operation) {
    if (!"INSPECTION_RESPONSE".equals(operation.entityType()) || !"UPSERT".equals(operation.operationType())) {
      return result(operation.operationId(), Status.REJECTED, null);
    }
    AnswerPayload payload = operation.payload();
    if (payload == null || payload.inspectionItemId() == null) {
      return result(operation.operationId(), Status.REJECTED, null);
    }
    List<InspectionRow> rows = jdbc.query(
        "select i.id, i.technician_id, i.status, i.version from inspection_item_snapshot s join inspection i on i.id=s.inspection_id where s.id=?",
        (rs, row) -> new InspectionRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("technician_id")), rs.getString("status"), rs.getInt("version")),
        payload.inspectionItemId());
    if (rows.isEmpty() || !technicianId.equals(rows.getFirst().technicianId()) || !"IN_PROGRESS".equals(rows.getFirst().status())) {
      return result(operation.operationId(), Status.REJECTED, null);
    }
    InspectionRow inspection = rows.getFirst();
    if (operation.baseVersion() == null || operation.baseVersion() != inspection.version()) {
      return result(operation.operationId(), Status.CONFLICT, inspection.version());
    }
    upsertResponse(inspection.id(), operation.entityId(), technicianId, payload);
    int updated = jdbc.update(
        "update inspection set version=version+1, updated_at=current_timestamp where id=? and version=?",
        inspection.id(), inspection.version());
    if (updated == 0) throw new ConflictRollback();
    return result(operation.operationId(), Status.APPLIED, inspection.version() + 1);
  }

  /** Read after the operation transaction so a rolled-back write is not reported as server state. */
  private OperationResult conflictWithSnapshot(Operation operation) {
    UUID itemId = operation.payload() == null ? null : operation.payload().inspectionItemId();
    Integer version = null;
    if (itemId != null) {
      List<Integer> versions = jdbc.query(
          "select i.version from inspection_item_snapshot s join inspection i on i.id=s.inspection_id where s.id=?",
          (rs, row) -> rs.getInt("version"), itemId);
      if (!versions.isEmpty()) version = versions.getFirst();
    }
    return new OperationResult(operation.operationId(), Status.CONFLICT, version, loadServerResponse(itemId));
  }

  private ServerResponse loadServerResponse(UUID itemId) {
    if (itemId == null) return ServerResponse.empty();
    List<ServerResponse> rows = jdbc.query(
        "select id, value_text, value_number, value_boolean, value_date, conformity, observation from inspection_response where inspection_item_id=?",
        (rs, row) -> new ServerResponse(
            UUID.fromString(rs.getString("id")),
            rs.getString("value_text"),
            rs.getBigDecimal("value_number"),
            (Boolean) rs.getObject("value_boolean"),
            rs.getDate("value_date") == null ? null : rs.getDate("value_date").toLocalDate(),
            rs.getString("conformity"),
            rs.getString("observation")),
        itemId);
    return rows.isEmpty() ? ServerResponse.empty() : rows.getFirst();
  }

  private void upsertResponse(UUID inspectionId, UUID entityId, UUID technicianId, AnswerPayload payload) {
    String observation = blankToNull(payload.observation());
    String valueText = blankToNull(payload.valueText());
    Date valueDate = payload.valueDate() == null ? null : Date.valueOf(payload.valueDate());
    List<UUID> existing = jdbc.query(
        "select id from inspection_response where inspection_item_id=?",
        (rs, row) -> UUID.fromString(rs.getString("id")), payload.inspectionItemId());
    if (existing.isEmpty()) {
      jdbc.update(
          "insert into inspection_response (id,inspection_id,inspection_item_id,value_text,value_number,value_boolean,value_date,observation,conformity,answered_by,server_received_at) values (?,?,?,?,?,?,?,?,?,?,current_timestamp)",
          entityId, inspectionId, payload.inspectionItemId(), valueText, payload.valueNumber(), payload.valueBoolean(), valueDate, observation, blankToNull(payload.conformity()), technicianId);
      return;
    }
    jdbc.update(
        "update inspection_response set value_text=?, value_number=?, value_boolean=?, value_date=?, observation=?, conformity=?, answered_by=?, server_received_at=current_timestamp, updated_at=current_timestamp, version=version+1 where inspection_item_id=?",
        valueText, payload.valueNumber(), payload.valueBoolean(), valueDate, observation, blankToNull(payload.conformity()), technicianId, payload.inspectionItemId());
  }

  private void markDependencyFailed(UUID technicianId, UUID deviceId, Map<UUID, Operation> byId, Map<UUID, List<UUID>> dependents, Map<UUID, OperationResult> done, UUID operationId) {
    if (done.containsKey(operationId) || !byId.containsKey(operationId)) return;
    storeTerminal(technicianId, deviceId, byId.get(operationId), Status.DEPENDENCY_FAILED);
    done.put(operationId, result(operationId, Status.DEPENDENCY_FAILED, null));
    for (UUID dependent : dependents.getOrDefault(operationId, List.of())) {
      markDependencyFailed(technicianId, deviceId, byId, dependents, done, dependent);
    }
  }

  private void storeTerminal(UUID technicianId, UUID deviceId, Operation operation, Status status) {
    transactions.executeWithoutResult(tx -> {
      if (findReceipt(operation.operationId()) != null) return;
      insertReceipt(technicianId, deviceId, operation, status, null);
    });
  }

  private Map<UUID, Receipt> loadReceipts(Set<UUID> operationIds) {
    if (operationIds.isEmpty()) return Map.of();
    String placeholders = String.join(",", operationIds.stream().map(ignored -> "?").toList());
    List<Receipt> receipts = jdbc.query(
        "select operation_id, status, entity_version from mobile_sync_operation where operation_id in (" + placeholders + ")",
        (rs, row) -> new Receipt(UUID.fromString(rs.getString("operation_id")), rs.getString("status"), (Integer) rs.getObject("entity_version")),
        operationIds.toArray());
    Map<UUID, Receipt> byId = new HashMap<>();
    for (Receipt receipt : receipts) byId.put(receipt.operationId(), receipt);
    return byId;
  }

  private Receipt findReceipt(UUID operationId) {
    List<Receipt> receipts = jdbc.query(
        "select operation_id, status, entity_version from mobile_sync_operation where operation_id=?",
        (rs, row) -> new Receipt(UUID.fromString(rs.getString("operation_id")), rs.getString("status"), (Integer) rs.getObject("entity_version")),
        operationId);
    return receipts.isEmpty() ? null : receipts.getFirst();
  }

  private void insertReceipt(UUID technicianId, UUID deviceId, Operation operation, Status status, Integer entityVersion) {
    jdbc.update(
        "insert into mobile_sync_operation (operation_id,device_id,technician_id,entity_type,entity_id,operation_type,status,entity_version) values (?,?,?,?,?,?,?,?)",
        operation.operationId(), deviceId, technicianId, operation.entityType(), operation.entityId(), operation.operationType(), status.name(), entityVersion);
  }

  private static List<UUID> dependencies(Operation operation) {
    if (operation.dependencyIds() == null || operation.dependencyIds().isEmpty()) return List.of();
    return operation.dependencyIds().stream().distinct().toList();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static final class ConflictRollback extends RuntimeException {}

  public record PushCommand(UUID deviceId, List<Operation> operations) {}
  public record Operation(UUID operationId, String entityType, UUID entityId, String operationType, List<UUID> dependencyIds, Integer baseVersion, AnswerPayload payload) {}
  public record AnswerPayload(UUID inspectionItemId, String valueText, BigDecimal valueNumber, Boolean valueBoolean, LocalDate valueDate, String conformity, String observation) {}
  public record PushResult(List<OperationResult> results, Instant serverTime) {}
  public record OperationResult(UUID operationId, Status status, Integer entityVersion, ServerResponse server) {}
  public record ServerResponse(UUID id, String valueText, BigDecimal valueNumber, Boolean valueBoolean, LocalDate valueDate, String conformity, String observation) {
    static ServerResponse empty() { return new ServerResponse(null, null, null, null, null, null, null); }
  }
  public enum Status { APPLIED, ALREADY_APPLIED, REJECTED, CONFLICT, DEPENDENCY_FAILED }

  private static OperationResult result(UUID operationId, Status status, Integer entityVersion) {
    return new OperationResult(operationId, status, entityVersion, null);
  }
  private record Receipt(UUID operationId, String status, Integer entityVersion) {}
  private record InspectionRow(UUID id, UUID technicianId, String status, int version) {}

  private List<ChangeCandidate> loadInspectionChanges(UUID technicianId, Cursor cursor) {
    return jdbc.query(
        """
        select i.id, i.client_id, i.site_id, i.equipment_id, i.template_version_id, i.technician_id, i.supervisor_id,
               i.title, i.instructions, i.priority, i.status, i.scheduled_for, i.started_at_device, i.started_at_server,
               i.completed_at_device, i.submitted_at_server, i.canceled_at, i.canceled_reason, i.equipment_confirmed_at,
               i.started_latitude, i.started_longitude, i.completed_latitude, i.completed_longitude, i.version, i.updated_at
        from inspection i
        where i.technician_id = ?
          and (i.updated_at > ? or (i.updated_at = ? and i.id > ?))
        order by i.updated_at asc, i.id asc
        limit ?
        """,
        (rs, row) -> {
          UUID id = UUID.fromString(rs.getString("id"));
          Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
          InspectionPullPayload payload = new InspectionPullPayload(
              id,
              UUID.fromString(rs.getString("client_id")),
              UUID.fromString(rs.getString("site_id")),
              optionalUuid(rs.getString("equipment_id")),
              UUID.fromString(rs.getString("template_version_id")),
              UUID.fromString(rs.getString("technician_id")),
              optionalUuid(rs.getString("supervisor_id")),
              rs.getString("title"),
              rs.getString("instructions"),
              rs.getString("priority"),
              rs.getString("status"),
              instant(rs.getTimestamp("scheduled_for")),
              instant(rs.getTimestamp("started_at_device")),
              instant(rs.getTimestamp("started_at_server")),
              instant(rs.getTimestamp("completed_at_device")),
              instant(rs.getTimestamp("submitted_at_server")),
              instant(rs.getTimestamp("canceled_at")),
              rs.getString("canceled_reason"),
              instant(rs.getTimestamp("equipment_confirmed_at")),
              rs.getBigDecimal("started_latitude"),
              rs.getBigDecimal("started_longitude"),
              rs.getBigDecimal("completed_latitude"),
              rs.getBigDecimal("completed_longitude"),
              rs.getInt("version"),
              updatedAt,
              List.of());
          return new ChangeCandidate("INSPECTION", id, "UPSERT", updatedAt, id, payload);
        },
        technicianId, Timestamp.from(cursor.afterAt()), Timestamp.from(cursor.afterAt()), cursor.afterId(), PULL_PAGE_SIZE + 1);
  }

  private List<ChangeCandidate> loadResponseChanges(UUID technicianId, Cursor cursor) {
    return jdbc.query(
        """
        select r.id, r.inspection_id, r.inspection_item_id, r.value_text, r.value_number, r.value_boolean, r.value_date,
               r.value_json, r.observation, r.conformity, r.answered_at_device, r.server_received_at, r.answered_by,
               r.version, r.updated_at
        from inspection_response r
        inner join inspection i on i.id = r.inspection_id
        where i.technician_id = ?
          and (r.updated_at > ? or (r.updated_at = ? and r.id > ?))
        order by r.updated_at asc, r.id asc
        limit ?
        """,
        (rs, row) -> {
          UUID id = UUID.fromString(rs.getString("id"));
          Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
          Date valueDate = rs.getDate("value_date");
          ResponsePullPayload payload = new ResponsePullPayload(
              id,
              UUID.fromString(rs.getString("inspection_id")),
              UUID.fromString(rs.getString("inspection_item_id")),
              rs.getString("value_text"),
              rs.getBigDecimal("value_number"),
              (Boolean) rs.getObject("value_boolean"),
              valueDate == null ? null : valueDate.toLocalDate(),
              rs.getString("value_json"),
              rs.getString("observation"),
              rs.getString("conformity"),
              instant(rs.getTimestamp("answered_at_device")),
              instant(rs.getTimestamp("server_received_at")),
              optionalUuid(rs.getString("answered_by")),
              rs.getInt("version"),
              updatedAt);
          return new ChangeCandidate("INSPECTION_RESPONSE", id, "UPSERT", updatedAt, id, payload);
        },
        technicianId, Timestamp.from(cursor.afterAt()), Timestamp.from(cursor.afterAt()), cursor.afterId(), PULL_PAGE_SIZE + 1);
  }

  private List<ChangeCandidate> loadTombstoneChanges(UUID technicianId, Cursor cursor) {
    return jdbc.query(
        """
        select t.id, t.entity_id, t.removed_at
        from mobile_sync_tombstone t
        where t.technician_id = ?
          and (t.removed_at > ? or (t.removed_at = ? and t.id > ?))
        order by t.removed_at asc, t.id asc
        limit ?
        """,
        (rs, row) -> {
          UUID streamKey = UUID.fromString(rs.getString("id"));
          UUID entityId = UUID.fromString(rs.getString("entity_id"));
          Instant updatedAt = rs.getTimestamp("removed_at").toInstant();
          return new ChangeCandidate("INSPECTION", entityId, "DELETE", updatedAt, streamKey, null);
        },
        technicianId, Timestamp.from(cursor.afterAt()), Timestamp.from(cursor.afterAt()), cursor.afterId(), PULL_PAGE_SIZE + 1);
  }

  private void attachInspectionItems(List<ChangeCandidate> page) {
    List<UUID> inspectionIds = page.stream()
        .filter(c -> "INSPECTION".equals(c.entityType()) && "UPSERT".equals(c.operation()))
        .map(ChangeCandidate::entityId)
        .distinct()
        .toList();
    if (inspectionIds.isEmpty()) return;

    Map<UUID, List<InspectionItemPullPayload>> itemsByInspection = new HashMap<>();
    String placeholders = String.join(",", inspectionIds.stream().map(id -> "?").toList());
    jdbc.query(
        "select id, inspection_id, section_title, section_description, section_order, item_code, item_title, item_description, "
            + "response_type, required, rules_json, options_json, item_order from inspection_item_snapshot "
            + "where inspection_id in (" + placeholders + ") order by section_order, item_order",
        (rs, row) -> {
          UUID inspectionId = UUID.fromString(rs.getString("inspection_id"));
          itemsByInspection.computeIfAbsent(inspectionId, ignored -> new ArrayList<>()).add(
              new InspectionItemPullPayload(
                  UUID.fromString(rs.getString("id")),
                  rs.getString("section_title"),
                  rs.getString("section_description"),
                  rs.getInt("section_order"),
                  rs.getString("item_code"),
                  rs.getString("item_title"),
                  rs.getString("item_description"),
                  rs.getString("response_type"),
                  rs.getBoolean("required"),
                  rs.getString("rules_json"),
                  rs.getString("options_json"),
                  rs.getInt("item_order")));
          return null;
        },
        inspectionIds.toArray());

    for (int i = 0; i < page.size(); i++) {
      ChangeCandidate candidate = page.get(i);
      if (!("INSPECTION".equals(candidate.entityType()) && "UPSERT".equals(candidate.operation()))) continue;
      InspectionPullPayload payload = (InspectionPullPayload) candidate.payload();
      List<InspectionItemPullPayload> items = itemsByInspection.getOrDefault(payload.id(), List.of());
      page.set(i, new ChangeCandidate(
          candidate.entityType(), candidate.entityId(), candidate.operation(), candidate.updatedAt(),
          candidate.streamKey(),
          new InspectionPullPayload(
              payload.id(), payload.clientId(), payload.siteId(), payload.equipmentId(), payload.templateVersionId(),
              payload.technicianId(), payload.supervisorId(), payload.title(), payload.instructions(), payload.priority(),
              payload.status(), payload.scheduledFor(), payload.startedAtDevice(), payload.startedAtServer(),
              payload.completedAtDevice(), payload.submittedAtServer(), payload.canceledAt(), payload.canceledReason(),
              payload.equipmentConfirmedAt(), payload.startedLatitude(), payload.startedLongitude(),
              payload.completedLatitude(), payload.completedLongitude(), payload.version(), payload.updatedAt(), items)));
    }
  }

  private static Cursor parseCursor(String rawCursor) {
    if (rawCursor == null || rawCursor.isBlank()) {
      return new Cursor(BOOTSTRAP_AT, BOOTSTRAP_ID);
    }
    String trimmed = rawCursor.trim();
    int sep = trimmed.indexOf('|');
    if (sep <= 0 || sep != trimmed.lastIndexOf('|') || sep == trimmed.length() - 1) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "cursor must be opaque instant|uuid");
    }
    String instantPart = trimmed.substring(0, sep);
    String uuidPart = trimmed.substring(sep + 1);
    try {
      Instant afterAt = Instant.parse(instantPart);
      UUID afterId = UUID.fromString(uuidPart);
      return new Cursor(afterAt, afterId);
    } catch (DateTimeParseException | IllegalArgumentException ex) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "cursor must be opaque instant|uuid");
    }
  }

  private static String formatCursor(Instant at, UUID id) {
    return CURSOR_TIME.format(at) + "|" + id;
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static UUID optionalUuid(String value) {
    return value == null || value.isBlank() ? null : UUID.fromString(value);
  }

  public record PullResult(List<SyncChange> changes, String nextCursor, Instant serverTime, boolean hasMore) {}
  public record SyncChange(String entityType, UUID entityId, String operation, Instant updatedAt, Object payload) {}
  public record InspectionPullPayload(
      UUID id, UUID clientId, UUID siteId, UUID equipmentId, UUID templateVersionId, UUID technicianId, UUID supervisorId,
      String title, String instructions, String priority, String status, Instant scheduledFor, Instant startedAtDevice,
      Instant startedAtServer, Instant completedAtDevice, Instant submittedAtServer, Instant canceledAt, String canceledReason,
      Instant equipmentConfirmedAt, BigDecimal startedLatitude, BigDecimal startedLongitude, BigDecimal completedLatitude,
      BigDecimal completedLongitude, int version, Instant updatedAt, List<InspectionItemPullPayload> items) {}
  public record InspectionItemPullPayload(
      UUID id, String sectionTitle, String sectionDescription, int sectionOrder, String itemCode, String itemTitle,
      String itemDescription, String responseType, boolean required, String rulesJson, String optionsJson, int itemOrder) {}
  public record ResponsePullPayload(
      UUID id, UUID inspectionId, UUID inspectionItemId, String valueText, BigDecimal valueNumber, Boolean valueBoolean,
      LocalDate valueDate, String valueJson, String observation, String conformity, Instant answeredAtDevice,
      Instant serverReceivedAt, UUID answeredBy, int version, Instant updatedAt) {}

  private record Cursor(Instant afterAt, UUID afterId) {}
  private record ChangeCandidate(
      String entityType, UUID entityId, String operation, Instant updatedAt, UUID streamKey, Object payload) {}
}
