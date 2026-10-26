package com.fieldops.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldops.shared.error.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateItemService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json = new ObjectMapper();

  public TemplateItemService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public Item create(UUID sectionId, CreateCommand command) {
    requireDraftSection(sectionId);
    validateOptions(command.responseType(), command.options());
    UUID id = UUID.randomUUID();
    try {
      jdbc.update("insert into template_item (id,section_id,code,title,description,response_type,required,observation_required_on_failure,evidence_required_on_failure,display_order,options_json) values (?,?,?,?,?,?,?,?,?,?,?)",
          id, sectionId, command.code(), command.title(), command.description(), command.responseType().name(), command.required(),
          command.observationRequiredOnFailure(), command.evidenceRequiredOnFailure(), command.displayOrder(), optionsJson(command.options()));
    } catch (DataIntegrityViolationException ex) {
      throw orderConflict();
    }
    return get(id);
  }

  public List<Item> listBySection(UUID sectionId) {
    requireSection(sectionId);
    return jdbc.query("select * from template_item where section_id=? order by display_order,id", (rs, row) -> map(rs), sectionId);
  }

  private Item get(UUID id) {
    List<Item> items = jdbc.query("select * from template_item where id=?", (rs, row) -> map(rs), id);
    if (items.isEmpty()) throw itemNotFound();
    return items.getFirst();
  }

  private void requireDraftSection(UUID sectionId) {
    Boolean exists = jdbc.queryForObject("select exists(select 1 from template_section s join inspection_template_version v on v.id=s.template_version_id join inspection_template t on t.id=v.template_id where s.id=? and t.status='DRAFT')", Boolean.class, sectionId);
    if (Boolean.TRUE.equals(exists)) return;
    requireSection(sectionId);
    throw templateNotEditable();
  }

  private void requireSection(UUID sectionId) {
    Boolean exists = jdbc.queryForObject("select exists(select 1 from template_section where id=?)", Boolean.class, sectionId);
    if (!Boolean.TRUE.equals(exists)) throw sectionNotFound();
  }

  private void validateOptions(ResponseType type, List<String> options) {
    boolean hasOptions = options != null && !options.isEmpty();
    if (type == ResponseType.SINGLE_CHOICE) {
      if (!hasOptions || new LinkedHashSet<>(options).size() != options.size()) throw invalidOptions();
    } else if (hasOptions) {
      throw invalidOptions();
    }
  }

  private String optionsJson(List<String> options) {
    if (options == null || options.isEmpty()) return null;
    try {
      return json.writeValueAsString(options);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Could not serialize response options", ex);
    }
  }

  private static Item map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Item(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("section_id")), rs.getString("code"),
        rs.getString("title"), rs.getString("description"), ResponseType.valueOf(rs.getString("response_type")),
        rs.getBoolean("required"), rs.getBoolean("observation_required_on_failure"),
        rs.getBoolean("evidence_required_on_failure"), rs.getInt("display_order"), rs.getString("options_json"),
        instant(rs.getTimestamp("created_at")));
  }

  private static Instant instant(Timestamp value) { return value.toInstant(); }
  private static ApiException sectionNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_SECTION_NOT_FOUND", "Template section not found"); }
  private static ApiException itemNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_ITEM_NOT_FOUND", "Template item not found"); }
  private static ApiException templateNotEditable() { return new ApiException(HttpStatus.CONFLICT, "TEMPLATE_NOT_EDITABLE", "Only draft inspection templates can be edited"); }
  private static ApiException orderConflict() { return new ApiException(HttpStatus.CONFLICT, "ITEM_ORDER_ALREADY_EXISTS", "An item already uses this display order in the section"); }
  private static ApiException invalidOptions() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESPONSE_OPTIONS", "SINGLE_CHOICE requires unique options and other response types must not define options"); }

  public enum ResponseType { TEXT_SHORT, TEXT_LONG, NUMBER, BOOLEAN, CONFORMITY, SINGLE_CHOICE, DATE }
  public record CreateCommand(String code, String title, String description, ResponseType responseType, boolean required,
                              boolean observationRequiredOnFailure, boolean evidenceRequiredOnFailure, int displayOrder,
                              List<String> options) {}
  public record Item(UUID id, UUID sectionId, String code, String title, String description, ResponseType responseType,
                     boolean required, boolean observationRequiredOnFailure, boolean evidenceRequiredOnFailure,
                     int displayOrder, String optionsJson, Instant createdAt) {}
}
