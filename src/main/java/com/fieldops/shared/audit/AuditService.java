package com.fieldops.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Writes and reads {@code audit_event} rows for domain state transitions (RNF-011 / PBI-0063).
 * Covered inspection actions: SCHEDULED, ASSIGNED, STARTED, SUBMITTED, CANCELED,
 * REVIEW_STARTED, REVIEW_APPROVED, REVIEW_REJECTED.
 * Intentionally deferred (no status change): evidence upload, nonconformity CRUD, QR confirm,
 * item answer patches, priority/deadline/instruction edits.
 */
@Service
public class AuditService {
  public static final String ENTITY_INSPECTION = "INSPECTION";

  private final JdbcTemplate jdbc;
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  public AuditService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void record(UUID actorId, String entityType, UUID entityId, String action, Map<String, ?> payload) {
    jdbc.update(
        "insert into audit_event (id,actor_id,entity_type,entity_id,action,payload) values (?,?,?,?,?,?)",
        UUID.randomUUID(), actorId, entityType, entityId, action, toJson(payload));
  }

  public void recordInspection(UUID actorId, UUID inspectionId, String action, String from, String to) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("from", from);
    payload.put("to", to);
    record(actorId, ENTITY_INSPECTION, inspectionId, action, payload);
  }

  public void recordInspection(UUID actorId, UUID inspectionId, String action, Map<String, ?> payload) {
    record(actorId, ENTITY_INSPECTION, inspectionId, action, payload);
  }

  public List<AuditEventView> listForInspection(UUID inspectionId) {
    return jdbc.query(
        """
        select a.id, a.actor_id, u.name as actor_name, a.action, a.payload, a.created_at
        from audit_event a
        left join app_user u on u.id = a.actor_id
        where a.entity_type = ? and a.entity_id = ?
        order by a.created_at asc, a.id asc
        """,
        (rs, row) -> {
          Timestamp created = rs.getTimestamp("created_at");
          String actor = rs.getString("actor_id");
          return new AuditEventView(
              UUID.fromString(rs.getString("id")),
              actor == null ? null : UUID.fromString(actor),
              rs.getString("actor_name"),
              rs.getString("action"),
              rs.getString("payload"),
              created == null ? null : created.toInstant());
        },
        ENTITY_INSPECTION, inspectionId);
  }

  /** Administrative, server-side paginated audit trail. Payload is returned as stored, never interpreted by the client. */
  public AuditPage list(int page, int size, String sort, String q, String entityType, String action,
                        UUID actorId, OffsetDateTime from, OffsetDateTime to) {
    String[] sortParts = sort == null ? new String[0] : sort.split(",", 2);
    String orderColumn = sortParts.length > 0 && "action".equalsIgnoreCase(sortParts[0]) ? "a.action" : "a.created_at";
    String direction = sortParts.length > 1 && "asc".equalsIgnoreCase(sortParts[1]) ? "asc" : "desc";
    List<Object> params = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    if (entityType != null && !entityType.isBlank()) { where.append(" and a.entity_type=?"); params.add(entityType.trim().toUpperCase()); }
    if (action != null && !action.isBlank()) { where.append(" and a.action=?"); params.add(action.trim().toUpperCase()); }
    if (actorId != null) { where.append(" and a.actor_id=?"); params.add(actorId); }
    if (from != null) { where.append(" and a.created_at>=?"); params.add(Timestamp.from(from.toInstant())); }
    if (to != null) { where.append(" and a.created_at<=?"); params.add(Timestamp.from(to.toInstant())); }
    if (q != null && !q.isBlank()) {
      where.append(" and (lower(coalesce(u.name,'')) like ? or lower(a.action) like ? or lower(coalesce(a.payload,'')) like ? or lower(a.entity_type) like ?)");
      String term = "%" + q.trim().toLowerCase() + "%";
      params.add(term); params.add(term); params.add(term); params.add(term);
    }
    Long total = jdbc.queryForObject("select count(*) from audit_event a left join app_user u on u.id=a.actor_id" + where, Long.class, params.toArray());
    List<Object> listParams = new ArrayList<>(params);
    listParams.add(size);
    listParams.add(page * size);
    List<AuditEvent> items = jdbc.query(
        "select a.id,a.actor_id,u.name as actor_name,a.entity_type,a.entity_id,a.action,a.payload,a.created_at "
            + "from audit_event a left join app_user u on u.id=a.actor_id" + where
            + " order by " + orderColumn + " " + direction + ", a.id " + direction + " limit ? offset ?",
        (rs, row) -> mapEvent(rs), listParams.toArray());
    long count = total == null ? 0 : total;
    return new AuditPage(items, new PageMeta(page, size, count, (int) Math.ceil(count / (double) size)));
  }

  private static AuditEvent mapEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp created = rs.getTimestamp("created_at");
    String actor = rs.getString("actor_id");
    return new AuditEvent(UUID.fromString(rs.getString("id")), actor == null ? null : UUID.fromString(actor),
        rs.getString("actor_name"), rs.getString("entity_type"), UUID.fromString(rs.getString("entity_id")),
        rs.getString("action"), rs.getString("payload"), created == null ? null : created.toInstant());
  }

  private String toJson(Map<String, ?> payload) {
    if (payload == null || payload.isEmpty()) return null;
    try {
      return json.writeValueAsString(payload);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize audit payload", ex);
    }
  }

  public record AuditEventView(UUID id, UUID actorId, String actorName, String action, String payload, Instant createdAt) {}
  public record AuditEvent(UUID id, UUID actorId, String actorName, String entityType, UUID entityId, String action, String payload, Instant createdAt) {}
  public record AuditPage(List<AuditEvent> items, PageMeta page) {}
  public record PageMeta(int page, int size, long totalElements, int totalPages) {}
}
