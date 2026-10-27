package com.fieldops.site;

import com.fieldops.shared.error.ApiException;
import com.fieldops.shared.pagination.PageQuery;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SiteService {
  private final JdbcTemplate jdbc;

  public SiteService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public List<Site> listByClient(UUID clientId) {
    requireClient(clientId);
    return jdbc.query("select * from inspection_site where client_id=? order by name,id", (rs, row) -> map(rs), clientId);
  }

  public Page list(int page, int size, String sort, String query, Status status, UUID clientId) {
    String where = " where (? is null or lower(name) like lower(?) or lower(city) like lower(?) or lower(state) like lower(?)) and (? is null or status=?) and (? is null or client_id=?)";
    String match = query == null || query.isBlank() ? null : "%" + query.trim() + "%";
    Object[] filter = {match, match, match, match, status == null ? null : status.name(), status == null ? null : status.name(), clientId, clientId};
    long total = jdbc.queryForObject("select count(*) from inspection_site" + where, Long.class, filter);
    List<Site> items = jdbc.query("select * from inspection_site" + where + " order by " + PageQuery.orderBy(sort, java.util.Map.of("name", "name", "city", "city", "createdAt", "created_at")) + " limit ? offset ?", (rs, row) -> map(rs), concat(filter, size, page * size));
    return new Page(items, new PageMeta(page, size, total, (int) Math.ceil((double) total / size)));
  }

  private static Object[] concat(Object[] values, Object... tail) { Object[] result = java.util.Arrays.copyOf(values, values.length + tail.length); System.arraycopy(tail, 0, result, values.length, tail.length); return result; }

  public Site get(UUID id) {
    List<Site> sites = jdbc.query("select * from inspection_site where id=?", (rs, row) -> map(rs), id);
    if (sites.isEmpty()) throw siteNotFound();
    return sites.getFirst();
  }

  @Transactional
  public Site create(UUID clientId, CreateCommand command) {
    requireClient(clientId);
    UUID id = UUID.randomUUID();
    try {
      jdbc.update("insert into inspection_site (id,client_id,name,description,address_line,city,state,postal_code,latitude,longitude,contact_name,contact_phone,status) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
          id, clientId, command.name(), command.description(), command.addressLine(), command.city(), command.state(),
          command.postalCode(), command.latitude(), command.longitude(), command.contactName(), command.contactPhone(), "ACTIVE");
    } catch (org.springframework.dao.DataIntegrityViolationException ex) {
      throw nameConflict();
    }
    return get(id);
  }

  @Transactional
  public Site update(UUID id, UpdateCommand command) {
    Site current = get(id);
    int updated;
    try {
      updated = jdbc.update("update inspection_site set name=?,description=?,address_line=?,city=?,state=?,postal_code=?,latitude=?,longitude=?,contact_name=?,contact_phone=?,updated_at=current_timestamp,version=version+1 where id=? and version=?",
          value(command.name(), current.name()), value(command.description(), current.description()),
          value(command.addressLine(), current.addressLine()), value(command.city(), current.city()),
          value(command.state(), current.state()), value(command.postalCode(), current.postalCode()),
          value(command.latitude(), current.latitude()), value(command.longitude(), current.longitude()),
          value(command.contactName(), current.contactName()), value(command.contactPhone(), current.contactPhone()), id, command.version());
    } catch (org.springframework.dao.DataIntegrityViolationException ex) {
      throw nameConflict();
    }
    if (updated == 0) throw versionConflict();
    return get(id);
  }

  @Transactional
  public Site changeStatus(UUID id, StatusCommand command) {
    get(id);
    int updated = jdbc.update("update inspection_site set status=?,updated_at=current_timestamp,version=version+1 where id=? and version=?",
        command.status().name(), id, command.version());
    if (updated == 0) throw versionConflict();
    return get(id);
  }

  private void requireClient(UUID id) {
    Boolean exists = jdbc.queryForObject("select exists(select 1 from client where id=?)", Boolean.class, id);
    if (!Boolean.TRUE.equals(exists)) throw clientNotFound();
  }

  private static <T> T value(T update, T current) { return update == null ? current : update; }

  private static Site map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Site(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("client_id")), rs.getString("name"),
        rs.getString("description"), rs.getString("address_line"), rs.getString("city"), rs.getString("state"),
        rs.getString("postal_code"), rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
        rs.getString("contact_name"), rs.getString("contact_phone"), Status.valueOf(rs.getString("status")),
        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), rs.getInt("version"));
  }

  private static Instant instant(Timestamp value) { return value.toInstant(); }
  private static ApiException clientNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND", "Client not found"); }
  private static ApiException siteNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "SITE_NOT_FOUND", "Site not found"); }
  private static ApiException nameConflict() { return new ApiException(HttpStatus.CONFLICT, "SITE_NAME_ALREADY_EXISTS", "A site with this name already exists for the client"); }
  private static ApiException versionConflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Site was modified by another request"); }

  public enum Status { ACTIVE, INACTIVE }
  public record CreateCommand(String name, String description, String addressLine, String city, String state, String postalCode,
                              BigDecimal latitude, BigDecimal longitude, String contactName, String contactPhone) {}
  public record UpdateCommand(String name, String description, String addressLine, String city, String state, String postalCode,
                              BigDecimal latitude, BigDecimal longitude, String contactName, String contactPhone, int version) {}
  public record StatusCommand(Status status, int version) {}
  public record Site(UUID id, UUID clientId, String name, String description, String addressLine, String city, String state,
                     String postalCode, BigDecimal latitude, BigDecimal longitude, String contactName, String contactPhone,
                     Status status, Instant createdAt, Instant updatedAt, int version) {}
  public record Page(List<Site> items, PageMeta page) {}
  public record PageMeta(int page, int size, long totalElements, int totalPages) {}
}
