package com.fieldops.release;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * AC-RELEASE 17.19 — API-relevant critical path (login → schedule → execute → sync → review → audit).
 *
 * <p>Mapping (item → coverage):
 * <ul>
 *   <li>1 login administrativo — {@link #loginScheduleExecuteSyncReviewApproveAndAuditTrail()}</li>
 *   <li>2 cliente/local/equipamento — seed JDBC (API CRUD covered elsewhere; WEB UI cadastro is UI-only)</li>
 *   <li>3 modelo publicado — seed JDBC (template publish APIs covered in template tests)</li>
 *   <li>4 agendamento/atribuição — schedule POST in chain</li>
 *   <li>5 login técnico — chain</li>
 *   <li>6 download — GET /inspections/my + checklist</li>
 *   <li>7 início — POST .../start (+ location item 11)</li>
 *   <li>8 resposta checklist — sync push UPSERT + complete answers</li>
 *   <li>9 foto — multipart evidence</li>
 *   <li>10 QR Code — confirm-equipment</li>
 *   <li>11 localização — start/complete coordinates</li>
 *   <li>12–14 offline UX — UI/mobile-only (API asserts sync idempotency instead)</li>
 *   <li>15 sync sem duplicidade — push ALREADY_APPLIED + pull cursor</li>
 *   <li>16 visualização administrativa — list + monitoring</li>
 *   <li>17 aprovação — review start → approve (reject: tagged existing test)</li>
 *   <li>18 atualização no app — UI/mobile-only after pull payload</li>
 *   <li>19 histórico mínimo — GET .../audit</li>
 * </ul>
 *
 * <p>Run only this suite: {@code ./mvnw -B test -Dgroups=critical}
 */
@Tag("critical")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "fieldops.security.jwt.secret=test-signing-key-for-auth-integration-only-32")
class AcReleaseCriticalPathTest {
  private static final String PASSWORD = "correct-password";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;
  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void clean() {
    clear();
  }

