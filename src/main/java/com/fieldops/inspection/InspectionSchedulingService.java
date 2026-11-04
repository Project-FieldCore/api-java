package com.fieldops.inspection;

import com.fieldops.shared.error.ApiException;
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

  public InspectionSchedulingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
    List<SnapshotSource> items = jdbc.query("select s.title as section_title,s.description as section_description,s.display_order as section_order,i.id,i.code,i.title,i.description,i.response_type,i.required,i.options_json,i.display_order as item_order from template_section s join template_item i on i.section_id=s.id where s.template_version_id=? order by s.display_order,i.display_order", (rs, row) -> new SnapshotSource(UUID.fromString(rs.getString("id")), rs.getString("section_title"), rs.getString("section_description"), rs.getInt("section_order"), rs.getString("code"), rs.getString("title"), rs.getString("description"), rs.getString("response_type"), rs.getBoolean("required"), rs.getString("options_json"), rs.getInt("item_order")), versionId);
    for (SnapshotSource item : items) {
      jdbc.update("insert into inspection_item_snapshot (id,inspection_id,source_template_item_id,section_title,section_description,section_order,item_code,item_title,item_description,response_type,required,rules_json,options_json,item_order) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(), inspectionId, item.sourceItemId(), item.sectionTitle(), item.sectionDescription(), item.sectionOrder(),
          item.code(), item.title(), item.description(), item.responseType(), item.required(), null, item.optionsJson(), item.itemOrder());
    }
  }

  private Inspection get(UUID id) {
    List<Inspection> inspections = jdbc.query("select * from inspection where id=?", (rs, row) -> new Inspection(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("client_id")), UUID.fromString(rs.getString("site_id")), UUID.fromString(rs.getString("template_version_id")), UUID.fromString(rs.getString("technician_id")), UUID.fromString(rs.getString("supervisor_id")), rs.getString("title"), rs.getString("instructions"), Priority.valueOf(rs.getString("priority")), Status.valueOf(rs.getString("status")), rs.getTimestamp("scheduled_for").toInstant(), rs.getInt("version")), id);
    return inspections.getFirst();
  }

  public enum Status { DRAFT, ASSIGNED, IN_PROGRESS, SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED, CANCELED }
  public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }
  public record ScheduleCommand(UUID templateVersionId, UUID clientId, UUID siteId, UUID technicianId, OffsetDateTime scheduledFor, String title, String instructions, Priority priority) {}
  public record Inspection(UUID id, UUID clientId, UUID siteId, UUID templateVersionId, UUID technicianId, UUID supervisorId, String title, String instructions, Priority priority, Status status, Instant scheduledFor, int version) {}
  private record PublishedVersion(UUID id, String title) {}
  private record SnapshotSource(UUID sourceItemId, String sectionTitle, String sectionDescription, int sectionOrder, String code, String title, String description, String responseType, boolean required, String optionsJson, int itemOrder) {}
}
