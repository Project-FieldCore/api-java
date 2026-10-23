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
    var users = jdbc.query("select id,name,email,password_hash,role,status from app_user where email = ?", (rs, row) -> new User(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("email"), rs.getString("password_hash"), rs.getString("role"), rs.getString("status")), email);
    if (users.isEmpty() || !passwords.matches(password, users.getFirst().hash())) throw invalidCredentials();
    User user = users.getFirst();
    if (!"ACTIVE".equals(user.status())) throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Account is inactive");
    return issue(user);
  }

  @Transactional
  public Tokens refresh(String rawToken) {
    var tokens = jdbc.query("select rt.id,rt.user_id,u.name,u.email,u.role,u.status from refresh_token rt join app_user u on u.id=rt.user_id where rt.token_hash=? and rt.revoked_at is null and rt.expires_at > current_timestamp", (rs, row) -> new Refresh(UUID.fromString(rs.getString("id")), new User(UUID.fromString(rs.getString("user_id")), rs.getString("name"), rs.getString("email"), null, rs.getString("role"), rs.getString("status"))), hash(rawToken));
    if (tokens.isEmpty() || !"ACTIVE".equals(tokens.getFirst().user().status())) throw invalidCredentials();
    jdbc.update("update refresh_token set revoked_at=current_timestamp where id=?", tokens.getFirst().id());
    return issue(tokens.getFirst().user());
  }

  @Transactional
  public void logout(String rawToken) { jdbc.update("update refresh_token set revoked_at=current_timestamp where token_hash=?", hash(rawToken)); }

  public User authenticatedUser(String accessToken) {
    Claims claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(accessToken).getPayload();
    UUID id = UUID.fromString(claims.getSubject());
    var users = jdbc.query("select id,name,email,role,status from app_user where id=?", (rs, row) -> new User(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("email"), null, rs.getString("role"), rs.getString("status")), id);
    if (users.isEmpty() || !"ACTIVE".equals(users.getFirst().status())) throw invalidCredentials();
    return users.getFirst();
  }

  public Profile profile(UUID id) {
    User user = jdbc.queryForObject("select id,name,email,role,status from app_user where id=?", (rs, row) -> new User(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("email"), null, rs.getString("role"), rs.getString("status")), id);
    if (user == null || !"ACTIVE".equals(user.status())) throw invalidCredentials();
    return new Profile(user.id(), user.name(), user.email(), user.role());
  }

  private Tokens issue(User user) {
    Instant now = Instant.now();
    String access = Jwts.builder().subject(user.id().toString()).claim("role", user.role()).issuedAt(Date.from(now)).expiration(Date.from(now.plus(properties.getJwt().getAccessTtl()))).signWith(key()).compact();
    byte[] bytes = new byte[48]; random.nextBytes(bytes);
    String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    jdbc.update("insert into refresh_token (id,user_id,token_hash,expires_at) values (?,?,?,?)", UUID.randomUUID(), user.id(), hash(refresh), Date.from(now.plus(properties.getJwt().getRefreshTtl())));
    return new Tokens(access, refresh, properties.getJwt().getAccessTtl().toSeconds(), new Profile(user.id(), user.name(), user.email(), user.role()));
  }

  private SecretKey key() {
    String secret = properties.getJwt().getSecret();
    if (secret == null || secret.length() < 32) throw new IllegalStateException("JWT_SECRET must contain at least 32 characters");
    return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }
  private static String hash(String value) { try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
  private static ApiException invalidCredentials() { return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password"); }
  public record User(UUID id, String name, String email, String hash, String role, String status) {}
  record Refresh(UUID id, User user) {}
  public record Profile(UUID id, String name, String email, String role) {}
  public record Tokens(String accessToken, String refreshToken, long expiresIn, Profile user) {}
}
