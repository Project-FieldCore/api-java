package com.fieldops.auth;

import com.fieldops.shared.error.ApiException;
import com.fieldops.shared.security.FieldOpsSecurityProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import io.jsonwebtoken.Claims;
import javax.crypto.SecretKey;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  /**
   * Fixed BCrypt hash used only to equalize login timing when the e-mail is unknown.
   * Password material is never logged; matches() still runs the same work factor.
   */
  private static final String DUMMY_PASSWORD_HASH =
      "$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG";

  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final FieldOpsSecurityProperties properties;
  private final SecureRandom random = new SecureRandom();

  public AuthService(JdbcTemplate jdbc, PasswordEncoder passwords, FieldOpsSecurityProperties properties) {
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.properties = properties;
  }

  @Transactional
  public Tokens login(String email, String password) {
    var users = jdbc.query(
        "select id,name,email,password_hash,role,status from app_user where email = ?",
        (rs, row) -> new User(
            UUID.fromString(rs.getString("id")),
            rs.getString("name"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getString("status")),
        email);
    String hash = users.isEmpty() ? DUMMY_PASSWORD_HASH : users.getFirst().hash();
    boolean matches = passwords.matches(password, hash);
    if (users.isEmpty() || !matches) throw invalidCredentials();
    User user = users.getFirst();
    if (!"ACTIVE".equals(user.status())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Account is inactive");
    }
    return issue(user);
  }

  @Transactional(noRollbackFor = ApiException.class)
  public Tokens refresh(String rawToken) {
    var tokens = jdbc.query(
        "select rt.id,rt.family_id,rt.user_id,rt.revoked_at,rt.expires_at,u.name,u.email,u.role,u.status "
            + "from refresh_token rt join app_user u on u.id=rt.user_id where rt.token_hash=?",
        (rs, row) -> new Refresh(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("family_id")),
            new User(
                UUID.fromString(rs.getString("user_id")),
                rs.getString("name"),
                rs.getString("email"),
                null,
                rs.getString("role"),
                rs.getString("status")),
            rs.getTimestamp("revoked_at") != null,
            rs.getTimestamp("expires_at").toInstant().isAfter(Instant.now())),
        hash(rawToken));
    if (tokens.isEmpty()) throw invalidCredentials();

    Refresh token = tokens.getFirst();
    if (token.revoked()) {
      revokeFamily(token.familyId());
      throw invalidCredentials();
    }
    if (!token.unexpired() || !"ACTIVE".equals(token.user().status())) throw invalidCredentials();

    int revoked = jdbc.update(
        "update refresh_token set revoked_at=current_timestamp where id=? and revoked_at is null",
        token.id());
    if (revoked != 1) {
      revokeFamily(token.familyId());
      throw invalidCredentials();
    }
    return issue(token.user(), token.familyId());
  }

  @Transactional
  public void logout(String rawToken) {
    var families = jdbc.query(
        "select family_id from refresh_token where token_hash=?",
        (rs, row) -> UUID.fromString(rs.getString("family_id")),
        hash(rawToken));
    if (!families.isEmpty()) revokeFamily(families.getFirst());
  }

  /**
   * Revokes every active refresh token for the user (all families). Call on deactivate/block
   * and from future password-reset flows (RN-008).
   */
  @Transactional
  public void revokeAllSessions(UUID userId) {
    jdbc.update(
        "update refresh_token set revoked_at=current_timestamp where user_id=? and revoked_at is null",
        userId);
  }

  public User authenticatedUser(String accessToken) {
    Claims claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(accessToken).getPayload();
    UUID id = UUID.fromString(claims.getSubject());
    var users = jdbc.query(
        "select id,name,email,role,status from app_user where id=?",
        (rs, row) -> new User(
            UUID.fromString(rs.getString("id")),
            rs.getString("name"),
            rs.getString("email"),
            null,
            rs.getString("role"),
            rs.getString("status")),
        id);
    if (users.isEmpty() || !"ACTIVE".equals(users.getFirst().status())) throw invalidCredentials();
    return users.getFirst();
  }

  public Profile profile(UUID id) {
    User user = jdbc.queryForObject(
        "select id,name,email,role,status from app_user where id=?",
        (rs, row) -> new User(
            UUID.fromString(rs.getString("id")),
            rs.getString("name"),
            rs.getString("email"),
            null,
            rs.getString("role"),
            rs.getString("status")),
        id);
    if (user == null || !"ACTIVE".equals(user.status())) throw invalidCredentials();
    return new Profile(user.id(), user.name(), user.email(), user.role());
  }

  private Tokens issue(User user) {
    return issue(user, UUID.randomUUID());
  }

  private Tokens issue(User user, UUID familyId) {
    Instant now = Instant.now();
    String access = Jwts.builder()
        .subject(user.id().toString())
        .claim("role", user.role())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(properties.getJwt().getAccessTtl())))
        .signWith(key())
        .compact();
    byte[] bytes = new byte[48];
    random.nextBytes(bytes);
    String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    jdbc.update(
        "insert into refresh_token (id,user_id,token_hash,expires_at,family_id) values (?,?,?,?,?)",
        UUID.randomUUID(),
        user.id(),
        hash(refresh),
        Date.from(now.plus(properties.getJwt().getRefreshTtl())),
        familyId);
    return new Tokens(
        access,
        refresh,
        properties.getJwt().getAccessTtl().toSeconds(),
        new Profile(user.id(), user.name(), user.email(), user.role()));
  }

  private void revokeFamily(UUID familyId) {
    jdbc.update(
        "update refresh_token set revoked_at=current_timestamp where family_id=? and revoked_at is null",
        familyId);
  }

  private SecretKey key() {
    return Keys.hmacShaKeyFor(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
  }

  private static String hash(String value) {
    try {
      return Base64.getEncoder()
          .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static ApiException invalidCredentials() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
  }

  public record User(UUID id, String name, String email, String hash, String role, String status) {}

  record Refresh(UUID id, UUID familyId, User user, boolean revoked, boolean unexpired) {}

  public record Profile(UUID id, String name, String email, String role) {}

  public record Tokens(String accessToken, String refreshToken, long expiresIn, Profile user) {}
}
