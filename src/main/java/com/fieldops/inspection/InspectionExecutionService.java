package com.fieldops.inspection;

import com.fieldops.shared.error.ApiException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Execução do técnico: baixar a inspeção atribuída, iniciar, ler o snapshot,
 * gravar observação e concluir só com as regras do item atendidas.
 */
@Service
public class InspectionExecutionService {
  private static final int MAX_EVIDENCE_BYTES = 5 * 1024 * 1024;
  private final JdbcTemplate jdbc;
  private final EvidenceFileStore files;

  public InspectionExecutionService(JdbcTemplate jdbc, EvidenceFileStore files) {
    this.jdbc = jdbc;
    this.files = files;
  }

  public List<AssignedInspection> assignedTo(UUID technicianId) {
    return jdbc.query(SELECT + " where i.technician_id=? and i.status in ('ASSIGNED','IN_PROGRESS') order by i.scheduled_for,i.id",
        (rs, row) -> mapAssigned(rs), technicianId);
  }

  public AssignedInspection detail(UUID inspectionId, UUID technicianId) {
    List<AssignedInspection> found = jdbc.query(SELECT + " where i.id=? and i.technician_id=?",
        (rs, row) -> mapAssigned(rs), inspectionId, technicianId);
    if (found.isEmpty()) throw notFound();
    return found.getFirst();
  }

  public List<ChecklistSection> checklist(UUID inspectionId, UUID technicianId) {
    detail(inspectionId, technicianId);
    List<ChecklistItem> items = jdbc.query(
        "select s.id,s.section_title,s.section_order,s.item_title,s.item_description,s.response_type,s.required,s.rules_json,s.options_json,s.item_order,r.observation,r.value_text,r.conformity from inspection_item_snapshot s left join inspection_response r on r.inspection_item_id=s.id where s.inspection_id=? order by s.section_order,s.item_order",
        (rs, row) -> new ChecklistItem(UUID.fromString(rs.getString("id")), rs.getString("section_title"), rs.getInt("section_order"), rs.getString("item_title"), rs.getString("item_description"), rs.getString("response_type"), rs.getBoolean("required"), rs.getString("rules_json"), rs.getString("options_json"), rs.getInt("item_order"), rs.getString("observation"), rs.getString("value_text"), rs.getString("conformity")),
        inspectionId);
    LinkedHashMap<String, ChecklistSection> sections = new LinkedHashMap<>();
    for (ChecklistItem item : items) {
      sections.computeIfAbsent(item.sectionOrder() + ":" + item.sectionTitle(), key -> new ChecklistSection(item.sectionTitle(), item.sectionOrder(), new ArrayList<>())).items().add(item);
    }
    return List.copyOf(sections.values());
  }

