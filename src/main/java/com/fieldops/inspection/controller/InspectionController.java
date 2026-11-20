package com.fieldops.inspection.controller;

import com.fieldops.inspection.InspectionExecutionService;
import com.fieldops.inspection.InspectionSchedulingService;
import com.fieldops.shared.security.FieldOpsUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inspections")
public class InspectionController {
  private final InspectionSchedulingService inspections;
  private final InspectionExecutionService execution;
  public InspectionController(InspectionSchedulingService inspections, InspectionExecutionService execution) {
    this.inspections = inspections;
    this.execution = execution;
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  public ResponseEntity<InspectionSchedulingService.Inspection> schedule(
      @Valid @RequestBody ScheduleInspectionRequest body, @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    InspectionSchedulingService.Inspection inspection = inspections.schedule(new InspectionSchedulingService.ScheduleCommand(
        body.templateVersionId(), body.clientId(), body.siteId(), body.technicianId(), body.scheduledFor(), body.title(),
        body.instructions(), body.priority() == null ? InspectionSchedulingService.Priority.MEDIUM : body.priority()), principal.userId());
    return ResponseEntity.status(201).body(inspection);
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  public InspectionSchedulingService.Page list(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @jakarta.validation.constraints.Max(100) int size,
      @RequestParam(defaultValue = "scheduledFor,asc") String sort,
      @RequestParam(required = false) InspectionSchedulingService.Status status,
      @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) OffsetDateTime scheduledFrom,
      @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) OffsetDateTime scheduledTo,
      @RequestParam(required = false) UUID technicianId,
      @RequestParam(required = false) UUID clientId) {
    return inspections.list(page, size, sort, status, scheduledFrom, scheduledTo, technicianId, clientId);
  }

