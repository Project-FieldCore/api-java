package com.fieldops.template;

import com.fieldops.shared.error.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InspectionTemplateService {
  private final JdbcTemplate jdbc;

  public InspectionTemplateService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public Template get(UUID id) {
    List<Template> templates = jdbc.query(TEMPLATE_SELECT + " where t.id=?", (rs, row) -> map(rs), id);
    if (templates.isEmpty()) throw notFound();
    return templates.getFirst();
  }

  @Transactional
  public Template create(CreateCommand command, UUID createdBy) {
    UUID id = UUID.randomUUID();
    UUID draftVersionId = UUID.randomUUID();
    try {
      jdbc.update("""
          insert into inspection_template (id,title,description,category,status,current_version,created_by)
          values (?,?,?,?,?,?,?)
          """,
          id, command.title(), command.description(), command.category(), Status.DRAFT.name(), 1, createdBy);
      jdbc.update("""
          insert into inspection_template_version
            (id,template_id,version_number,title_snapshot,description_snapshot,active_for_new_inspections)
          values (?,?,?,?,?,?)
          """,
          draftVersionId, id, 1, command.title(), command.description(), false);
    } catch (DataIntegrityViolationException ex) {
      throw titleConflict();
    }
    return get(id);
  }

  @Transactional
  public Template updateDraft(UUID id, UpdateCommand command) {
    Template current = get(id);
    if (current.status() != Status.DRAFT) throw notEditable();
    String title = command.title() == null ? current.title() : command.title();
    String description = command.description() == null ? current.description() : command.description();
    String category = command.category() == null ? current.category() : command.category();
    int updated;
    try {
      updated = jdbc.update("""
          update inspection_template
             set title=?, description=?, category=?, updated_at=current_timestamp, version=version+1
           where id=? and version=? and status='DRAFT'
          """,
          title, description, category, id, command.version());
    } catch (DataIntegrityViolationException ex) {
      throw titleConflict();
    }
    if (updated == 0) throw versionConflict();
    if (current.draftVersionId() != null) {
      jdbc.update("""
          update inspection_template_version
             set title_snapshot=?, description_snapshot=?
           where id=? and template_id=?
          """,
          title, description, current.draftVersionId(), id);
    }
    return get(id);
  }

  private static final String TEMPLATE_SELECT = """
      select t.*,
             (select v.id from inspection_template_version v
               where v.template_id = t.id and v.published_at is null
               order by v.version_number desc limit 1) as draft_version_id,
             (select v.id from inspection_template_version v
               where v.template_id = t.id
               order by v.version_number desc limit 1) as latest_version_id
        from inspection_template t
      """;

  private static Template map(java.sql.ResultSet rs) throws java.sql.SQLException {
    String createdBy = rs.getString("created_by");
    Integer currentVersion = (Integer) rs.getObject("current_version");
    String draftVersionId = rs.getString("draft_version_id");
    String latestVersionId = rs.getString("latest_version_id");
    return new Template(UUID.fromString(rs.getString("id")), rs.getString("title"), rs.getString("description"),
        rs.getString("category"), Status.valueOf(rs.getString("status")), currentVersion,
        createdBy == null ? null : UUID.fromString(createdBy), instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")), rs.getInt("version"),
        draftVersionId == null ? null : UUID.fromString(draftVersionId),
        latestVersionId == null ? null : UUID.fromString(latestVersionId));
  }

  private static Instant instant(Timestamp value) { return value.toInstant(); }
  private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "INSPECTION_TEMPLATE_NOT_FOUND", "Inspection template not found"); }
  private static ApiException titleConflict() { return new ApiException(HttpStatus.CONFLICT, "TEMPLATE_TITLE_ALREADY_EXISTS", "An inspection template with this title already exists"); }
  private static ApiException notEditable() { return new ApiException(HttpStatus.CONFLICT, "TEMPLATE_NOT_EDITABLE", "Only draft inspection templates can be edited"); }
  private static ApiException versionConflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Inspection template was modified by another request"); }

  public enum Status { DRAFT, ACTIVE, INACTIVE }
  public record CreateCommand(String title, String description, String category) {}
  public record UpdateCommand(String title, String description, String category, int version) {}
  public record Template(UUID id, String title, String description, String category, Status status, Integer currentVersion,
                         UUID createdBy, Instant createdAt, Instant updatedAt, int version,
                         UUID draftVersionId, UUID latestVersionId) {}
}