  @Transactional
  public AssignedInspection confirmEquipment(UUID inspectionId, UUID technicianId, String qrCode) {
    AssignedInspection current = detail(inspectionId, technicianId);
    if (current.status() != Status.ASSIGNED && current.status() != Status.IN_PROGRESS) {
      throw new ApiException(HttpStatus.CONFLICT, "INSPECTION_NOT_CONFIRMABLE", "Equipment can be confirmed only while the inspection is assigned or in progress");
    }
    List<EquipmentMatch> matches = jdbc.query(
        "select id, site_id, status from equipment where qr_code=?",
        (rs, row) -> new EquipmentMatch(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("site_id")), rs.getString("status")),
        qrCode.trim());
    if (matches.isEmpty()) {
      throw new ApiException(HttpStatus.NOT_FOUND, "EQUIPMENT_NOT_FOUND", "Equipment not found");
    }
    EquipmentMatch equipment = matches.getFirst();
    if (!"ACTIVE".equals(equipment.status())) {
      throw new ApiException(HttpStatus.CONFLICT, "EQUIPMENT_NOT_ACTIVE", "Only an active equipment can be confirmed");
    }
    InspectionLink link = jdbc.query(
        "select site_id, equipment_id from inspection where id=?",
        (rs, row) -> new InspectionLink(UUID.fromString(rs.getString("site_id")), rs.getString("equipment_id") == null ? null : UUID.fromString(rs.getString("equipment_id"))),
        inspectionId).getFirst();
    if (!link.siteId().equals(equipment.siteId()) || (link.equipmentId() != null && !link.equipmentId().equals(equipment.id()))) {
      throw new ApiException(HttpStatus.CONFLICT, "EQUIPMENT_MISMATCH", "The QR code does not match the equipment of this inspection");
    }
    jdbc.update("update inspection set equipment_id=?, equipment_confirmed_at=current_timestamp, updated_at=current_timestamp, version=version+1 where id=? and technician_id=?",
        equipment.id(), inspectionId, technicianId);
    return detail(inspectionId, technicianId);
  }

  @Transactional
  public AssignedInspection start(UUID inspectionId, UUID technicianId, OffsetDateTime startedAtDevice) {
    AssignedInspection current = detail(inspectionId, technicianId);
    if (current.status() == Status.IN_PROGRESS) return current;
    if (current.status() != Status.ASSIGNED) {
      throw new ApiException(HttpStatus.CONFLICT, "INSPECTION_NOT_STARTABLE", "Only an assigned inspection can be started");
    }
    Timestamp device = startedAtDevice == null ? null : Timestamp.from(startedAtDevice.toInstant());
    jdbc.update("update inspection set status='IN_PROGRESS',started_at_server=current_timestamp,started_at_device=?,updated_at=current_timestamp,version=version+1 where id=? and technician_id=? and status='ASSIGNED'",
        device, inspectionId, technicianId);
    return detail(inspectionId, technicianId);
  }

  @Transactional
  public EvidenceFile uploadEvidence(UUID inspectionId, UUID itemId, UUID technicianId, String fileName, String contentType, byte[] bytes) {
    AssignedInspection current = detail(inspectionId, technicianId);
    if (current.status() != Status.IN_PROGRESS) {
      throw new ApiException(HttpStatus.CONFLICT, "INSPECTION_NOT_IN_PROGRESS", "Evidence can be attached only after the inspection starts");
    }
    requireItem(inspectionId, itemId);
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_EVIDENCE_BYTES || !allowedImage(contentType)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "EVIDENCE_INVALID", "Evidence must be a JPEG, PNG or WebP image up to 5 MB");
    }
    UUID responseId = ensureResponse(inspectionId, itemId, technicianId);
    UUID evidenceId = UUID.randomUUID();
    String safeName = fileName == null || fileName.isBlank() || Path.of(fileName).getFileName().toString().isBlank()
        ? evidenceId + extension(contentType) : Path.of(fileName).getFileName().toString();
    String storageKey = inspectionId + "/" + evidenceId + extension(contentType);
    jdbc.update("insert into evidence (id,inspection_response_id,storage_key,file_name,content_type,size_bytes,created_by) values (?,?,?,?,?,?,?)",
        evidenceId, responseId, storageKey, safeName, contentType, bytes.length, technicianId);
    files.write(storageKey, bytes);
    return new EvidenceFile(evidenceId, responseId, itemId, safeName, contentType, bytes.length, Instant.now());
  }

  @Transactional
  public ChecklistItem saveObservation(UUID inspectionId, UUID itemId, UUID technicianId, String observation) {
    AssignedInspection current = detail(inspectionId, technicianId);
    if (current.status() != Status.IN_PROGRESS) {
      throw new ApiException(HttpStatus.CONFLICT, "INSPECTION_NOT_IN_PROGRESS", "Observations can be saved only after the inspection starts");
    }
    requireItem(inspectionId, itemId);
    upsertObservation(inspectionId, itemId, technicianId, observation);
    return checklist(inspectionId, technicianId).stream().flatMap(section -> section.items().stream()).filter(item -> item.id().equals(itemId)).findFirst().orElseThrow(InspectionExecutionService::notFound);
  }

  @Transactional
  public AssignedInspection complete(UUID inspectionId, UUID technicianId, CompleteCommand command) {
    AssignedInspection current = detail(inspectionId, technicianId);
    if (current.status() != Status.IN_PROGRESS) {
      throw new ApiException(HttpStatus.CONFLICT, "INSPECTION_NOT_IN_PROGRESS", "Only an inspection in progress can be completed");
    }
    if (current.version() != command.version()) {
      throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Inspection was modified by another request");
    }
    if (command.answers() != null) {
      for (AnswerInput answer : command.answers()) {
        requireItem(inspectionId, answer.itemId());
        upsertAnswer(inspectionId, answer.itemId(), technicianId, answer);
      }
    }
    List<String> pending = pendingRules(inspectionId);
    if (!pending.isEmpty()) {
      throw new ApiException(HttpStatus.CONFLICT, "INSPECTION_INCOMPLETE", String.join("; ", pending));
    }
    Timestamp device = command.completedAtDevice() == null ? null : Timestamp.from(command.completedAtDevice().toInstant());
    int updated = jdbc.update("update inspection set status='SUBMITTED',completed_at_device=?,submitted_at_server=current_timestamp,updated_at=current_timestamp,version=version+1 where id=? and version=? and status='IN_PROGRESS'",
        device, inspectionId, command.version());
    if (updated == 0) throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Inspection was modified by another request");
    return detail(inspectionId, technicianId);
  }

  private List<String> pendingRules(UUID inspectionId) {
    org.springframework.jdbc.core.RowMapper<RuleRow> rules = (rs, row) -> new RuleRow(rs.getString("item_title"), rs.getBoolean("required"), rs.getString("rules_json"), rs.getString("value_text"), rs.getBigDecimal("value_number"), (Boolean) rs.getObject("value_boolean"), rs.getDate("value_date"), rs.getString("conformity"), rs.getString("observation"), rs.getInt("evidence_count"));
    List<RuleRow> items = jdbc.query(
        "select s.item_title,s.required,s.rules_json,r.value_text,r.value_number,r.value_boolean,r.value_date,r.conformity,r.observation,(select count(*) from evidence e where e.inspection_response_id=r.id) as evidence_count from inspection_item_snapshot s left join inspection_response r on r.inspection_item_id=s.id where s.inspection_id=?",
        rules, inspectionId);
    List<String> pending = new ArrayList<>();
    for (RuleRow item : items) {
      boolean answered = item.answered();
      if (item.required() && !answered) pending.add(item.title() + " is required");
      boolean failed = "NON_CONFORMING".equals(item.conformity());
      if (failed && item.rules() != null && item.rules().contains("\"observationRequiredOnFailure\":true") && (item.observation() == null || item.observation().isBlank())) {
        pending.add(item.title() + " requires an observation");
      }
      if (failed && item.rules() != null && item.rules().contains("\"evidenceRequiredOnFailure\":true") && item.evidenceCount() == 0) {
        pending.add(item.title() + " requires evidence");
      }
    }
    return pending;
  }

  private UUID ensureResponse(UUID inspectionId, UUID itemId, UUID technicianId) {
    List<UUID> existing = jdbc.query("select id from inspection_response where inspection_item_id=?", (rs, row) -> UUID.fromString(rs.getString("id")), itemId);
    if (!existing.isEmpty()) return existing.getFirst();
    UUID responseId = UUID.randomUUID();
    jdbc.update("insert into inspection_response (id,inspection_id,inspection_item_id,answered_by,server_received_at) values (?,?,?,?,current_timestamp)",
        responseId, inspectionId, itemId, technicianId);
    return responseId;
  }

  private static boolean allowedImage(String contentType) {
    return "image/jpeg".equals(contentType) || "image/png".equals(contentType) || "image/webp".equals(contentType);
  }

  private static String extension(String contentType) {
    return switch (contentType) {
      case "image/png" -> ".png";
      case "image/webp" -> ".webp";
      default -> ".jpg";
    };
  }

  private void requireItem(UUID inspectionId, UUID itemId) {
    Integer count = jdbc.queryForObject("select count(*) from inspection_item_snapshot where id=? and inspection_id=?", Integer.class, itemId, inspectionId);
    if (count == null || count == 0) throw notFound();
  }

  private void upsertObservation(UUID inspectionId, UUID itemId, UUID technicianId, String observation) {
    upsert(inspectionId, itemId, technicianId, observation, null, null, null, null, null);
  }

  private void upsertAnswer(UUID inspectionId, UUID itemId, UUID technicianId, AnswerInput answer) {
    upsert(inspectionId, itemId, technicianId, answer.observation(), answer.valueText(), answer.valueNumber(), answer.valueBoolean(), answer.valueDate(), answer.conformity());
  }

  private void upsert(UUID inspectionId, UUID itemId, UUID technicianId, String observation, String valueText, BigDecimal valueNumber, Boolean valueBoolean, LocalDate valueDate, String conformity) {
    List<UUID> existing = jdbc.query("select id from inspection_response where inspection_item_id=?", (rs, row) -> UUID.fromString(rs.getString("id")), itemId);
    if (existing.isEmpty()) {
      jdbc.update("insert into inspection_response (id,inspection_id,inspection_item_id,value_text,value_number,value_boolean,value_date,observation,conformity,answered_by,server_received_at) values (?,?,?,?,?,?,?,?,?,?,current_timestamp)",
          UUID.randomUUID(), inspectionId, itemId, blankToNull(valueText), valueNumber, valueBoolean, valueDate == null ? null : Date.valueOf(valueDate), blankToNull(observation), conformity, technicianId);
      return;
    }
    jdbc.update("update inspection_response set value_text=coalesce(?,value_text),value_number=coalesce(?,value_number),value_boolean=coalesce(?,value_boolean),value_date=coalesce(?,value_date),observation=coalesce(?,observation),conformity=coalesce(?,conformity),answered_by=?,server_received_at=current_timestamp,updated_at=current_timestamp,version=version+1 where inspection_item_id=?",
        blankToNull(valueText), valueNumber, valueBoolean, valueDate == null ? null : Date.valueOf(valueDate), blankToNull(observation), conformity, technicianId, itemId);
  }

  private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

  private static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "INSPECTION_NOT_FOUND", "Inspection not found");
  }

  private static AssignedInspection mapAssigned(java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp started = rs.getTimestamp("started_at_server");
    Timestamp confirmed = rs.getTimestamp("equipment_confirmed_at");
    return new AssignedInspection(UUID.fromString(rs.getString("id")), rs.getString("client_name"), rs.getString("site_name"), rs.getString("equipment_name"), rs.getString("title"), rs.getString("instructions"), InspectionSchedulingService.Priority.valueOf(rs.getString("priority")), Status.valueOf(rs.getString("status")), rs.getTimestamp("scheduled_for").toInstant(), started == null ? null : started.toInstant(), rs.getInt("version"), confirmed == null ? null : confirmed.toInstant());
  }

  private static final String SELECT = "select i.id,i.title,i.instructions,i.priority,i.status,i.scheduled_for,i.started_at_server,i.version,i.equipment_confirmed_at,c.name as client_name,s.name as site_name,e.name as equipment_name from inspection i join client c on c.id=i.client_id join inspection_site s on s.id=i.site_id left join equipment e on e.id=i.equipment_id";

  public enum Status { ASSIGNED, IN_PROGRESS, SUBMITTED }

  public record AssignedInspection(UUID id, String clientName, String siteName, String equipmentName, String title, String instructions, InspectionSchedulingService.Priority priority, Status status, Instant scheduledFor, Instant startedAt, int version, Instant equipmentConfirmedAt) {}
  private record EquipmentMatch(UUID id, UUID siteId, String status) {}
  private record InspectionLink(UUID siteId, UUID equipmentId) {}
  public record ChecklistSection(String title, int order, List<ChecklistItem> items) {}
  public record ChecklistItem(UUID id, String sectionTitle, int sectionOrder, String title, String description, String responseType, boolean required, String rulesJson, String optionsJson, int itemOrder, String observation, String valueText, String conformity) {}
  public record AnswerInput(UUID itemId, String valueText, BigDecimal valueNumber, Boolean valueBoolean, LocalDate valueDate, String conformity, String observation) {}
  public record CompleteCommand(int version, OffsetDateTime completedAtDevice, List<AnswerInput> answers) {}
  public record EvidenceFile(UUID id, UUID inspectionResponseId, UUID inspectionItemId, String fileName, String contentType, long sizeBytes, Instant createdAt) {}
  private record RuleRow(String title, boolean required, String rules, String valueText, BigDecimal valueNumber, Boolean valueBoolean, Date valueDate, String conformity, String observation, int evidenceCount) {
    boolean answered() {
      return (valueText != null && !valueText.isBlank()) || valueNumber != null || valueBoolean != null || valueDate != null || (conformity != null && !conformity.isBlank());
    }
  }
}
