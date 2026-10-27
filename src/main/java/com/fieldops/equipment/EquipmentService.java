package com.fieldops.equipment;

import com.fieldops.shared.error.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EquipmentService {
  private final JdbcTemplate jdbc;

  public EquipmentService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public Equipment getByQrCode(String qrCode) {
    List<Equipment> equipment = jdbc.query(
        "select * from equipment where qr_code=?", (rs, row) -> map(rs), qrCode);
    if (equipment.isEmpty()) throw notFound();
    return equipment.getFirst();
  }

  @Transactional
  public Equipment assignQrCode(UUID id, QrCodeCommand command) {
    get(id);
    int updated;
    try {
      updated = jdbc.update(
          "update equipment set qr_code=?,updated_at=current_timestamp,version=version+1 where id=? and version=?",
          command.qrCode(), id, command.version());
    } catch (DataIntegrityViolationException ex) {
      throw qrCodeConflict();
    }
    if (updated == 0) throw versionConflict();
    return get(id);
  }

  private Equipment get(UUID id) {
    List<Equipment> equipment = jdbc.query("select * from equipment where id=?", (rs, row) -> map(rs), id);
    if (equipment.isEmpty()) throw notFound();
    return equipment.getFirst();
  }

  private static Equipment map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Equipment(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("site_id")),
        rs.getString("name"), rs.getString("asset_number"), rs.getString("serial_number"), rs.getString("qr_code"),
        Status.valueOf(rs.getString("status")), instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")), rs.getInt("version"));
  }

  private static Instant instant(Timestamp value) { return value.toInstant(); }
  private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "EQUIPMENT_NOT_FOUND", "Equipment not found"); }
  private static ApiException qrCodeConflict() { return new ApiException(HttpStatus.CONFLICT, "QR_CODE_ALREADY_EXISTS", "QR code is already assigned to another equipment"); }
  private static ApiException versionConflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Equipment was modified by another request"); }

  public enum Status { ACTIVE, INACTIVE, DECOMMISSIONED }
  public record QrCodeCommand(String qrCode, int version) {}
  public record Equipment(UUID id, UUID siteId, String name, String assetNumber, String serialNumber, String qrCode,
                          Status status, Instant createdAt, Instant updatedAt, int version) {}
}
