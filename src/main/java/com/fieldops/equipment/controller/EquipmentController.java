package com.fieldops.equipment.controller;

import com.fieldops.equipment.EquipmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/equipment")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
public class EquipmentController {
  private final EquipmentService equipment;

  public EquipmentController(EquipmentService equipment) { this.equipment = equipment; }

  @GetMapping("/by-qr/{qrCode}")
  public EquipmentService.Equipment getByQrCode(@PathVariable String qrCode) {
    return equipment.getByQrCode(qrCode);
  }

  @PatchMapping("/{equipmentId}/qr-code")
  public EquipmentService.Equipment assignQrCode(
      @PathVariable UUID equipmentId, @Valid @RequestBody QrCodeRequest body) {
    return equipment.assignQrCode(equipmentId, new EquipmentService.QrCodeCommand(body.qrCode(), body.version()));
  }

  public record QrCodeRequest(@NotBlank @Size(max = 255) String qrCode, @NotNull @Min(0) Integer version) {}
}
