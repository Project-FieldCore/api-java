package com.fieldops.equipment.controller;

import com.fieldops.equipment.EquipmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
public class EquipmentController {
  private final EquipmentService equipment;

  public EquipmentController(EquipmentService equipment) { this.equipment = equipment; }

  @GetMapping("/equipment")
  public List<EquipmentService.Equipment> list() { return equipment.list(); }

  @GetMapping("/sites/{siteId}/equipment")
  public List<EquipmentService.Equipment> listBySite(@PathVariable UUID siteId) { return equipment.listBySite(siteId); }

  @PostMapping("/sites/{siteId}/equipment")
  public ResponseEntity<EquipmentService.Equipment> create(@PathVariable UUID siteId, @Valid @RequestBody EquipmentRequest body) {
    EquipmentService.Equipment created = equipment.create(siteId, body.createCommand());
    return ResponseEntity.created(URI.create("/api/v1/equipment/" + created.id())).body(created);
  }

  @GetMapping("/equipment/{equipmentId}")
  public EquipmentService.Equipment get(@PathVariable UUID equipmentId) { return equipment.get(equipmentId); }

  @PatchMapping("/equipment/{equipmentId}")
  public EquipmentService.Equipment update(@PathVariable UUID equipmentId, @Valid @RequestBody EquipmentUpdateRequest body) {
    return equipment.update(equipmentId, body.command());
  }

  @PutMapping("/equipment/{equipmentId}")
  public EquipmentService.Equipment replace(@PathVariable UUID equipmentId, @Valid @RequestBody EquipmentUpdateRequest body) {
    return equipment.update(equipmentId, body.command());
  }

  @PatchMapping("/equipment/{equipmentId}/status")
  public EquipmentService.Equipment changeStatus(@PathVariable UUID equipmentId, @Valid @RequestBody StatusChangeRequest body) {
    return equipment.changeStatus(equipmentId, new EquipmentService.StatusCommand(body.status(), body.version()));
  }

  @DeleteMapping("/equipment/{equipmentId}")
  public ResponseEntity<Void> delete(@PathVariable UUID equipmentId, @Valid @RequestBody DeleteRequest body) {
    equipment.changeStatus(equipmentId, new EquipmentService.StatusCommand(EquipmentService.Status.INACTIVE, body.version()));
    return ResponseEntity.noContent().build();
  }

  public record EquipmentRequest(@NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 255) String qrCode,
                                 @Size(max = 100) String assetNumber, @Size(max = 100) String serialNumber,
                                 @Size(max = 160) String manufacturer, @Size(max = 160) String model,
                                 @Size(max = 1000) String description, @PastOrPresent LocalDate installedAt) {
    EquipmentService.CreateCommand createCommand() { return new EquipmentService.CreateCommand(name, qrCode, assetNumber, serialNumber, manufacturer, model, description, installedAt); }
  }
  public record EquipmentUpdateRequest(@Size(max = 160) String name, @Size(max = 255) String qrCode,
                                       @Size(max = 100) String assetNumber, @Size(max = 100) String serialNumber,
                                       @Size(max = 160) String manufacturer, @Size(max = 160) String model,
                                       @Size(max = 1000) String description, @PastOrPresent LocalDate installedAt,
                                       @NotNull @Min(0) Integer version) {
    EquipmentService.UpdateCommand command() { return new EquipmentService.UpdateCommand(name, qrCode, assetNumber, serialNumber, manufacturer, model, description, installedAt, version); }
  }
  public record StatusChangeRequest(@NotNull EquipmentService.Status status, @NotNull @Min(0) Integer version) {}
  public record DeleteRequest(@NotNull @Min(0) Integer version) {}
}