  @GetMapping("/review-queue")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  public InspectionSchedulingService.ReviewPage reviewQueue(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @jakarta.validation.constraints.Max(100) int size,
      @RequestParam(defaultValue = "submittedAt,asc") String sort,
      @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) OffsetDateTime submittedFrom,
      @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) OffsetDateTime submittedTo,
      @RequestParam(required = false) UUID technicianId,
      @RequestParam(required = false) UUID clientId) {
    return inspections.reviewQueue(page, size, sort, submittedFrom, submittedTo, technicianId, clientId);
  }

  @GetMapping("/{inspectionId}/monitoring")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  public InspectionSchedulingService.Monitoring monitoring(@PathVariable UUID inspectionId) {
    return inspections.monitoring(inspectionId);
  }

  @GetMapping("/{inspectionId}/checklist")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  public List<InspectionExecutionService.ChecklistSection> reviewChecklist(@PathVariable UUID inspectionId) {
    return execution.checklistForReview(inspectionId);
  }

  @GetMapping("/{inspectionId}/evidence/{evidenceId}/content")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  public ResponseEntity<byte[]> evidenceContent(@PathVariable UUID inspectionId, @PathVariable UUID evidenceId) {
    InspectionSchedulingService.EvidenceContent content = inspections.evidenceContent(inspectionId, evidenceId);
    return ResponseEntity.ok()
        .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, content.contentType())
        .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private")
        .body(content.bytes());
  }

  @GetMapping("/{inspectionId}/audit")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  public List<com.fieldops.shared.audit.AuditService.AuditEventView> auditTrail(@PathVariable UUID inspectionId) {
    return inspections.auditTrail(inspectionId);
  }

  @PatchMapping("/{inspectionId}/assignee")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  public InspectionSchedulingService.Inspection assign(
      @PathVariable UUID inspectionId, @Valid @RequestBody AssignTechnicianRequest body,
      @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return inspections.assign(inspectionId, new InspectionSchedulingService.AssignmentCommand(body.technicianId(), body.version()), principal.userId());
  }

  @PostMapping("/{inspectionId}/review/start")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  public InspectionSchedulingService.Inspection startReview(
      @PathVariable UUID inspectionId, @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return inspections.startReview(inspectionId, principal.userId());
  }

  @PostMapping("/{inspectionId}/review/approve")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  public InspectionSchedulingService.Inspection approveReview(
      @PathVariable UUID inspectionId,
      @RequestBody(required = false) @Valid ApproveReviewRequest body,
      @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return inspections.approveReview(inspectionId, principal.userId(), body == null ? null : body.comentario());
  }

  @PostMapping("/{inspectionId}/review/reject")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  public InspectionSchedulingService.Inspection rejectReview(
      @PathVariable UUID inspectionId,
      @Valid @RequestBody RejectReviewRequest body,
      @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return inspections.rejectReview(inspectionId, principal.userId(), body.motivo());
  }

  @PatchMapping("/{inspectionId}/cancel")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  public InspectionSchedulingService.Inspection cancel(
      @PathVariable UUID inspectionId, @Valid @RequestBody CancelInspectionRequest body,
      @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return inspections.cancel(inspectionId, new InspectionSchedulingService.CancellationCommand(body.reason(), body.version()), principal.userId());
  }

  @GetMapping("/my")
  @PreAuthorize("hasRole('TECHNICIAN')")
  public List<InspectionExecutionService.AssignedInspection> myAssignments(
      @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return execution.assignedTo(principal.userId());
  }

  @GetMapping("/my/{inspectionId}")
  @PreAuthorize("hasRole('TECHNICIAN')")
  public InspectionExecutionService.AssignedInspection myInspection(
      @PathVariable UUID inspectionId, @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return execution.detail(inspectionId, principal.userId());
  }

  @GetMapping("/my/{inspectionId}/checklist")
  @PreAuthorize("hasRole('TECHNICIAN')")
  public List<InspectionExecutionService.ChecklistSection> checklist(
      @PathVariable UUID inspectionId, @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return execution.checklist(inspectionId, principal.userId());
  }

  @PostMapping("/my/{inspectionId}/confirm-equipment")
  @PreAuthorize("hasRole('TECHNICIAN')")
  public InspectionExecutionService.AssignedInspection confirmEquipment(
      @PathVariable UUID inspectionId, @Valid @RequestBody ConfirmEquipmentRequest body,
      @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return execution.confirmEquipment(inspectionId, principal.userId(), body.qrCode());
  }

  @PostMapping("/my/{inspectionId}/start")
  @PreAuthorize("hasRole('TECHNICIAN')")
  public InspectionExecutionService.AssignedInspection start(
      @PathVariable UUID inspectionId, @RequestBody(required = false) StartInspectionRequest body,
      @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return execution.start(inspectionId, principal.userId(), body == null ? null : body.startedAtDevice(), body == null ? null : body.latitude(), body == null ? null : body.longitude());
  }

  @PostMapping(value = "/my/{inspectionId}/items/{itemId}/evidence", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('TECHNICIAN')")
  public InspectionExecutionService.EvidenceFile uploadEvidence(
      @PathVariable UUID inspectionId, @PathVariable UUID itemId,
      @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file,
      @AuthenticationPrincipal FieldOpsUserPrincipal principal) throws java.io.IOException {
    return execution.uploadEvidence(inspectionId, itemId, principal.userId(), file.getOriginalFilename(), file.getContentType(), file.getBytes());
  }

  @PutMapping("/my/{inspectionId}/items/{itemId}/observation")
  @PreAuthorize("hasRole('TECHNICIAN')")
  public InspectionExecutionService.ChecklistItem saveObservation(
      @PathVariable UUID inspectionId, @PathVariable UUID itemId, @Valid @RequestBody ObservationRequest body,
      @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return execution.saveObservation(inspectionId, itemId, principal.userId(), body.observation());
  }

  @GetMapping("/my/{inspectionId}/non-conformities")
  @PreAuthorize("hasRole('TECHNICIAN')")
  public List<InspectionExecutionService.NonConformityRecord> nonConformities(
      @PathVariable UUID inspectionId, @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return execution.nonConformities(inspectionId, principal.userId());
  }

  @PostMapping("/my/{inspectionId}/non-conformities")
  @PreAuthorize("hasRole('TECHNICIAN')")
  public ResponseEntity<InspectionExecutionService.NonConformityRecord> registerNonConformity(
      @PathVariable UUID inspectionId, @Valid @RequestBody NonConformityRequest body,
      @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return ResponseEntity.status(201).body(execution.registerNonConformity(inspectionId, body.itemId(), principal.userId(), body.severity(), body.description()));
  }

  @PostMapping("/my/{inspectionId}/complete")
  @PreAuthorize("hasRole('TECHNICIAN')")
  public InspectionExecutionService.AssignedInspection complete(
      @PathVariable UUID inspectionId, @Valid @RequestBody CompleteInspectionRequest body,
      @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    List<InspectionExecutionService.AnswerInput> answers = body.answers() == null ? List.of() : body.answers().stream()
        .map(answer -> new InspectionExecutionService.AnswerInput(answer.itemId(), answer.valueText(), answer.valueNumber(), answer.valueBoolean(), answer.valueDate(), answer.conformity(), answer.observation()))
        .toList();
    InspectionExecutionService.SignatureInput signature = body.signature() == null ? null
        : new InspectionExecutionService.SignatureInput(body.signature().contentType(), body.signature().dataBase64());
    return execution.complete(inspectionId, principal.userId(), new InspectionExecutionService.CompleteCommand(body.version(), body.completedAtDevice(), body.latitude(), body.longitude(), answers, signature));
  }

  public record ScheduleInspectionRequest(@NotNull UUID templateVersionId, @NotNull UUID clientId, @NotNull UUID siteId,
                                          @NotNull UUID technicianId, @NotNull @Future OffsetDateTime scheduledFor,
                                          @Size(max = 255) String title, @Size(max = 2000) String instructions,
                                          InspectionSchedulingService.Priority priority) {}
  public record AssignTechnicianRequest(@NotNull UUID technicianId, @NotNull @jakarta.validation.constraints.Min(0) Integer version) {}
  public record CancelInspectionRequest(@NotBlank @Size(max = 1000) String reason, @NotNull @Min(0) Integer version) {}
  public record ApproveReviewRequest(@Size(max = 2000) String comentario) {}
  public record RejectReviewRequest(@NotBlank @Size(max = 2000) String motivo) {}
  public record ConfirmEquipmentRequest(@NotBlank @Size(max = 255) String qrCode) {}
  public record StartInspectionRequest(OffsetDateTime startedAtDevice, @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude, @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude) {}
  public record ObservationRequest(@Size(max = 2000) String observation) {}
  public record CompleteInspectionRequest(@NotNull @Min(0) Integer version, OffsetDateTime completedAtDevice, @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude, @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude, List<AnswerRequest> answers, @Valid SignatureRequest signature) {}
  public record SignatureRequest(@NotBlank @Size(max = 127) String contentType, @NotBlank @Size(max = 1400000) String dataBase64) {}
  public record NonConformityRequest(@NotNull UUID itemId, @NotBlank @Size(max = 32) String severity, @NotBlank @Size(max = 2000) String description) {}
  public record AnswerRequest(@NotNull UUID itemId, @Size(max = 4000) String valueText, BigDecimal valueNumber, Boolean valueBoolean, LocalDate valueDate, @Size(max = 32) String conformity, @Size(max = 2000) String observation) {}
}
