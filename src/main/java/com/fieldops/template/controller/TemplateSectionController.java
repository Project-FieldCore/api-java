package com.fieldops.template.controller;

import com.fieldops.template.TemplateSectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/template-versions/{templateVersionId}/sections")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
public class TemplateSectionController {
  private final TemplateSectionService sections;
  public TemplateSectionController(TemplateSectionService sections) { this.sections = sections; }
  @GetMapping public List<TemplateSectionService.Section> list(@PathVariable UUID templateVersionId) { return sections.list(templateVersionId); }
  @PostMapping public ResponseEntity<TemplateSectionService.Section> create(@PathVariable UUID templateVersionId, @Valid @RequestBody CreateSectionRequest body) {
    TemplateSectionService.Section section = sections.create(templateVersionId, new TemplateSectionService.CreateCommand(body.title(), body.description(), body.displayOrder()));
    return ResponseEntity.created(URI.create("/api/v1/template-versions/" + templateVersionId + "/sections/" + section.id())).body(section);
  }
  @PatchMapping("/order") public List<TemplateSectionService.Section> reorder(@PathVariable UUID templateVersionId, @Valid @RequestBody ReorderSectionsRequest body) {
    return sections.reorder(templateVersionId, body.sections().stream().map(value -> new TemplateSectionService.OrderCommand(value.sectionId(), value.displayOrder())).toList());
  }
  public record CreateSectionRequest(@NotBlank @Size(max = 255) String title, @Size(max = 1000) String description, @NotNull @Min(0) Integer displayOrder) {}
  public record ReorderSectionsRequest(@NotEmpty List<@Valid SectionOrderRequest> sections) {}
  public record SectionOrderRequest(@NotNull UUID sectionId, @NotNull @Min(0) Integer displayOrder) {}
}
