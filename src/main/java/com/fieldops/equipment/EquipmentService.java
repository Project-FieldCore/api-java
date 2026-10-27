package com.fieldops.equipment;

import com.fieldops.shared.error.ApiException;
import com.fieldops.shared.pagination.PageQuery;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EquipmentService {
  private final JdbcTemplate jdbc;

  public EquipmentService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public Page list(int page, int size, String sort, String query, Status status, UUID siteId) {
    String where = " where (? is null or lower(name) like lower(?) or lower(qr_code) like lower(?) or lower(asset_number) like lower(?) or lower(serial_number) like lower(?)) and (? is null or status=?) and (? is null or site_id=?)";
    String match = query == null || query.isBlank() ? null : "%" + query.trim() + "%";
    Object[] filter = {match, match, match, match, match, status == null ? null : status.name(), status == null ? null : status.name(), siteId, siteId};
    long total = jdbc.queryForObject("select count(*) from equipment" + where, Long.class, filter);
    List<Equipment> items = jdbc.query("select * from equipment" + where + " order by " + PageQuery.orderBy(sort, java.util.Map.of("name", "name", "qrCode", "qr_code", "createdAt", "created_at")) + " limit ? offset ?", (rs, row) -> map(rs), concat(filter, size, page * size));
    return new Page(items, new PageMeta(page, size, total, (int) Math.ceil((double) total / size)));
  }

  private static Object[] concat(Object[] values, Object... tail) { Object[] result = java.util.Arrays.copyOf(values, values.length + tail.length); System.arraycopy(tail, 0, result, values.length, tail.length); return result; }

  public List<Equipment> listBySite(UUID siteId) {
    requireSite(siteId);
    return jdbc.query("select * from equipment where site_id=? order by name,id", (rs, row) -> map(rs), siteId);
  }

  public Equipment get(UUID id) {
    List<Equipment> equipment = jdbc.query("select * from equipment where id=?", (rs, row) -> map(rs), id);
    if (equipment.isEmpty()) throw equipmentNotFound();
    return equipment.getFirst();
  }

  @Transactional
  public Equipment create(UUID siteId, CreateCommand command) {
    requireSite(siteId);
    UUID id = UUID.randomUUID();
    try {
      jdbc.update("insert into equipment (id,site_id,name,qr_code,asset_number,serial_number,manufacturer,model,description,status,installed_at) values (?,?,?,?,?,?,?,?,?,?,?)",
          id, siteId, command.name(), command.qrCode(), command.assetNumber(), command.serialNumber(), command.manufacturer(),
          command.model(), command.description(), "ACTIVE", date(command.installedAt()));
    } catch (org.springframework.dao.DataIntegrityViolationException ex) {
      throw conflict(ex);
    }
    return get(id);
  }

  @Transactional
  public Equipment update(UUID id, UpdateCommand command) {
    Equipment current = get(id);
    try {
      int updated = jdbc.update("update equipment set name=?,qr_code=?,asset_number=?,serial_number=?,manufacturer=?,model=?,description=?,installed_at=?,updated_at=current_timestamp,version=version+1 where id=? and version=?",
          value(command.name(), current.name()), value(command.qrCode(), current.qrCode()), value(command.assetNumber(), current.assetNumber()),
          value(command.serialNumber(), current.serialNumber()), value(command.manufacturer(), current.manufacturer()),
          value(command.model(), current.model()), value(command.description(), current.description()),
          date(value(command.installedAt(), current.installedAt())), id, command.version());
      if (updated == 0) throw versionConflict();
    } catch (org.springframework.dao.DataIntegrityViolationException ex) {
      throw conflict(ex);
    }
    return get(id);
  }

  @Transactional
  public Equipment changeStatus(UUID id, StatusCommand command) {
    get(id);
    int updated = jdbc.update("update equipment set status=?,updated_at=current_timestamp,version=version+1 where id=? and version=?",
        command.status().name(), id, command.version());
    if (updated == 0) throw versionConflict();
    return get(id);
  }

  private void requireSite(UUID siteId) {
    Boolean exists = jdbc.queryForObject("select exists(select 1 from inspection_site where id=?)", Boolean.class, siteId);
    if (!Boolean.TRUE.equals(exists)) throw siteNotFound();
  }

  private static <T> T value(T update, T current) { return update == null ? current : update; }
  private static Date date(LocalDate value) { return value == null ? null : Date.valueOf(value); }
  private static Equipment map(ResultSet rs) throws SQLException {
    Date installedAt = rs.getDate("installed_at");
    return new Equipment(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("site_id")), rs.getString("name"),
        rs.getString("qr_code"), rs.getString("asset_number"), rs.getString("serial_number"), rs.getString("manufacturer"),
        rs.getString("model"), rs.getString("description"), Status.valueOf(rs.getString("status")),
        installedAt == null ? null : installedAt.toLocalDate(), instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")), rs.getInt("version"));
  }

  private static Instant instant(Timestamp value) { return value.toInstant(); }
  private static ApiException siteNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "SITE_NOT_FOUND", "Site not found"); }
  private static ApiException equipmentNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "EQUIPMENT_NOT_FOUND", "Equipment not found"); }
  private static ApiException qrCodeConflict() { return new ApiException(HttpStatus.CONFLICT, "QR_CODE_ALREADY_EXISTS", "QR code is already in use"); }
  private static ApiException serialConflict() { return new ApiException(HttpStatus.CONFLICT, "SERIAL_NUMBER_ALREADY_EXISTS", "Serial number is already in use at this site"); }
  private static ApiException versionConflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Equipment was modified by another request"); }
  private static ApiException conflict(org.springframework.dao.DataIntegrityViolationException ex) {
    String message = ex.getMostSpecificCause().getMessage();
    return message != null && message.toLowerCase().contains("uk_equipment_qr_code") ? qrCodeConflict() : serialConflict();
  }

  public enum Status { ACTIVE, INACTIVE, DECOMMISSIONED }
  public record CreateCommand(String name, String qrCode, String assetNumber, String serialNumber, String manufacturer,
                              String model, String description, LocalDate installedAt) {}
  public record UpdateCommand(String name, String qrCode, String assetNumber, String serialNumber, String manufacturer,
                              String model, String description, LocalDate installedAt, int version) {}
  public record StatusCommand(Status status, int version) {}
  public record Equipment(UUID id, UUID siteId, String name, String qrCode, String assetNumber, String serialNumber,
                          String manufacturer, String model, String description, Status status, LocalDate installedAt,
                          Instant createdAt, Instant updatedAt, int version) {}
  public record Page(List<Equipment> items, PageMeta page) {}
  public record PageMeta(int page, int size, long totalElements, int totalPages) {}
}
