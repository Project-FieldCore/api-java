package com.fieldops.user;

import com.fieldops.shared.error.ApiException;
import com.fieldops.shared.security.Role;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;

  public UserService(JdbcTemplate jdbc, PasswordEncoder passwords) {
    this.jdbc = jdbc;
    this.passwords = passwords;
  }

  public Page list(int page, int size) {
    long total = jdbc.queryForObject("select count(*) from app_user", Long.class);
    List<User> items = jdbc.query(
        "select id,name,email,role,status,phone,created_at,updated_at,version from app_user "
            + "order by name, id limit ? offset ?",
        (rs, row) -> map(rs), size, page * size);
    return new Page(items, new PageMeta(page, size, total, (int) Math.ceil((double) total / size)));
  }

  public User get(UUID id) {
    List<User> users = jdbc.query(
        "select id,name,email,role,status,phone,created_at,updated_at,version from app_user where id=?",
        (rs, row) -> map(rs), id);
    if (users.isEmpty()) throw notFound();
    return users.getFirst();
  }

  @Transactional
  public User create(CreateCommand command) {
    if (emailExists(command.email(), null)) throw emailConflict();
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into app_user (id,name,email,password_hash,role,status,phone) values (?,?,?,?,?,?,?)",
        id, command.name(), command.email(), passwords.encode(command.password()), command.role().name(),
        "ACTIVE", command.phone());
    return get(id);
  }

  @Transactional
  public User update(UUID id, UpdateCommand command) {
    User current = get(id);
    if (command.email() != null && emailExists(command.email(), id)) throw emailConflict();
    int updated = jdbc.update(
        "update app_user set name=?,email=?,role=?,phone=?,updated_at=current_timestamp,version=version+1 "
            + "where id=? and version=?",
        command.name() == null ? current.name() : command.name(),
        command.email() == null ? current.email() : command.email(),
        command.role() == null ? current.role().name() : command.role().name(),
        command.phone() == null ? current.phone() : command.phone(), id, command.version());
    if (updated == 0) throw versionConflict();
    return get(id);
  }

  @Transactional
  public User changeStatus(UUID id, StatusCommand command) {
    get(id);
    int updated = jdbc.update(
        "update app_user set status=?,updated_at=current_timestamp,version=version+1 where id=? and version=?",
        command.status().name(), id, command.version());
    if (updated == 0) throw versionConflict();
    return get(id);
  }

  private boolean emailExists(String email, UUID excludedId) {
    String sql = excludedId == null
        ? "select exists(select 1 from app_user where email=?)"
        : "select exists(select 1 from app_user where email=? and id<>?)";
    Boolean exists = excludedId == null
        ? jdbc.queryForObject(sql, Boolean.class, email)
        : jdbc.queryForObject(sql, Boolean.class, email, excludedId);
    return Boolean.TRUE.equals(exists);
  }

  private static User map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new User(
        UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("email"),
        Role.valueOf(rs.getString("role")), Status.valueOf(rs.getString("status")),
        rs.getString("phone"), instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")), rs.getInt("version"));
  }

  private static Instant instant(Timestamp value) { return value.toInstant(); }
  private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"); }
  private static ApiException emailConflict() { return new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "Email is already in use"); }
  private static ApiException versionConflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "User was modified by another request"); }

  public enum Status { ACTIVE, INACTIVE, BLOCKED }
  public record CreateCommand(String name, String email, String password, Role role, String phone) {}
  public record UpdateCommand(String name, String email, Role role, String phone, int version) {}
  public record StatusCommand(Status status, int version) {}
  public record User(UUID id, String name, String email, Role role, Status status, String phone,
                     Instant createdAt, Instant updatedAt, int version) {}
  public record Page(List<User> items, PageMeta page) {}
  public record PageMeta(int page, int size, long totalElements, int totalPages) {}
}
