package com.fieldops.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
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

  private String toJson(Map<String, ?> payload) {
    if (payload == null || payload.isEmpty()) return null;
    try {
      return json.writeValueAsString(payload);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize audit payload", ex);
    }
  }

  public record AuditEventView(UUID id, UUID actorId, String actorName, String action, String payload, Instant createdAt) {}
}
