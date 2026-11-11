package com.fieldops.shared.dev;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Reproducible local/dev demo dataset (AC-RELEASE / PBI-0066).
 * Active only under the {@code dev} profile — never production.
 * Idempotent: safe on restart; natural keys / fixed titles prevent duplicates.
 *
 * <p>Disable in tests with {@code fieldops.dev.seed.enabled=false} (see test
 * {@code application.properties}); {@link #seedAll()} remains callable when the bean is present.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "fieldops.dev.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DevDemoSeed implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(DevDemoSeed.class);

  /** Documented local-only demo password (same as prior DevUserSeed). Not a real credential. */
  public static final String DEMO_PASSWORD = "DemoPass123!";

  public static final String ADMIN_EMAIL = "admin@fieldcore.com";
  public static final String SUPERVISOR_EMAIL = "ana.silva@example.com";
  public static final String TECHNICIAN_EMAIL = "joao.tecnico@example.com";

  public static final String CLIENT_DOCUMENT = "DEMO-CLIENT-001";
  public static final String SITE_NAME = "Unidade Demo SP";
  public static final String EQUIPMENT_QR = "DEMO-EQ-001";
  public static final String TEMPLATE_TITLE = "Checklist Demo Segurança";

  public static final String INSPECTION_ASSIGNED = "[Demo] Atribuída — sync mobile";
  public static final String INSPECTION_IN_PROGRESS = "[Demo] Em execução";
  public static final String INSPECTION_SUBMITTED = "[Demo] Enviada — fila revisão";
  public static final String INSPECTION_UNDER_REVIEW = "[Demo] Em revisão";

  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;

  public DevDemoSeed(JdbcTemplate jdbc, PasswordEncoder passwords) {
    this.jdbc = jdbc;
    this.passwords = passwords;
  }

  @Override
  public void run(ApplicationArguments args) {
    seedAll();
  }

  /** Public for tests — re-runnable and idempotent. */
  public void seedAll() {
    UUID admin = ensureUser(ADMIN_EMAIL, "Admin Demo", "ADMIN");
    UUID supervisor = ensureUser(SUPERVISOR_EMAIL, "Ana Silva", "SUPERVISOR");
    UUID technician = ensureUser(TECHNICIAN_EMAIL, "João Técnico", "TECHNICIAN");
    UUID client = ensureClient();
    UUID site = ensureSite(client);
    UUID equipment = ensureEquipment(site);
    UUID templateVersion = ensurePublishedTemplate();
    ensureInspection(INSPECTION_ASSIGNED, "ASSIGNED", client, site, equipment, templateVersion, technician, supervisor, admin, false, false);
    ensureInspection(INSPECTION_IN_PROGRESS, "IN_PROGRESS", client, site, equipment, templateVersion, technician, supervisor, admin, true, false);
    ensureInspection(INSPECTION_SUBMITTED, "SUBMITTED", client, site, equipment, templateVersion, technician, supervisor, admin, true, true);
    ensureInspection(INSPECTION_UNDER_REVIEW, "UNDER_REVIEW", client, site, equipment, templateVersion, technician, supervisor, admin, true, true);
    log.info(
        "Dev demo seed ready (password={}). users=[{}]; client={}; inspections=[ASSIGNED,IN_PROGRESS,SUBMITTED,UNDER_REVIEW]",
        DEMO_PASSWORD,
        String.join(", ", ADMIN_EMAIL, SUPERVISOR_EMAIL, TECHNICIAN_EMAIL),
        CLIENT_DOCUMENT);
  }

  private UUID ensureUser(String email, String name, String role) {
    List<UUID> existing =
        jdbc.query("select id from app_user where email=?", (rs, row) -> UUID.fromString(rs.getString(1)), email);
    if (!existing.isEmpty()) {
      return existing.getFirst();
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into app_user (id,email,name,password_hash,role,status) values (?,?,?,?,?,?)",
        id, email, name, passwords.encode(DEMO_PASSWORD), role, "ACTIVE");
    log.info("Dev seed: created user {} ({})", email, role);
    return id;
  }

  private UUID ensureClient() {
    List<UUID> existing =
        jdbc.query("select id from client where document=?", (rs, row) -> UUID.fromString(rs.getString(1)), CLIENT_DOCUMENT);
    if (!existing.isEmpty()) {
      return existing.getFirst();
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into client (id,name,legal_name,document,email,status,version) values (?,?,?,?,?,?,0)",
        id, "Cliente Demo FieldOps", "Cliente Demo FieldOps Ltda", CLIENT_DOCUMENT, "contato@demo.fieldops.local", "ACTIVE");
    log.info("Dev seed: created client {}", CLIENT_DOCUMENT);
    return id;
  }

  private UUID ensureSite(UUID clientId) {
    List<UUID> existing =
        jdbc.query(
            "select id from inspection_site where client_id=? and name=?",
            (rs, row) -> UUID.fromString(rs.getString(1)),
            clientId, SITE_NAME);
    if (!existing.isEmpty()) {
      return existing.getFirst();
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into inspection_site (id,client_id,name,description,city,state,status,version) values (?,?,?,?,?,?,?,0)",
        id, clientId, SITE_NAME, "Site de demonstração local", "São Paulo", "SP", "ACTIVE");
    log.info("Dev seed: created site {}", SITE_NAME);
    return id;
  }

  private UUID ensureEquipment(UUID siteId) {
    List<UUID> existing =
        jdbc.query("select id from equipment where qr_code=?", (rs, row) -> UUID.fromString(rs.getString(1)), EQUIPMENT_QR);
    if (!existing.isEmpty()) {
      return existing.getFirst();
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into equipment (id,site_id,name,qr_code,asset_number,status,version) values (?,?,?,?,?,?,0)",
        id, siteId, "Bomba Demo", EQUIPMENT_QR, "ASSET-DEMO-1", "ACTIVE");
    log.info("Dev seed: created equipment {}", EQUIPMENT_QR);
    return id;
  }

  private UUID ensurePublishedTemplate() {
    List<UUID> existingVersions =
        jdbc.query(
            """
            select v.id from inspection_template_version v
            join inspection_template t on t.id = v.template_id
            where t.title = ? and v.published_at is not null
            order by v.version_number desc
            """,
            (rs, row) -> UUID.fromString(rs.getString(1)),
            TEMPLATE_TITLE);
    if (!existingVersions.isEmpty()) {
      return existingVersions.getFirst();
    }
    UUID templateId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID sectionId = UUID.randomUUID();
    jdbc.update(
        "insert into inspection_template (id,title,category,status,version) values (?,?,?,?,0)",
        templateId, TEMPLATE_TITLE, "SAFETY", "ACTIVE");
    jdbc.update(
        "insert into inspection_template_version (id,template_id,version_number,title_snapshot,published_at,active_for_new_inspections) values (?,?,?,?,current_timestamp,true)",
        versionId, templateId, 1, TEMPLATE_TITLE);
    jdbc.update(
        "insert into template_section (id,template_version_id,title,display_order) values (?,?,?,0)",
        sectionId, versionId, "Segurança básica");
    jdbc.update(
        "insert into template_item (id,section_id,title,response_type,required,display_order) values (?,?,?,?,?,0)",
        UUID.randomUUID(), sectionId, "EPI completo?", "BOOLEAN", true);
    jdbc.update(
        "insert into template_item (id,section_id,title,response_type,required,display_order) values (?,?,?,?,?,1)",
        UUID.randomUUID(), sectionId, "Área sinalizada?", "BOOLEAN", true);
    log.info("Dev seed: created published template {}", TEMPLATE_TITLE);
    return versionId;
  }

  private void ensureInspection(
      String title,
      String status,
      UUID clientId,
      UUID siteId,
      UUID equipmentId,
      UUID templateVersionId,
      UUID technicianId,
      UUID supervisorId,
      UUID createdBy,
      boolean started,
      boolean submitted) {
    Boolean exists =
        jdbc.queryForObject("select exists(select 1 from inspection where title=?)", Boolean.class, title);
    if (Boolean.TRUE.equals(exists)) {
      return;
    }
    UUID id = UUID.randomUUID();
    Instant scheduled = Instant.now().plus(1, ChronoUnit.DAYS);
    Timestamp startedAt = started ? Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS)) : null;
    Timestamp submittedAt = submitted ? Timestamp.from(Instant.now().minus(30, ChronoUnit.MINUTES)) : null;
    jdbc.update(
        """
        insert into inspection (
          id,client_id,site_id,equipment_id,template_version_id,technician_id,supervisor_id,created_by,
          title,instructions,priority,status,scheduled_for,started_at_server,submitted_at_server,version
        ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
        """,
        id, clientId, siteId, equipmentId, templateVersionId, technicianId, supervisorId, createdBy,
        title, "Inspeção de demonstração (PBI-0066). Reset: drop H2 / wipe DB e reinicie com profile dev.",
        "MEDIUM", status, Timestamp.from(scheduled), startedAt, submittedAt);
    copySnapshots(id, templateVersionId);
    if (submitted) {
      seedAnswers(id, technicianId);
    }
    log.info("Dev seed: created inspection '{}' ({})", title, status);
  }

  private void copySnapshots(UUID inspectionId, UUID templateVersionId) {
    record SnapshotRow(
        UUID sourceItemId,
        String sectionTitle,
        int sectionOrder,
        String itemTitle,
        String responseType,
        boolean required,
        int itemOrder) {}
    List<SnapshotRow> rows =
        jdbc.query(
            """
            select s.title as section_title, s.display_order as section_order,
                   i.id as item_id, i.title as item_title, i.response_type, i.required, i.display_order as item_order
            from template_section s
            join template_item i on i.section_id = s.id
            where s.template_version_id = ?
            order by s.display_order, i.display_order
            """,
            (rs, row) ->
                new SnapshotRow(
                    UUID.fromString(rs.getString("item_id")),
                    rs.getString("section_title"),
                    rs.getInt("section_order"),
                    rs.getString("item_title"),
                    rs.getString("response_type"),
                    rs.getBoolean("required"),
                    rs.getInt("item_order")),
            templateVersionId);
    for (SnapshotRow item : rows) {
      jdbc.update(
          """
          insert into inspection_item_snapshot (
            id,inspection_id,source_template_item_id,section_title,section_order,
            item_title,response_type,required,rules_json,item_order
          ) values (?,?,?,?,?,?,?,?,?,?)
          """,
          UUID.randomUUID(),
          inspectionId,
          item.sourceItemId(),
          item.sectionTitle(),
          item.sectionOrder(),
          item.itemTitle(),
          item.responseType(),
          item.required(),
          "{\"observationRequiredOnFailure\":false,\"evidenceRequiredOnFailure\":false}",
          item.itemOrder());
    }
  }

  private void seedAnswers(UUID inspectionId, UUID technicianId) {
    List<UUID> items =
        jdbc.query(
            "select id from inspection_item_snapshot where inspection_id=? order by item_order",
            (rs, row) -> UUID.fromString(rs.getString(1)),
            inspectionId);
    for (UUID itemId : items) {
      jdbc.update(
          """
          insert into inspection_response (
            id,inspection_id,inspection_item_id,value_boolean,conformity,answered_by,server_received_at
          ) values (?,?,?,?,?,?,current_timestamp)
          """,
          UUID.randomUUID(), inspectionId, itemId, true, "CONFORMING", technicianId);
    }
  }
}
