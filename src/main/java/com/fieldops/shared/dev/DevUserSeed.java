package com.fieldops.shared.dev;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Local/dev-only demo users for web E2E. Never enabled outside the {@code dev} profile. */
@Component
@Profile("dev")
public class DevUserSeed implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(DevUserSeed.class);
  private static final String DEMO_PASSWORD = "DemoPass123!";

  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;

  public DevUserSeed(JdbcTemplate jdbc, PasswordEncoder passwords) {
    this.jdbc = jdbc;
    this.passwords = passwords;
  }

  @Override
  public void run(ApplicationArguments args) {
    seed("admin@fieldcore.com", "Admin Demo", "ADMIN");
    seed("ana.silva@example.com", "Ana Silva", "SUPERVISOR");
  }

  private void seed(String email, String name, String role) {
    Boolean exists =
        jdbc.queryForObject("select exists(select 1 from app_user where email=?)", Boolean.class, email);
    if (Boolean.TRUE.equals(exists)) {
      return;
    }
    jdbc.update(
        "insert into app_user (id,email,name,password_hash,role,status) values (?,?,?,?,?,?)",
        UUID.randomUUID(),
        email,
        name,
        passwords.encode(DEMO_PASSWORD),
        role,
        "ACTIVE");
    log.info("Dev seed: created {} ({})", email, role);
  }
}
