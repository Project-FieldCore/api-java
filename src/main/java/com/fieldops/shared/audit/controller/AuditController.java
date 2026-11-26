package com.fieldops.shared.audit.controller;

import com.fieldops.shared.audit.AuditService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {
  private final AuditService audit;

  public AuditController(AuditService audit) { this.audit = audit; }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public AuditService.AuditPage list(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
    return audit.list(page, size, sort, q, entityType, action, actorId, from, to);
  }
}
