package com.fieldops.inspection.controller;

import com.fieldops.inspection.InspectionSchedulingService;
import com.fieldops.shared.security.FieldOpsUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inspections")
public class InspectionController {
  private final InspectionSchedulingService inspections;
  public InspectionController(InspectionSchedulingService inspections) { this.inspections = inspections; }

  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  public ResponseEntity<InspectionSchedulingService.Inspection> schedule(
      @Valid @RequestBody ScheduleInspectionRequest body, @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    InspectionSchedulingService.Inspection inspection = inspections.schedule(new InspectionSchedulingService.ScheduleCommand(
        body.templateVersionId(), body.clientId(), body.siteId(), body.technicianId(), body.scheduledFor(), body.title(),
        body.instructions(), body.priority() == null ? InspectionSchedulingService.Priority.MEDIUM : body.priority()), principal.userId());
    return ResponseEntity.status(201).body(inspection);
  }

  @PatchMapping("/{inspectionId}/assignee")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  public InspectionSchedulingService.Inspection assign(
      @PathVariable UUID inspectionId, @Valid @RequestBody AssignTechnicianRequest body) {
    return inspections.assign(inspectionId, new InspectionSchedulingService.AssignmentCommand(body.technicianId(), body.version()));
  }

  @GetMapping("/my")
  @PreAuthorize("hasRole('TECHNICIAN')")
  public List<InspectionSchedulingService.Inspection> myAssignments(
      @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return inspections.assignedTo(principal.userId());
  }

  public record ScheduleInspectionRequest(@NotNull UUID templateVersionId, @NotNull UUID clientId, @NotNull UUID siteId,
                                          @NotNull UUID technicianId, @NotNull @Future OffsetDateTime scheduledFor,
                                          @Size(max = 255) String title, @Size(max = 2000) String instructions,
                                          InspectionSchedulingService.Priority priority) {}
  public record AssignTechnicianRequest(@NotNull UUID technicianId, @NotNull @jakarta.validation.constraints.Min(0) Integer version) {}
}