  @AfterEach
  void clear() {
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
  @DisplayName("17.19 API chain: login→schedule→execute→sync→review→audit")
  void loginScheduleExecuteSyncReviewApproveAndAuditTrail() throws Exception {
    UUID supervisorId = insertUser("supervisor@release.test", "Supervisor", "SUPERVISOR");
    UUID technicianId = insertUser("technician@release.test", "Technician", "TECHNICIAN");
    UUID client = insertClient();
    UUID site = insertSite(client);
    insertEquipment(site, "QR-RELEASE-01");
    UUID version = insertPublishedVersion();

    String supervisorToken = login("supervisor@release.test");
    String technicianToken = login("technician@release.test");

    String scheduled = mvc.perform(post("/api/v1/inspections")
            .header("Authorization", bearer(supervisorToken))
            .contentType("application/json")
            .content(scheduleBody(version, client, site, technicianId)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ASSIGNED"))
        .andExpect(jsonPath("$.technicianId").value(technicianId.toString()))
        .andReturn().getResponse().getContentAsString();
    UUID inspection = UUID.fromString(json.readTree(scheduled).path("id").asText());

    mvc.perform(get("/api/v1/inspections/my").header("Authorization", bearer(technicianToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(inspection.toString()));
    mvc.perform(get("/api/v1/inspections/my/{id}", inspection).header("Authorization", bearer(technicianToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ASSIGNED"));
    String checklist = mvc.perform(get("/api/v1/inspections/my/{id}/checklist", inspection)
            .header("Authorization", bearer(technicianToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].items[0].id").exists())
        .andReturn().getResponse().getContentAsString();
    UUID itemId = UUID.fromString(json.readTree(checklist).path(0).path("items").path(0).path("id").asText());

    mvc.perform(post("/api/v1/inspections/my/{id}/confirm-equipment", inspection)
            .header("Authorization", bearer(technicianToken))
            .contentType("application/json")
            .content("{\"qrCode\":\"QR-RELEASE-01\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.equipmentConfirmedAt").exists());

    mvc.perform(post("/api/v1/inspections/my/{id}/start", inspection)
            .header("Authorization", bearer(technicianToken))
            .contentType("application/json")
            .content("{\"startedAtDevice\":\"2026-11-11T12:00:00Z\",\"latitude\":-23.55052,\"longitude\":-46.633308}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

    int baseVersion = jdbc.queryForObject("select version from inspection where id=?", Integer.class, inspection);
    UUID operationId = UUID.randomUUID();
    String pushBody = syncBatch(operationId, itemId, baseVersion, "\"valueBoolean\":true");
    mvc.perform(post("/api/v1/mobile/sync/push")
            .header("Authorization", bearer(technicianToken))
            .contentType("application/json")
            .content(pushBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].status").value("APPLIED"));
    mvc.perform(post("/api/v1/mobile/sync/push")
            .header("Authorization", bearer(technicianToken))
            .contentType("application/json")
            .content(pushBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].status").value("ALREADY_APPLIED"));
    org.assertj.core.api.Assertions.assertThat(
        jdbc.queryForObject("select count(*) from inspection_response where inspection_id=?", Integer.class, inspection))
        .isEqualTo(1);

    MockMultipartFile photo = new MockMultipartFile("file", "release.jpg", "image/jpeg", new byte[] {1, 2, 3, 4});
    mvc.perform(multipart("/api/v1/inspections/my/{inspectionId}/items/{itemId}/evidence", inspection, itemId)
            .file(photo)
            .header("Authorization", bearer(technicianToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fileName").value("release.jpg"));

    int completeVersion = jdbc.queryForObject("select version from inspection where id=?", Integer.class, inspection);
    mvc.perform(post("/api/v1/inspections/my/{id}/complete", inspection)
            .header("Authorization", bearer(technicianToken))
            .contentType("application/json")
            .content("{\"version\":" + completeVersion
                + ",\"latitude\":-23.55,\"longitude\":-46.63,"
                + "\"answers\":[{\"itemId\":\"" + itemId + "\",\"valueBoolean\":true}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUBMITTED"));

    MvcResult pullBootstrap = mvc.perform(get("/api/v1/mobile/sync/pull")
            .header("Authorization", bearer(technicianToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextCursor").isString())
        .andReturn();
    String cursor = json.readTree(pullBootstrap.getResponse().getContentAsString()).path("nextCursor").asText();
    mvc.perform(get("/api/v1/mobile/sync/pull")
            .param("cursor", cursor)
            .header("Authorization", bearer(technicianToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextCursor").isString());

    mvc.perform(get("/api/v1/inspections").param("status", "SUBMITTED")
            .header("Authorization", bearer(supervisorToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.id=='" + inspection + "')].status", hasItem("SUBMITTED")));
    mvc.perform(get("/api/v1/inspections/{id}/monitoring", inspection)
            .header("Authorization", bearer(supervisorToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inspectionId").value(inspection.toString()));

    mvc.perform(post("/api/v1/inspections/{id}/review/start", inspection)
            .header("Authorization", bearer(supervisorToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
    mvc.perform(post("/api/v1/inspections/{id}/review/approve", inspection)
            .header("Authorization", bearer(supervisorToken))
            .contentType("application/json")
            .content("{\"comentario\":\"AC-RELEASE OK\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));

    mvc.perform(get("/api/v1/inspections/{id}/audit", inspection)
            .header("Authorization", bearer(supervisorToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].action", hasItem("SCHEDULED")))
        .andExpect(jsonPath("$[*].action", hasItem("STARTED")))
        .andExpect(jsonPath("$[*].action", hasItem("SUBMITTED")))
        .andExpect(jsonPath("$[*].action", hasItem("REVIEW_STARTED")))
        .andExpect(jsonPath("$[*].action", hasItem("REVIEW_APPROVED")))
        .andExpect(jsonPath("$[0].createdAt").exists())
        .andExpect(jsonPath("$[0].payload", containsString("to")));

    org.assertj.core.api.Assertions.assertThat(supervisorId).isNotNull();
  }

  private String login(String email) throws Exception {
    String body = mvc.perform(post("/api/v1/auth/login")
            .contentType("application/json")
            .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).path("accessToken").asText();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private static String scheduleBody(UUID version, UUID client, UUID site, UUID technician) {
    return "{\"templateVersionId\":\"" + version + "\",\"clientId\":\"" + client + "\",\"siteId\":\"" + site
        + "\",\"technicianId\":\"" + technician + "\",\"scheduledFor\":\"" + OffsetDateTime.now().plusDays(1)
        + "\",\"priority\":\"MEDIUM\",\"instructions\":\"AC-RELEASE critical path\"}";
  }

  private static String syncBatch(UUID operationId, UUID itemId, int baseVersion, String payloadFields) {
    return "{\"deviceId\":\"" + UUID.randomUUID() + "\",\"operations\":[{"
        + "\"operationId\":\"" + operationId + "\","
        + "\"entityType\":\"INSPECTION_RESPONSE\","
        + "\"entityId\":\"" + UUID.randomUUID() + "\","
        + "\"operationType\":\"UPSERT\","
        + "\"baseVersion\":" + baseVersion + ","
        + "\"payload\":{\"inspectionItemId\":\"" + itemId + "\"," + payloadFields + "}"
        + "}]}";
  }

  private UUID insertUser(String email, String name, String role) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into app_user (id,email,name,password_hash,role,status,version) values (?,?,?,?,?,?,0)",
        id, email, name, passwords.encode(PASSWORD), role, "ACTIVE");
    return id;
  }

  private UUID insertClient() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into client (id,name,status,version) values (?,?,?,0)", id, "Release Client", "ACTIVE");
    return id;
  }

  private UUID insertSite(UUID client) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into inspection_site (id,client_id,name,status,version) values (?,?,?,?,0)",
        id, client, "Release Site", "ACTIVE");
    return id;
  }

  private void insertEquipment(UUID siteId, String qrCode) {
    jdbc.update("insert into equipment (id,site_id,name,qr_code,status) values (?,?,?,?,?)",
        UUID.randomUUID(), siteId, "Pump", qrCode, "ACTIVE");
  }

  private UUID insertPublishedVersion() {
    UUID template = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    jdbc.update("insert into inspection_template (id,title,category,status,version) values (?,?,?,?,0)",
        template, "Release Template", "SAFETY", "ACTIVE");
    jdbc.update(
        "insert into inspection_template_version (id,template_id,version_number,title_snapshot,published_at,active_for_new_inspections) values (?,?,?,?,current_timestamp,true)",
        version, template, 1, "Release published");
    UUID section = UUID.randomUUID();
    jdbc.update("insert into template_section (id,template_version_id,title,display_order) values (?,?,?,0)",
        section, version, "Section");
    jdbc.update("insert into template_item (id,section_id,title,response_type,display_order) values (?,?,?,?,0)",
        UUID.randomUUID(), section, "Item", "BOOLEAN");
    return version;
  }
}
