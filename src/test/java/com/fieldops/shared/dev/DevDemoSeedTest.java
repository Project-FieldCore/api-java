package com.fieldops.shared.dev;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "fieldops.security.jwt.secret=test-signing-key-for-auth-integration-only-32",
    "fieldops.dev.seed.enabled=true",
    // Isolate from other SpringBootTest classes sharing jdbc:h2:mem:fieldops
    "spring.datasource.url=jdbc:h2:mem:fieldops_demo_seed;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
})
class DevDemoSeedTest {
  @Autowired DevDemoSeed seed;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void resetAndSeed() {
    clearAll();
    seed.seedAll();
  }

  @AfterEach
  void tearDown() {
    clearAll();
  }

  private void clearAll() {
    jdbc.update("delete from sync_idempotency");
    jdbc.update("delete from mobile_sync_operation");
    jdbc.update("delete from mobile_sync_tombstone");
    jdbc.update("delete from evidence");
    jdbc.update("delete from non_conformity");
    jdbc.update("delete from inspection_response");
    jdbc.update("delete from inspection_item_snapshot");
    jdbc.update("delete from inspection_review");
    jdbc.update("delete from audit_event");
    jdbc.update("delete from inspection");
    jdbc.update("delete from template_item");
    jdbc.update("delete from template_section");
    jdbc.update("delete from inspection_template_version");
    jdbc.update("delete from inspection_template");
    jdbc.update("delete from equipment");
    jdbc.update("delete from inspection_site");
    jdbc.update("delete from client");
    jdbc.update("delete from refresh_token");
    jdbc.update("delete from app_user");
  }

  @Test
  void seedsUsersMasterDataTemplateAndInspectionStatesIdempotently() {
    assertThat(countUsers(DevDemoSeed.ADMIN_EMAIL, "ADMIN")).isEqualTo(1);
    assertThat(countUsers(DevDemoSeed.SUPERVISOR_EMAIL, "SUPERVISOR")).isEqualTo(1);
    assertThat(countUsers(DevDemoSeed.TECHNICIAN_EMAIL, "TECHNICIAN")).isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "select count(*) from client where document=?", Integer.class, DevDemoSeed.CLIENT_DOCUMENT))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "select count(*) from inspection_site where name=?", Integer.class, DevDemoSeed.SITE_NAME))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "select count(*) from equipment where qr_code=?", Integer.class, DevDemoSeed.EQUIPMENT_QR))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "select count(*) from inspection_template where title=?", Integer.class, DevDemoSeed.TEMPLATE_TITLE))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            """
            select count(*) from inspection_template_version v
            join inspection_template t on t.id=v.template_id
            where t.title=? and v.published_at is not null
            """,
            Integer.class, DevDemoSeed.TEMPLATE_TITLE))
        .isEqualTo(1);

    assertStatus(DevDemoSeed.INSPECTION_ASSIGNED, "ASSIGNED");
    assertStatus(DevDemoSeed.INSPECTION_IN_PROGRESS, "IN_PROGRESS");
    assertStatus(DevDemoSeed.INSPECTION_SUBMITTED, "SUBMITTED");
    assertStatus(DevDemoSeed.INSPECTION_UNDER_REVIEW, "UNDER_REVIEW");

    Integer snapshots = jdbc.queryForObject(
        "select count(*) from inspection_item_snapshot s join inspection i on i.id=s.inspection_id where i.title=?",
        Integer.class, DevDemoSeed.INSPECTION_ASSIGNED);
    assertThat(snapshots).isGreaterThanOrEqualTo(2);

    Integer responses = jdbc.queryForObject(
        "select count(*) from inspection_response r join inspection i on i.id=r.inspection_id where i.title=?",
        Integer.class, DevDemoSeed.INSPECTION_UNDER_REVIEW);
    assertThat(responses).isGreaterThanOrEqualTo(2);

    int usersBefore = jdbc.queryForObject("select count(*) from app_user", Integer.class);
    int inspectionsBefore = jdbc.queryForObject("select count(*) from inspection", Integer.class);
    int clientsBefore = jdbc.queryForObject("select count(*) from client", Integer.class);
    seed.seedAll();
    assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(usersBefore);
    assertThat(jdbc.queryForObject("select count(*) from inspection", Integer.class)).isEqualTo(inspectionsBefore);
    assertThat(jdbc.queryForObject("select count(*) from client", Integer.class)).isEqualTo(clientsBefore);
  }

  private int countUsers(String email, String role) {
    return jdbc.queryForObject(
        "select count(*) from app_user where email=? and role=? and status='ACTIVE'",
        Integer.class, email, role);
  }

  private void assertStatus(String title, String status) {
    assertThat(jdbc.queryForObject("select status from inspection where title=?", String.class, title))
        .isEqualTo(status);
  }
}
