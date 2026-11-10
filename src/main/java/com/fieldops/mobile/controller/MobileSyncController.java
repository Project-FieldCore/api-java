package com.fieldops.mobile.controller;

import com.fieldops.mobile.MobileSyncService;
import com.fieldops.shared.security.FieldOpsUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mobile/sync")
public class MobileSyncController {
  private final MobileSyncService sync;

  public MobileSyncController(MobileSyncService sync) { this.sync = sync; }

  @GetMapping("/pull")
  @PreAuthorize("hasRole('TECHNICIAN')")
  public MobileSyncService.PullResult pull(
      @RequestParam(required = false) String cursor, @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return sync.pull(principal.userId(), cursor);
  }

  @PostMapping("/push")
  @PreAuthorize("hasRole('TECHNICIAN')")
  public MobileSyncService.PushResult push(
      @Valid @RequestBody PushRequest body, @AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    List<MobileSyncService.Operation> operations = body.operations().stream()
        .map(operation -> new MobileSyncService.Operation(
            operation.operationId(), operation.entityType(), operation.entityId(), operation.operationType(),
            operation.dependencyIds(), operation.baseVersion(), toPayload(operation.payload())))
        .toList();
    return sync.push(principal.userId(), new MobileSyncService.PushCommand(body.deviceId(), operations));
  }

  private static MobileSyncService.AnswerPayload toPayload(AnswerPayloadRequest payload) {
    if (payload == null) return null;
    return new MobileSyncService.AnswerPayload(payload.inspectionItemId(), payload.valueText(), payload.valueNumber(),
        payload.valueBoolean(), payload.valueDate(), payload.conformity(), payload.observation());
  }

  public record PushRequest(@NotNull UUID deviceId, String lastPullCursor, @NotNull @Size(max = 200) List<@Valid @NotNull OperationRequest> operations) {}
  public record OperationRequest(@NotNull UUID operationId, @NotBlank @Size(max = 64) String entityType, @NotNull UUID entityId,
                                 @NotBlank @Size(max = 32) String operationType, List<UUID> dependencyIds, Integer baseVersion,
                                 AnswerPayloadRequest payload) {}
  public record AnswerPayloadRequest(UUID inspectionItemId, @Size(max = 4000) String valueText, BigDecimal valueNumber,
                                     Boolean valueBoolean, LocalDate valueDate, @Size(max = 32) String conformity,
                                     @Size(max = 2000) String observation) {}
}
