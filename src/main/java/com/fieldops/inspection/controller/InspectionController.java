package com.fieldops.inspection.controller;

import com.fieldops.inspection.InspectionSchedulingService;
import com.fieldops.shared.security.FieldOpsUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inspections")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
public class InspectionController {
  private final InspectionSchedulingService inspections;
  public InspectionController(InspectionSchedulingService inspections) { this.inspections = inspections; }

  @PostMapping
  public ResponseEntity<InspectionSchedulingService.Inspection> schedule(
      @Valid @RequestBody ScheduleInspectionRequest body, @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    InspectionSchedulingService.Inspection inspection = inspections.schedule(new InspectionSchedulingService.ScheduleCommand(
        body.templateVersionId(), body.clientId(), body.siteId(), body.technicianId(), body.scheduledFor(), body.title(),
        body.instructions(), body.priority() == null ? InspectionSchedulingService.Priority.MEDIUM : body.priority()), principal.userId());
    return ResponseEntity.status(201).body(inspection);
  }

  public record ScheduleInspectionRequest(@NotNull UUID templateVersionId, @NotNull UUID clientId, @NotNull UUID siteId,
                                          @NotNull UUID technicianId, @NotNull @Future OffsetDateTime scheduledFor,
                                          @Size(max = 255) String title, @Size(max = 2000) String instructions,
                                          InspectionSchedulingService.Priority priority) {}
}
