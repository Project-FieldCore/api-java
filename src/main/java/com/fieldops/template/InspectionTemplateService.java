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
    List<Template> templates = jdbc.query("select * from inspection_template where id=?", (rs, row) -> map(rs), id);
    if (templates.isEmpty()) throw notFound();
    return templates.getFirst();
  }

  @Transactional
  public Template create(CreateCommand command, UUID createdBy) {
    UUID id = UUID.randomUUID();
    try {
      jdbc.update("insert into inspection_template (id,title,description,category,status,created_by) values (?,?,?,?,?,?)",
          id, command.title(), command.description(), command.category(), Status.DRAFT.name(), createdBy);
    } catch (DataIntegrityViolationException ex) {
      throw titleConflict();
    }
    return get(id);
  }

  @Transactional
  public Template updateDraft(UUID id, UpdateCommand command) {
    Template current = get(id);
    if (current.status() != Status.DRAFT) throw notEditable();
    int updated;
    try {
      updated = jdbc.update("update inspection_template set title=?,description=?,category=?,updated_at=current_timestamp,version=version+1 where id=? and version=? and status='DRAFT'",
          command.title() == null ? current.title() : command.title(),
          command.description() == null ? current.description() : command.description(),
          command.category() == null ? current.category() : command.category(), id, command.version());
    } catch (DataIntegrityViolationException ex) {
      throw titleConflict();
    }
    if (updated == 0) throw versionConflict();
    return get(id);
  }

  private static Template map(java.sql.ResultSet rs) throws java.sql.SQLException {
    String createdBy = rs.getString("created_by");
    Integer currentVersion = (Integer) rs.getObject("current_version");
    return new Template(UUID.fromString(rs.getString("id")), rs.getString("title"), rs.getString("description"),
        rs.getString("category"), Status.valueOf(rs.getString("status")), currentVersion,
        createdBy == null ? null : UUID.fromString(createdBy), instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")), rs.getInt("version"));
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
                         UUID createdBy, Instant createdAt, Instant updatedAt, int version) {}
}
