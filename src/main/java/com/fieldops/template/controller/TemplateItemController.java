package com.fieldops.template.controller;

import com.fieldops.template.TemplateItemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/template-sections/{sectionId}/items")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
public class TemplateItemController {
  private final TemplateItemService items;

  public TemplateItemController(TemplateItemService items) { this.items = items; }

  @PostMapping
  public ResponseEntity<TemplateItemService.Item> create(
      @PathVariable UUID sectionId, @Valid @RequestBody CreateItemRequest body) {
    TemplateItemService.Item item = items.create(sectionId, new TemplateItemService.CreateCommand(body.code(), body.title(),
        body.description(), body.responseType(), Boolean.TRUE.equals(body.required()),
        Boolean.TRUE.equals(body.observationRequiredOnFailure()), Boolean.TRUE.equals(body.evidenceRequiredOnFailure()),
        body.displayOrder(), body.options()));
    return ResponseEntity.status(201).body(item);
  }

  @GetMapping
  public List<TemplateItemService.Item> list(@PathVariable UUID sectionId) { return items.listBySection(sectionId); }

  public record CreateItemRequest(@Size(max = 100) String code, @NotBlank @Size(max = 500) String title,
                                  @Size(max = 1000) String description, @NotNull TemplateItemService.ResponseType responseType,
                                  Boolean required, Boolean observationRequiredOnFailure, Boolean evidenceRequiredOnFailure,
                                  @NotNull @Min(0) Integer displayOrder, List<@NotBlank @Size(max = 255) String> options) {}
}
