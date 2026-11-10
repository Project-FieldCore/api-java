package com.fieldops.mobile;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fieldops.shared.security.FieldOpsUserPrincipal;
import com.fieldops.shared.security.Role;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MobileSyncControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach void clean() { clear(); }
  @AfterEach void clear() {
    jdbc.update("delete from mobile_sync_operation");
    jdbc.update("delete from mobile_sync_tombstone");
    jdbc.update("delete from evidence");
    jdbc.update("delete from non_conformity");
    jdbc.update("delete from inspection_response");
    jdbc.update("delete from inspection_item_snapshot");
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
  void batchAppliesDependentResponseUpsertsInOrder() throws Exception {
    UUID technician = insertUser("TECHNICIAN");
    UUID inspection = insertInProgressInspection(technician, 0);
    UUID firstItem = insertSnapshot(inspection, 0);
    UUID secondItem = insertSnapshot(inspection, 1);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID device = UUID.randomUUID();

    mvc.perform(post("/api/v1/mobile/sync/push").with(technician(technician)).contentType("application/json")
            .content(batch(device, operation(first, firstItem, 0, null, "\"valueText\":\"alpha\"")
                + "," + operation(second, secondItem, 1, List.of(first), "\"conformity\":\"NON_CONFORMING\",\"observation\":\"Trinca\""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.serverTime").exists())
        .andExpect(jsonPath("$.results[0].operationId").value(first.toString()))
        .andExpect(jsonPath("$.results[0].status").value("APPLIED"))
        .andExpect(jsonPath("$.results[0].entityVersion").value(1))
        .andExpect(jsonPath("$.results[1].operationId").value(second.toString()))
        .andExpect(jsonPath("$.results[1].status").value("APPLIED"))
        .andExpect(jsonPath("$.results[1].entityVersion").value(2));

    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from inspection_response", Integer.class)).isEqualTo(2);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select value_text from inspection_response where inspection_item_id=?", String.class, firstItem)).isEqualTo("alpha");
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select conformity from inspection_response where inspection_item_id=?", String.class, secondItem)).isEqualTo("NON_CONFORMING");
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select version from inspection where id=?", Integer.class, inspection)).isEqualTo(2);
  }

  @Test
  void versionConflictDoesNotApplyDependentsAndKeepsHttpOk() throws Exception {
    UUID technician = insertUser("TECHNICIAN");
    UUID inspection = insertInProgressInspection(technician, 0);
    UUID firstItem = insertSnapshot(inspection, 0);
    UUID secondItem = insertSnapshot(inspection, 1);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();

    mvc.perform(post("/api/v1/mobile/sync/push").with(technician(technician)).contentType("application/json")
            .content(batch(UUID.randomUUID(), operation(first, firstItem, 4, null, "\"valueText\":\"stale\"")
                + "," + operation(second, secondItem, 0, List.of(first), "\"valueText\":\"later\""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].status").value("CONFLICT"))
        .andExpect(jsonPath("$.results[0].entityVersion").value(0))
        .andExpect(jsonPath("$.results[0].server.valueText").value(nullValue()))
        .andExpect(jsonPath("$.results[1].status").value("DEPENDENCY_FAILED"));

    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from inspection_response", Integer.class)).isZero();
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select version from inspection where id=?", Integer.class, inspection)).isZero();
  }

  @Test
  void resendingTheSameOperationIdIsAlreadyApplied() throws Exception {
    UUID technician = insertUser("TECHNICIAN");
    UUID inspection = insertInProgressInspection(technician, 3);
    UUID item = insertSnapshot(inspection, 0);
    UUID operationId = UUID.randomUUID();
    String body = batch(UUID.randomUUID(), operation(operationId, item, 3, null, "\"valueBoolean\":true"));

    mvc.perform(post("/api/v1/mobile/sync/push").with(technician(technician)).contentType("application/json").content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].status").value("APPLIED"))
        .andExpect(jsonPath("$.results[0].entityVersion").value(4));
    mvc.perform(post("/api/v1/mobile/sync/push").with(technician(technician)).contentType("application/json").content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].status").value("ALREADY_APPLIED"))
        .andExpect(jsonPath("$.results[0].entityVersion").value(4));

    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from inspection_response", Integer.class)).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select version from inspection where id=?", Integer.class, inspection)).isEqualTo(4);
  }

  @Test
  void staleVersionReturnsServerSnapshotAndCanBeAppliedAfterAnalysis() throws Exception {
    UUID technician = insertUser("TECHNICIAN");
    UUID inspection = insertInProgressInspection(technician, 2);
    UUID item = insertSnapshot(inspection, 0);
    UUID responseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    jdbc.update(
        "insert into inspection_response (id,inspection_id,inspection_item_id,value_text,value_boolean,value_date,observation,conformity,answered_by) values (?,?,?,?,?,?,?,?,?)",
        responseId, inspection, item, "server", true, java.sql.Date.valueOf("2026-08-01"), "ok", "CONFORMING", technician);
    String stale = batch(UUID.randomUUID(), operation(operationId, item, 0, null, "\"valueText\":\"local\""));

    mvc.perform(post("/api/v1/mobile/sync/push").with(technician(technician)).contentType("application/json").content(stale))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].status").value("CONFLICT"))
        .andExpect(jsonPath("$.results[0].entityVersion").value(2))
        .andExpect(jsonPath("$.results[0].server.id").value(responseId.toString()))
        .andExpect(jsonPath("$.results[0].server.valueText").value("server"))
        .andExpect(jsonPath("$.results[0].server.valueBoolean").value(true))
        .andExpect(jsonPath("$.results[0].server.valueDate").value("2026-08-01"))
        .andExpect(jsonPath("$.results[0].server.conformity").value("CONFORMING"))
        .andExpect(jsonPath("$.results[0].server.observation").value("ok"));
    mvc.perform(post("/api/v1/mobile/sync/push").with(technician(technician)).contentType("application/json").content(stale))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].status").value("CONFLICT"))
        .andExpect(jsonPath("$.results[0].entityVersion").value(2))
        .andExpect(jsonPath("$.results[0].server.valueText").value("server"));

    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from inspection_response", Integer.class)).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select value_text from inspection_response where id=?", String.class, responseId)).isEqualTo("server");
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select version from inspection where id=?", Integer.class, inspection)).isEqualTo(2);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from mobile_sync_operation where operation_id=?", Integer.class, operationId)).isZero();

    String resolved = batch(UUID.randomUUID(), operation(operationId, item, 2, null, "\"valueText\":\"local\",\"observation\":\"revisado\""));
    mvc.perform(post("/api/v1/mobile/sync/push").with(technician(technician)).contentType("application/json").content(resolved))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].status").value("APPLIED"))
        .andExpect(jsonPath("$.results[0].entityVersion").value(3));
    mvc.perform(post("/api/v1/mobile/sync/push").with(technician(technician)).contentType("application/json").content(resolved))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].status").value("ALREADY_APPLIED"))
        .andExpect(jsonPath("$.results[0].entityVersion").value(3));

    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from inspection_response", Integer.class)).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select value_text from inspection_response where id=?", String.class, responseId)).isEqualTo("local");
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select version from inspection where id=?", Integer.class, inspection)).isEqualTo(3);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from mobile_sync_operation where operation_id=?", Integer.class, operationId)).isEqualTo(1);
  }

  @Test
  void baseVersionMismatchIsConflictForThatOperation() throws Exception {
    UUID technician = insertUser("TECHNICIAN");
    UUID inspection = insertInProgressInspection(technician, 2);
    UUID item = insertSnapshot(inspection, 0);

    mvc.perform(post("/api/v1/mobile/sync/push").with(technician(technician)).contentType("application/json")
            .content(batch(UUID.randomUUID(), operation(UUID.randomUUID(), item, 0, null, "\"valueText\":\"nope\""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].status").value("CONFLICT"))
        .andExpect(jsonPath("$.results[0].entityVersion").value(2))
        .andExpect(jsonPath("$.results[0].server.id").value(nullValue()));
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from inspection_response", Integer.class)).isZero();
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select version from inspection where id=?", Integer.class, inspection)).isEqualTo(2);
  }

  @Test
  void unknownOperationAndForeignInspectionAreRejectedWithoutWriting() throws Exception {
    UUID technician = insertUser("TECHNICIAN");
    UUID other = insertUser("TECHNICIAN");
    UUID inspection = insertInProgressInspection(other, 0);
    UUID item = insertSnapshot(inspection, 0);
    UUID foreignOperation = UUID.randomUUID();
    UUID unknown = UUID.randomUUID();
    String foreignBody = operation(foreignOperation, item, 0, null, "\"valueText\":\"alheia\"");
    String unknownBody = "{\"operationId\":\"" + unknown + "\",\"entityType\":\"EVIDENCE\",\"entityId\":\"" + UUID.randomUUID()
        + "\",\"operationType\":\"UPSERT\",\"baseVersion\":0,\"payload\":{}}";

    mvc.perform(post("/api/v1/mobile/sync/push").with(technician(technician)).contentType("application/json")
            .content(batch(UUID.randomUUID(), foreignBody + "," + unknownBody)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].status").value("REJECTED"))
        .andExpect(jsonPath("$.results[1].status").value("REJECTED"));
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from inspection_response", Integer.class)).isZero();
  }

  @Test
  void missingTokenReturnsUnauthorizedErrorBody() throws Exception {
    mvc.perform(post("/api/v1/mobile/sync/push").contentType("application/json")
            .content("{\"deviceId\":\"" + UUID.randomUUID() + "\",\"operations\":[]}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.path").value("/api/v1/mobile/sync/push"))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void malformedOperationIdIsBadRequest() throws Exception {
    UUID technician = insertUser("TECHNICIAN");
    mvc.perform(post("/api/v1/mobile/sync/push").with(technician(technician)).contentType("application/json")
            .content("{\"deviceId\":\"" + UUID.randomUUID() + "\",\"operations\":[{\"operationId\":\"not-a-uuid\",\"entityType\":\"INSPECTION_RESPONSE\",\"entityId\":\"" + UUID.randomUUID() + "\",\"operationType\":\"UPSERT\"}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.path").value("/api/v1/mobile/sync/push"));
  }

  private static String batch(UUID deviceId, String operations) {
    return "{\"deviceId\":\"" + deviceId + "\",\"lastPullCursor\":\"ignored\",\"operations\":[" + operations + "]}";
  }

  private static String operation(UUID operationId, UUID itemId, int baseVersion, List<UUID> dependencyIds, String payloadFields) {
    String dependencies = dependencyIds == null ? "" : ",\"dependencyIds\":[" + dependencyIds.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElse("") + "]";
    return "{\"operationId\":\"" + operationId + "\",\"entityType\":\"INSPECTION_RESPONSE\",\"entityId\":\"" + UUID.randomUUID()
        + "\",\"operationType\":\"UPSERT\",\"baseVersion\":" + baseVersion + dependencies
        + ",\"payload\":{\"inspectionItemId\":\"" + itemId + "\"," + payloadFields + "}}";
  }

  private UUID insertInProgressInspection(UUID technician, int version) {
    UUID supervisor = insertUser("SUPERVISOR");
    UUID client = UUID.randomUUID();
    UUID site = UUID.randomUUID();
    UUID template = UUID.randomUUID();
    UUID templateVersion = UUID.randomUUID();
    UUID inspection = UUID.randomUUID();
    jdbc.update("insert into client (id,name,status,version) values (?,?,?,0)", client, "Client", "ACTIVE");
    jdbc.update("insert into inspection_site (id,client_id,name,status,version) values (?,?,?,?,0)", site, client, "Site", "ACTIVE");
    jdbc.update("insert into inspection_template (id,title,category,status,version) values (?,?,?,?,0)", template, "Template " + template, "SAFETY", "ACTIVE");
    jdbc.update("insert into inspection_template_version (id,template_id,version_number,title_snapshot,published_at,active_for_new_inspections) values (?,?,?,?,current_timestamp,true)", templateVersion, template, 1, "Published");
    jdbc.update("insert into inspection (id,client_id,site_id,template_version_id,technician_id,supervisor_id,created_by,title,priority,status,scheduled_for,version) values (?,?,?,?,?,?,?,?,?,?,current_timestamp,?)",
        inspection, client, site, templateVersion, technician, supervisor, supervisor, "Inspection", "MEDIUM", "IN_PROGRESS", version);
    return inspection;
  }

  private UUID insertSnapshot(UUID inspectionId, int order) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into inspection_item_snapshot (id,inspection_id,section_title,section_order,item_title,response_type,required,item_order) values (?,?,?,?,?,?,?,?)",
        id, inspectionId, "Secao", 0, "Item " + order, "TEXT_SHORT", false, order);
    return id;
  }

  private UUID insertUser(String role) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into app_user (id,email,name,password_hash,role,status,version) values (?,?,?,?,?,?,0)", id, id + "@test.local", role, "x", role, "ACTIVE");
    return id;
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor technician(UUID id) {
    var principal = new FieldOpsUserPrincipal(id, Set.of(Role.TECHNICIAN), Set.of());
    return authentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))));
  }
}
