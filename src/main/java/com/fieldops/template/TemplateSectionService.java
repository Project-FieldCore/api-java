package com.fieldops.template;

import com.fieldops.shared.error.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateSectionService {
  private final JdbcTemplate jdbc;
  public TemplateSectionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public List<Section> list(UUID templateVersionId) {
    requireEditableVersion(templateVersionId);
    return jdbc.query("select * from template_section where template_version_id=? order by display_order,id", (rs, row) -> map(rs), templateVersionId);
  }

  @Transactional
  public Section create(UUID templateVersionId, CreateCommand command) {
    requireEditableVersion(templateVersionId);
    UUID id = UUID.randomUUID();
    try { jdbc.update("insert into template_section (id,template_version_id,title,description,display_order) values (?,?,?,?,?)", id, templateVersionId, command.title(), command.description(), command.displayOrder()); }
    catch (org.springframework.dao.DataIntegrityViolationException ex) { throw orderConflict(); }
    return get(id);
  }

  @Transactional
  public List<Section> reorder(UUID templateVersionId, List<OrderCommand> commands) {
    requireEditableVersion(templateVersionId);
    if (commands.isEmpty() || commands.stream().map(OrderCommand::sectionId).distinct().count() != commands.size()
        || commands.stream().map(OrderCommand::displayOrder).distinct().count() != commands.size()) throw invalidOrder();
    for (OrderCommand command : commands) if (!belongsToVersion(command.sectionId(), templateVersionId)) throw sectionNotFound();
    for (int i = 0; i < commands.size(); i++) jdbc.update("update template_section set display_order=? where id=?", 100000 + i, commands.get(i).sectionId());
    for (OrderCommand command : commands) jdbc.update("update template_section set display_order=? where id=?", command.displayOrder(), command.sectionId());
    return list(templateVersionId);
  }

  private Section get(UUID id) { return jdbc.queryForObject("select * from template_section where id=?", (rs, row) -> map(rs), id); }
  private boolean belongsToVersion(UUID id, UUID versionId) { Boolean value = jdbc.queryForObject("select exists(select 1 from template_section where id=? and template_version_id=?)", Boolean.class, id, versionId); return Boolean.TRUE.equals(value); }
  private void requireEditableVersion(UUID id) {
    List<Boolean> versions = jdbc.query("select t.status='DRAFT' from inspection_template_version v join inspection_template t on t.id=v.template_id where v.id=?", (rs, row) -> rs.getBoolean(1), id);
    if (versions.isEmpty()) throw versionNotFound();
    if (!versions.getFirst()) throw notEditable();
  }
  private static Section map(java.sql.ResultSet rs) throws java.sql.SQLException { return new Section(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("template_version_id")), rs.getString("title"), rs.getString("description"), rs.getInt("display_order"), instant(rs.getTimestamp("created_at"))); }
  private static Instant instant(Timestamp value) { return value.toInstant(); }
  private static ApiException versionNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_VERSION_NOT_FOUND", "Template version not found"); }
  private static ApiException sectionNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_SECTION_NOT_FOUND", "Template section not found"); }
  private static ApiException orderConflict() { return new ApiException(HttpStatus.CONFLICT, "SECTION_ORDER_ALREADY_EXISTS", "Section display order already exists in this template version"); }
  private static ApiException invalidOrder() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SECTION_ORDER", "Section identifiers and display orders must be unique"); }
  private static ApiException notEditable() { return new ApiException(HttpStatus.CONFLICT, "TEMPLATE_NOT_EDITABLE", "Only draft templates can be edited"); }
  public record CreateCommand(String title, String description, int displayOrder) {}
  public record OrderCommand(UUID sectionId, int displayOrder) {}
  public record Section(UUID id, UUID templateVersionId, String title, String description, int displayOrder, Instant createdAt) {}
}
