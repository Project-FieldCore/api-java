package com.fieldops.inspection;

import com.fieldops.shared.error.ApiException;
import com.fieldops.shared.pagination.PageQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InspectionSchedulingService {
  private final JdbcTemplate jdbc;
  private final EvidenceFileStore files;

  public InspectionSchedulingService(JdbcTemplate jdbc, EvidenceFileStore files) {
    this.jdbc = jdbc;
    this.files = files;
  }

  @Transactional
  public Inspection schedule(ScheduleCommand command, UUID supervisorId) {
    PublishedVersion version = requirePublishedVersion(command.templateVersionId());
    requireClient(command.clientId());
    requireSiteForClient(command.siteId(), command.clientId());
    requireActiveTechnician(command.technicianId());
    UUID id = UUID.randomUUID();
    String title = command.title() == null || command.title().isBlank() ? version.title() : command.title();
    jdbc.update("insert into inspection (id,client_id,site_id,template_version_id,technician_id,supervisor_id,created_by,title,instructions,priority,status,scheduled_for) values (?,?,?,?,?,?,?,?,?,?,?,?)",
        id, command.clientId(), command.siteId(), command.templateVersionId(), command.technicianId(), supervisorId,
        supervisorId, title, command.instructions(), command.priority().name(), Status.ASSIGNED.name(),
        Timestamp.from(command.scheduledFor().toInstant()));
    copyItemSnapshot(id, command.templateVersionId());
    return get(id);
  }

  @Transactional
  public Inspection assign(UUID inspectionId, AssignmentCommand command) {
    Inspection current = get(inspectionId);
    requireActiveTechnician(command.technicianId());
    if (current.status() != Status.DRAFT && current.status() != Status.ASSIGNED) {
      throw new ApiException(HttpStatus.CONFLICT, "INSPECTION_NOT_ASSIGNABLE", "Inspection cannot be assigned in its current state");
    }
    int updated = jdbc.update("update inspection set technician_id=?,status='ASSIGNED',updated_at=current_timestamp,version=version+1 where id=? and version=? and status in ('DRAFT','ASSIGNED')",
        command.technicianId(), inspectionId, command.version());
    if (updated == 0) throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Inspection was modified by another request");
    return get(inspectionId);
  }

  /** RN-029/030: registra a trilha de cancelamento e nunca cancela uma inspeção aprovada. */
  @Transactional
  public Inspection cancel(UUID inspectionId, CancellationCommand command, UUID canceledBy) {
    Inspection current = get(inspectionId);
    if (current.status() == Status.APPROVED) {
      throw new ApiException(HttpStatus.CONFLICT, "INSPECTION_CANNOT_BE_CANCELED", "Approved inspection cannot be canceled");
    }
    if (current.status() == Status.CANCELED) {
      throw new ApiException(HttpStatus.CONFLICT, "INSPECTION_ALREADY_CANCELED", "Inspection is already canceled");
    }
    int updated = jdbc.update(
        "update inspection set status='CANCELED',canceled_by=?,canceled_at=current_timestamp,canceled_reason=?,updated_at=current_timestamp,version=version+1 where id=? and version=? and status not in ('APPROVED','CANCELED')",
        canceledBy, command.reason().trim(), inspectionId, command.version());
    if (updated == 0) {
      throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Inspection was modified by another request");
    }
    return get(inspectionId);
  }

  public List<Inspection> assignedTo(UUID technicianId) {
    return jdbc.query("select * from inspection where technician_id=? and status in ('ASSIGNED','IN_PROGRESS') order by scheduled_for,id",
        (rs, row) -> mapInspection(rs), technicianId);
  }

  /** Listagem administrativa paginada para acompanhamento (AC-ADMIN-MONITOR). */
  public Page list(int page, int size, String sort, Status status, OffsetDateTime scheduledFrom,
      OffsetDateTime scheduledTo, UUID technicianId, UUID clientId) {
    if (scheduledFrom != null && scheduledTo != null && scheduledFrom.isAfter(scheduledTo)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "scheduledFrom must be before scheduledTo");
    }
    Timestamp from = scheduledFrom == null ? null : Timestamp.from(scheduledFrom.toInstant());
    Timestamp to = scheduledTo == null ? null : Timestamp.from(scheduledTo.toInstant());
    String where = " where (? is null or status=?) and (? is null or scheduled_for>=?) and (? is null or scheduled_for<=?)"
        + " and (? is null or technician_id=?) and (? is null or client_id=?)";
    Object[] filters = {status == null ? null : status.name(), status == null ? null : status.name(), from, from, to, to,
        technicianId, technicianId, clientId, clientId};
    long total = jdbc.queryForObject("select count(*) from inspection" + where, Long.class, filters);
    List<Inspection> items = jdbc.query(
        "select * from inspection" + where + " order by " + PageQuery.orderBy(sort,
            java.util.Map.of("scheduledFor", "scheduled_for", "status", "status", "priority", "priority", "createdAt", "created_at"))
            + " limit ? offset ?",
        (rs, row) -> mapInspection(rs), concat(filters, size, page * size));
    return new Page(items, new PageMeta(page, size, total, (int) Math.ceil((double) total / size)));
  }

  /** Evidências e não conformidades já recebidas pela API para o acompanhamento. */
  public Monitoring monitoring(UUID inspectionId) {
    get(inspectionId);
    List<Evidence> evidences = jdbc.query(
        "select e.id,e.inspection_response_id,r.inspection_item_id,e.storage_key,e.file_name,e.content_type,e.size_bytes,e.created_by,e.created_at from evidence e join inspection_response r on r.id=e.inspection_response_id where r.inspection_id=? order by e.created_at,e.id",
        (rs, row) -> new Evidence(uuid(rs.getString("id")), uuid(rs.getString("inspection_response_id")), uuid(rs.getString("inspection_item_id")), rs.getString("storage_key"), rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size_bytes"), optionalUuid(rs.getString("created_by")), rs.getTimestamp("created_at").toInstant()),
        inspectionId);
    List<NonConformity> nonConformities = jdbc.query(
        "select id,inspection_response_id,severity,description,status,resolved_at,resolved_by,created_at from non_conformity where inspection_id=? order by created_at,id",
        (rs, row) -> {
          Timestamp resolvedAt = rs.getTimestamp("resolved_at");
          return new NonConformity(uuid(rs.getString("id")), optionalUuid(rs.getString("inspection_response_id")), Severity.valueOf(rs.getString("severity")), rs.getString("description"), NonConformityStatus.valueOf(rs.getString("status")), resolvedAt == null ? null : resolvedAt.toInstant(), optionalUuid(rs.getString("resolved_by")), rs.getTimestamp("created_at").toInstant());
        }, inspectionId);
    return new Monitoring(inspectionId, evidences, nonConformities);
  }

  public EvidenceContent evidenceContent(UUID inspectionId, UUID evidenceId) {
    get(inspectionId);
    List<EvidenceContent> rows = jdbc.query(
        "select e.file_name,e.content_type,e.storage_key from evidence e join inspection_response r on r.id=e.inspection_response_id where e.id=? and r.inspection_id=?",
        (rs, row) -> new EvidenceContent(rs.getString("file_name"), rs.getString("content_type"), files.read(rs.getString("storage_key"))),
        evidenceId, inspectionId);
    if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND", "Evidence not found");
    return rows.getFirst();
  }

  private static Object[] concat(Object[] values, Object... tail) {
    Object[] result = java.util.Arrays.copyOf(values, values.length + tail.length);
    System.arraycopy(tail, 0, result, values.length, tail.length);
    return result;
  }

  private PublishedVersion requirePublishedVersion(UUID versionId) {
    List<PublishedVersion> versions = jdbc.query("select id,title_snapshot from inspection_template_version where id=? and published_at is not null and active_for_new_inspections=true", (rs, row) -> new PublishedVersion(UUID.fromString(rs.getString("id")), rs.getString("title_snapshot")), versionId);
    if (versions.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "TEMPLATE_VERSION_NOT_PUBLISHED", "Inspection requires a published template version");
    return versions.getFirst();
  }

  private void requireClient(UUID id) {
    if (!exists("select exists(select 1 from client where id=?)", id)) throw new ApiException(HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND", "Client not found");
  }

  private void requireSiteForClient(UUID siteId, UUID clientId) {
    if (!exists("select exists(select 1 from inspection_site where id=?)", siteId)) throw new ApiException(HttpStatus.NOT_FOUND, "SITE_NOT_FOUND", "Site not found");
    if (!exists("select exists(select 1 from inspection_site where id=? and client_id=?)", siteId, clientId)) throw new ApiException(HttpStatus.CONFLICT, "SITE_CLIENT_MISMATCH", "Site does not belong to the selected client");
  }

  private void requireActiveTechnician(UUID id) {
    List<String> users = jdbc.query("select role || ':' || status from app_user where id=?", (rs, row) -> rs.getString(1), id);
    if (users.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "TECHNICIAN_NOT_FOUND", "Technician not found");
    String[] values = users.getFirst().split(":");
    if (!"TECHNICIAN".equals(values[0])) throw new ApiException(HttpStatus.CONFLICT, "TECHNICIAN_ROLE_INVALID", "Assigned user must have TECHNICIAN role");
    if (!"ACTIVE".equals(values[1])) throw new ApiException(HttpStatus.CONFLICT, "TECHNICIAN_NOT_ACTIVE", "Assigned technician must be active");
  }

  private boolean exists(String sql, Object... args) {
    Boolean value = jdbc.queryForObject(sql, Boolean.class, args);
    return Boolean.TRUE.equals(value);
  }

  private void copyItemSnapshot(UUID inspectionId, UUID versionId) {
    List<SnapshotSource> items = jdbc.query("select s.title as section_title,s.description as section_description,s.display_order as section_order,i.id,i.code,i.title,i.description,i.response_type,i.required,i.observation_required_on_failure,i.evidence_required_on_failure,i.options_json,i.display_order as item_order from template_section s join template_item i on i.section_id=s.id where s.template_version_id=? order by s.display_order,i.display_order", (rs, row) -> new SnapshotSource(UUID.fromString(rs.getString("id")), rs.getString("section_title"), rs.getString("section_description"), rs.getInt("section_order"), rs.getString("code"), rs.getString("title"), rs.getString("description"), rs.getString("response_type"), rs.getBoolean("required"), rs.getBoolean("observation_required_on_failure"), rs.getBoolean("evidence_required_on_failure"), rs.getString("options_json"), rs.getInt("item_order")), versionId);
    for (SnapshotSource item : items) {
      jdbc.update("insert into inspection_item_snapshot (id,inspection_id,source_template_item_id,section_title,section_description,section_order,item_code,item_title,item_description,response_type,required,rules_json,options_json,item_order) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(), inspectionId, item.sourceItemId(), item.sectionTitle(), item.sectionDescription(), item.sectionOrder(),
          item.code(), item.title(), item.description(), item.responseType(), item.required(), rulesJson(item), item.optionsJson(), item.itemOrder());
    }
  }

  private static String rulesJson(SnapshotSource item) {
    return "{\"observationRequiredOnFailure\":" + item.observationRequiredOnFailure()
        + ",\"evidenceRequiredOnFailure\":" + item.evidenceRequiredOnFailure() + "}";
  }

  private Inspection get(UUID id) {
    List<Inspection> inspections = jdbc.query("select * from inspection where id=?", (rs, row) -> mapInspection(rs), id);
    if (inspections.isEmpty()) {
      throw new ApiException(HttpStatus.NOT_FOUND, "INSPECTION_NOT_FOUND", "Inspection not found");
    }
    return inspections.getFirst();
  }

  private static Inspection mapInspection(java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp canceledAt = rs.getTimestamp("canceled_at");
    return new Inspection(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("client_id")), UUID.fromString(rs.getString("site_id")), UUID.fromString(rs.getString("template_version_id")), UUID.fromString(rs.getString("technician_id")), UUID.fromString(rs.getString("supervisor_id")), rs.getString("title"), rs.getString("instructions"), Priority.valueOf(rs.getString("priority")), Status.valueOf(rs.getString("status")), rs.getTimestamp("scheduled_for").toInstant(), rs.getInt("version"), rs.getObject("canceled_by", UUID.class), canceledAt == null ? null : canceledAt.toInstant(), rs.getString("canceled_reason"));
  }

  public enum Status { DRAFT, ASSIGNED, IN_PROGRESS, SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED, CANCELED }
  public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }
  public record ScheduleCommand(UUID templateVersionId, UUID clientId, UUID siteId, UUID technicianId, OffsetDateTime scheduledFor, String title, String instructions, Priority priority) {}
  public record AssignmentCommand(UUID technicianId, int version) {}
  public record CancellationCommand(String reason, int version) {}
  public record Inspection(UUID id, UUID clientId, UUID siteId, UUID templateVersionId, UUID technicianId, UUID supervisorId, String title, String instructions, Priority priority, Status status, Instant scheduledFor, int version, UUID canceledBy, Instant canceledAt, String canceledReason) {}
  public record Page(List<Inspection> items, PageMeta page) {}
  public record PageMeta(int page, int size, long totalElements, int totalPages) {}
  public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
  public enum NonConformityStatus { OPEN, RESOLVED, WAIVED }
  public record Monitoring(UUID inspectionId, List<Evidence> evidences, List<NonConformity> nonConformities) {}
  public record Evidence(UUID id, UUID inspectionResponseId, UUID inspectionItemId, String storageKey, String fileName, String contentType, long sizeBytes, UUID createdBy, Instant createdAt) {}
  public record NonConformity(UUID id, UUID inspectionResponseId, Severity severity, String description, NonConformityStatus status, Instant resolvedAt, UUID resolvedBy, Instant createdAt) {}
  public record EvidenceContent(String fileName, String contentType, byte[] bytes) {}
  private static UUID uuid(String value) { return UUID.fromString(value); }
  private static UUID optionalUuid(String value) { return value == null ? null : UUID.fromString(value); }
  private record PublishedVersion(UUID id, String title) {}
  private record SnapshotSource(UUID sourceItemId, String sectionTitle, String sectionDescription, int sectionOrder, String code, String title, String description, String responseType, boolean required, boolean observationRequiredOnFailure, boolean evidenceRequiredOnFailure, String optionsJson, int itemOrder) {}
}
