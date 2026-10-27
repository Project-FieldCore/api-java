package com.fieldops.client;

import com.fieldops.shared.error.ApiException;
import com.fieldops.shared.pagination.PageQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClientService {
  private final JdbcTemplate jdbc;

  public ClientService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public Page list(int page, int size, String sort, String query, Status status) {
    String where = " where (? is null or lower(name) like lower(?) or lower(legal_name) like lower(?) or lower(document) like lower(?) or lower(email) like lower(?)) and (? is null or status=?)";
    String match = query == null || query.isBlank() ? null : "%" + query.trim() + "%";
    Object[] filter = {match, match, match, match, match, status == null ? null : status.name(), status == null ? null : status.name()};
    long total = jdbc.queryForObject("select count(*) from client" + where, Long.class, filter);
    List<Client> items = jdbc.query(
        "select * from client" + where + " order by " + PageQuery.orderBy(sort, java.util.Map.of("name", "name", "createdAt", "created_at")) + " limit ? offset ?",
        (rs, row) -> map(rs), concat(filter, size, page * size));
    return new Page(items, new PageMeta(page, size, total, (int) Math.ceil((double) total / size)));
  }

  private static Object[] concat(Object[] values, Object... tail) { Object[] result = java.util.Arrays.copyOf(values, values.length + tail.length); System.arraycopy(tail, 0, result, values.length, tail.length); return result; }

  public Client get(UUID id) {
    List<Client> clients = jdbc.query("select * from client where id=?", (rs, row) -> map(rs), id);
    if (clients.isEmpty()) throw notFound();
    return clients.getFirst();
  }

  @Transactional
  public Client create(CreateCommand command) {
    if (hasDocument(command.document(), null)) throw documentConflict();
    UUID id = UUID.randomUUID();
    jdbc.update("insert into client (id,name,legal_name,document,email,phone,status) values (?,?,?,?,?,?,?)",
        id, command.name(), command.legalName(), blankToNull(command.document()), command.email(), command.phone(), "ACTIVE");
    return get(id);
  }

  @Transactional
  public Client update(UUID id, UpdateCommand command) {
    Client current = get(id);
    String document = command.document() == null ? current.document() : blankToNull(command.document());
    if (hasDocument(document, id)) throw documentConflict();
    int updated = jdbc.update(
        "update client set name=?,legal_name=?,document=?,email=?,phone=?,updated_at=current_timestamp,version=version+1 where id=? and version=?",
        command.name() == null ? current.name() : command.name(),
        command.legalName() == null ? current.legalName() : command.legalName(), document,
        command.email() == null ? current.email() : command.email(),
        command.phone() == null ? current.phone() : command.phone(), id, command.version());
    if (updated == 0) throw versionConflict();
    return get(id);
  }

  @Transactional
  public Client changeStatus(UUID id, StatusCommand command) {
    get(id);
    int updated = jdbc.update(
        "update client set status=?,updated_at=current_timestamp,version=version+1 where id=? and version=?",
        command.status().name(), id, command.version());
    if (updated == 0) throw versionConflict();
    return get(id);
  }

  private boolean hasDocument(String document, UUID excludedId) {
    if (document == null) return false;
    String sql = excludedId == null ? "select exists(select 1 from client where document=?)"
        : "select exists(select 1 from client where document=? and id<>?)";
    Boolean exists = excludedId == null ? jdbc.queryForObject(sql, Boolean.class, document)
        : jdbc.queryForObject(sql, Boolean.class, document, excludedId);
    return Boolean.TRUE.equals(exists);
  }

  private static Client map(ResultSet rs) throws SQLException {
    return new Client(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("legal_name"),
        rs.getString("document"), rs.getString("email"), rs.getString("phone"), Status.valueOf(rs.getString("status")),
        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), rs.getInt("version"));
  }

  private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
  private static Instant instant(Timestamp value) { return value.toInstant(); }
  private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND", "Client not found"); }
  private static ApiException documentConflict() { return new ApiException(HttpStatus.CONFLICT, "DOCUMENT_ALREADY_EXISTS", "Document is already in use"); }
  private static ApiException versionConflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Client was modified by another request"); }

  public enum Status { ACTIVE, INACTIVE }
  public record CreateCommand(String name, String legalName, String document, String email, String phone) {}
  public record UpdateCommand(String name, String legalName, String document, String email, String phone, int version) {}
  public record StatusCommand(Status status, int version) {}
  public record Client(UUID id, String name, String legalName, String document, String email, String phone, Status status,
                       Instant createdAt, Instant updatedAt, int version) {}
  public record Page(List<Client> items, PageMeta page) {}
  public record PageMeta(int page, int size, long totalElements, int totalPages) {}
}
