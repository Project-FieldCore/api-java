package com.fieldops.template.controller;

import com.fieldops.shared.security.FieldOpsUserPrincipal;
import com.fieldops.template.InspectionTemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inspection-templates")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
public class InspectionTemplateController {
  private final InspectionTemplateService templates;

  public InspectionTemplateController(InspectionTemplateService templates) { this.templates = templates; }

  @PostMapping
  public ResponseEntity<InspectionTemplateService.Template> create(
      @Valid @RequestBody CreateTemplateRequest body, Authentication authentication) {
    UUID createdBy = authentication.getPrincipal() instanceof FieldOpsUserPrincipal principal ? principal.userId() : null;
    InspectionTemplateService.Template template = templates.create(
        new InspectionTemplateService.CreateCommand(body.title(), body.description(), body.category()), createdBy);
    return ResponseEntity.created(URI.create("/api/v1/inspection-templates/" + template.id())).body(template);
  }

  @GetMapping("/{templateId}")
  public InspectionTemplateService.Template get(@PathVariable UUID templateId) { return templates.get(templateId); }

  @PatchMapping("/{templateId}")
  public InspectionTemplateService.Template updateDraft(
      @PathVariable UUID templateId, @Valid @RequestBody UpdateTemplateRequest body) {
    return templates.updateDraft(templateId,
        new InspectionTemplateService.UpdateCommand(body.title(), body.description(), body.category(), body.version()));
  }

  @PutMapping("/{templateId}")
  public InspectionTemplateService.Template replaceDraft(
      @PathVariable UUID templateId, @Valid @RequestBody UpdateTemplateRequest body) {
    return templates.updateDraft(templateId,
        new InspectionTemplateService.UpdateCommand(body.title(), body.description(), body.category(), body.version()));
  }

  public record CreateTemplateRequest(@NotBlank @Size(max = 160) String title,
                                      @Size(max = 1000) String description,
                                      @NotBlank @Size(max = 100) String category) {}
  public record UpdateTemplateRequest(@Size(max = 160) String title, @Size(max = 1000) String description,
                                      @Size(max = 100) String category, @NotNull @Min(0) Integer version) {}
}
