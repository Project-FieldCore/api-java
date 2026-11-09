package com.fieldops.mobile;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Push de sincronização do técnico. Cada operação commita na própria transação:
 * uma falha não desfaz o que já entrou no mesmo lote, e o recibo de {@code operationId}
 * torna o retry idempotente.
 */
@Service
public class MobileSyncService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public MobileSyncService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
}
