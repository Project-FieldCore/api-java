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
    if (current.status() != Status.DRAFT) {
      if (current.latestVersionId() == null) throw notEditable();
      current = createNextDraft(current);
    }
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

  @Transactional
  public Template publish(UUID id, PublishCommand command, UUID publishedBy) {
    Template current = get(id);
    if (current.status() != Status.DRAFT || current.draftVersionId() == null) throw notEditable();
    if (!hasPublishableContent(current.draftVersionId())) throw invalidForPublication();
    int updated = jdbc.update("update inspection_template set status='ACTIVE',current_version=?,updated_at=current_timestamp,version=version+1 where id=? and version=? and status='DRAFT'",
        versionNumber(current.draftVersionId()), id, command.version());
    if (updated == 0) throw versionConflict();
    jdbc.update("update inspection_template_version set active_for_new_inspections=false where template_id=?", id);
    jdbc.update("update inspection_template_version set published_at=current_timestamp,published_by=?,active_for_new_inspections=true where id=?", publishedBy, current.draftVersionId());
    return get(id);
  }

  private Template createNextDraft(Template current) {
    UUID source = current.latestVersionId();
    UUID next = UUID.randomUUID();
    int number = versionNumber(source) + 1;
    jdbc.update("insert into inspection_template_version (id,template_id,version_number,title_snapshot,description_snapshot,active_for_new_inspections) values (?,?,?,?,?,false)",
        next, current.id(), number, current.title(), current.description());
    List<SectionCopy> sections = jdbc.query("select id,title,description,display_order from template_section where template_version_id=?", (rs,row) -> new SectionCopy(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getString(3),rs.getInt(4)), source);
    for (SectionCopy section : sections) {
      UUID newSection = UUID.randomUUID();
      jdbc.update("insert into template_section (id,template_version_id,title,description,display_order) values (?,?,?,?,?)", newSection,next,section.title(),section.description(),section.order());
      jdbc.query("select code,title,description,response_type,required,observation_required_on_failure,evidence_required_on_failure,display_order,options_json from template_item where section_id=?", (rs,row) -> { jdbc.update("insert into template_item (id,section_id,code,title,description,response_type,required,observation_required_on_failure,evidence_required_on_failure,display_order,options_json) values (?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID(),newSection,rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBoolean(5),rs.getBoolean(6),rs.getBoolean(7),rs.getInt(8),rs.getString(9)); return 0; }, section.id());
    }
    jdbc.update("update inspection_template set status='DRAFT' where id=?", current.id());
    return get(current.id());
  }

  private boolean hasPublishableContent(UUID versionId) {
    Integer sections = jdbc.queryForObject("select count(*) from template_section where template_version_id=?", Integer.class, versionId);
    Integer items = jdbc.queryForObject("select count(*) from template_item i join template_section s on s.id=i.section_id where s.template_version_id=? and i.response_type is not null", Integer.class, versionId);
    return sections != null && sections > 0 && items != null && items > 0;
  }
  private int versionNumber(UUID id) { return jdbc.queryForObject("select version_number from inspection_template_version where id=?", Integer.class, id); }

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
  private static ApiException invalidForPublication() { return new ApiException(HttpStatus.CONFLICT, "TEMPLATE_NOT_PUBLISHABLE", "A template requires at least one section and one typed item before publication"); }
  private static ApiException versionConflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Inspection template was modified by another request"); }

  public enum Status { DRAFT, ACTIVE, INACTIVE }
  public record CreateCommand(String title, String description, String category) {}
  public record UpdateCommand(String title, String description, String category, int version) {}
  public record PublishCommand(int version) {}
  private record SectionCopy(UUID id, String title, String description, int order) {}
  public record Template(UUID id, String title, String description, String category, Status status, Integer currentVersion,
                         UUID createdBy, Instant createdAt, Instant updatedAt, int version,
                         UUID draftVersionId, UUID latestVersionId) {}
}
