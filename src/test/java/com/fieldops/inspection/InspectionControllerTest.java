package com.fieldops.inspection;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fieldops.shared.security.FieldOpsUserPrincipal;
import com.fieldops.shared.security.Role;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class InspectionControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired EvidenceFileStore evidenceFiles;

  @BeforeEach void clean() {
    clearDatabase();
  }

  @AfterEach void clearDatabase() {
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
  void supervisorSchedulesFromPublishedVersionWithRequiredDataAndSnapshot() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient();
    UUID site = insertSite(client);
    UUID version = insertPublishedVersion();
    String body = body(version, client, site, technician);

    String response = mvc.perform(post("/api/v1/inspections").with(principal(supervisor, "SUPERVISOR")).contentType("application/json").content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ASSIGNED"))
        .andExpect(jsonPath("$.clientId").value(client.toString()))
        .andExpect(jsonPath("$.siteId").value(site.toString()))
        .andExpect(jsonPath("$.technicianId").value(technician.toString())).andReturn().getResponse().getContentAsString();
    UUID inspection = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("id").asText());
    Integer snapshots = jdbc.queryForObject("select count(*) from inspection_item_snapshot", Integer.class);
    org.assertj.core.api.Assertions.assertThat(snapshots).isEqualTo(1);
    String snapshotTitle = jdbc.queryForObject("select item_title from inspection_item_snapshot where inspection_id=?", String.class, inspection);
    String rules = jdbc.queryForObject("select rules_json from inspection_item_snapshot where inspection_id=?", String.class, inspection);
    jdbc.update("update template_item set title='Changed after scheduling'");
    org.assertj.core.api.Assertions.assertThat(snapshotTitle).isEqualTo("Item");
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select item_title from inspection_item_snapshot where inspection_id=?", String.class, inspection)).isEqualTo("Item");
    org.assertj.core.api.Assertions.assertThat(rules).contains("observationRequiredOnFailure");
  }

  @Test
  void priorityDeadlineAndInstructionsArePersistedAndReturnedToTechnician() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient();
    UUID site = insertSite(client);
    UUID version = insertPublishedVersion();
    OffsetDateTime scheduledFor = OffsetDateTime.now().plusDays(2).withNano(0);
    String instructions = "Use EPI e registre fotos do painel.";
    String body = "{\"templateVersionId\":\"" + version + "\",\"clientId\":\"" + client
        + "\",\"siteId\":\"" + site + "\",\"technicianId\":\"" + technician
        + "\",\"scheduledFor\":\"" + scheduledFor + "\",\"priority\":\"HIGH\",\"instructions\":\""
        + instructions + "\"}";

    String response = mvc.perform(post("/api/v1/inspections").with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.priority").value("HIGH"))
        .andExpect(jsonPath("$.instructions").value(instructions))
        .andExpect(jsonPath("$.scheduledFor").value(scheduledFor.toInstant().toString()))
        .andReturn().getResponse().getContentAsString();

    UUID inspectionId = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("id").asText());
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select priority from inspection where id=?", String.class, inspectionId)).isEqualTo("HIGH");
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select instructions from inspection where id=?", String.class, inspectionId)).isEqualTo(instructions);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select scheduled_for from inspection where id=?", java.sql.Timestamp.class, inspectionId).toInstant())
        .isEqualTo(scheduledFor.toInstant());

    mvc.perform(get("/api/v1/inspections/my").with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(inspectionId.toString()))
        .andExpect(jsonPath("$[0].priority").value("HIGH"))
        .andExpect(jsonPath("$[0].instructions").value(instructions))
        .andExpect(jsonPath("$[0].scheduledFor").value(scheduledFor.toInstant().toString()));
  }

  @Test
  @Tag("critical")
  void technicianDownloadsDetailChecklistStartsAndCompletesOnlyWhenRulesPass() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID other = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient();
    UUID site = insertSite(client);
    UUID version = insertPublishedVersion();
    jdbc.update("update template_item set required=true, response_type='CONFORMITY', observation_required_on_failure=true");
    String created = mvc.perform(post("/api/v1/inspections").with(principal(supervisor, "SUPERVISOR")).contentType("application/json").content(body(version, client, site, technician)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    UUID inspection = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).get("id").asText());
    UUID item = jdbc.queryForObject("select id from inspection_item_snapshot where inspection_id=?", UUID.class, inspection);

    mvc.perform(get("/api/v1/inspections/my").with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].clientName").value("Client " + client))
        .andExpect(jsonPath("$[0].siteName").value("Site " + site))
        .andExpect(jsonPath("$[0].status").value("ASSIGNED"));
    mvc.perform(get("/api/v1/inspections/my/{id}", inspection).with(principal(other, "TECHNICIAN")))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INSPECTION_NOT_FOUND"));
    mvc.perform(get("/api/v1/inspections/my/{id}/checklist", inspection).with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].items[0].responseType").value("CONFORMITY"))
        .andExpect(jsonPath("$[0].items[0].required").value(true));

    mvc.perform(post("/api/v1/inspections/my/{id}/start", inspection).with(principal(technician, "TECHNICIAN")).contentType("application/json").content("{\"startedAtDevice\":\"2026-11-05T14:00:00Z\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.startedAt").exists());
    mvc.perform(post("/api/v1/inspections/my/{id}/complete", inspection).with(principal(technician, "TECHNICIAN")).contentType("application/json").content("{\"version\":1}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSPECTION_INCOMPLETE"));
    mvc.perform(post("/api/v1/inspections/my/{id}/complete", inspection).with(principal(technician, "TECHNICIAN")).contentType("application/json").content("{\"version\":1,\"answers\":[{\"itemId\":\"" + item + "\",\"conformity\":\"NON_CONFORMING\"}]}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSPECTION_INCOMPLETE"));
    mvc.perform(put("/api/v1/inspections/my/{id}/items/{itemId}/observation", inspection, item).with(principal(technician, "TECHNICIAN")).contentType("application/json").content("{\"observation\":\"Vazamento na junta\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.observation").value("Vazamento na junta"));
    mvc.perform(post("/api/v1/inspections/my/{id}/complete", inspection).with(principal(technician, "TECHNICIAN")).contentType("application/json").content("{\"version\":1,\"answers\":[{\"itemId\":\"" + item + "\",\"conformity\":\"NON_CONFORMING\",\"observation\":\"Vazamento na junta\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUBMITTED"));
    mvc.perform(get("/api/v1/inspections/my").with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void unpublishedVersionMismatchedSiteAndInactiveTechnicianUseStandardErrors() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID activeTech = insertUser("TECHNICIAN", "ACTIVE");
    UUID inactiveTech = insertUser("TECHNICIAN", "INACTIVE");
    UUID client = insertClient(); UUID otherClient = insertClient(); UUID site = insertSite(client); UUID otherSite = insertSite(otherClient);
    UUID unpublished = insertVersion(false);
    mvc.perform(post("/api/v1/inspections").with(principal(supervisor, "SUPERVISOR")).contentType("application/json").content(body(unpublished, client, site, activeTech)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEMPLATE_VERSION_NOT_PUBLISHED"));
    UUID published = insertPublishedVersion();
    mvc.perform(post("/api/v1/inspections").with(principal(supervisor, "SUPERVISOR")).contentType("application/json").content(body(published, client, otherSite, activeTech)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SITE_CLIENT_MISMATCH"));
    mvc.perform(post("/api/v1/inspections").with(principal(supervisor, "SUPERVISOR")).contentType("application/json").content(body(published, client, site, inactiveTech)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TECHNICIAN_NOT_ACTIVE"));
  }

  @Test
  void schedulingRequiresSupervisorOrAdministrator() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE"); UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion(); String body = body(version, client, site, technician);
    mvc.perform(post("/api/v1/inspections").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/inspections").with(principal(technician, "TECHNICIAN")).contentType("application/json").content(body)).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/inspections").with(principal(supervisor, "SUPERVISOR")).contentType("application/json").content("{\"templateVersionId\":\"" + version + "\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void supervisorCanAssignOnlyActiveTechnicianAndTechnicianSeesOwnAssignments() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID previousTechnician = insertUser("TECHNICIAN", "ACTIVE");
    UUID assignedTechnician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion();
    UUID inspection = insertInspection(version, client, site, previousTechnician, supervisor, "DRAFT", 0);

    mvc.perform(patch("/api/v1/inspections/{inspectionId}/assignee", inspection).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{\"technicianId\":\"" + assignedTechnician + "\",\"version\":0}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ASSIGNED"))
        .andExpect(jsonPath("$.technicianId").value(assignedTechnician.toString())).andExpect(jsonPath("$.version").value(1));
    mvc.perform(get("/api/v1/inspections/my").with(principal(assignedTechnician, "TECHNICIAN")))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(inspection.toString()));
    mvc.perform(get("/api/v1/inspections/my").with(principal(previousTechnician, "TECHNICIAN")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void inactiveTechnicianAndTechnicianAssignmentAttemptAreRejected() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE"); UUID technician = insertUser("TECHNICIAN", "ACTIVE"); UUID inactive = insertUser("TECHNICIAN", "INACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion(); UUID inspection = insertInspection(version, client, site, technician, supervisor, "ASSIGNED", 0);
    mvc.perform(patch("/api/v1/inspections/{inspectionId}/assignee", inspection).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{\"technicianId\":\"" + inactive + "\",\"version\":0}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TECHNICIAN_NOT_ACTIVE"));
    mvc.perform(patch("/api/v1/inspections/{inspectionId}/assignee", inspection).with(principal(technician, "TECHNICIAN"))
            .contentType("application/json").content("{\"technicianId\":\"" + technician + "\",\"version\":0}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void supervisorCancelsInspectionWithReasonAndAuditData() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID templateVersion = insertPublishedVersion();
    UUID inspection = insertInspection(templateVersion, client, site, technician, supervisor, "ASSIGNED", 0);
    String reason = "Acesso ao local foi suspenso pelo cliente.";

    mvc.perform(patch("/api/v1/inspections/{inspectionId}/cancel", inspection).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{\"reason\":\"" + reason + "\",\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELED"))
        .andExpect(jsonPath("$.canceledBy").value(supervisor.toString()))
        .andExpect(jsonPath("$.canceledReason").value(reason))
        .andExpect(jsonPath("$.canceledAt").exists())
        .andExpect(jsonPath("$.version").value(1));

    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select status from inspection where id=?", String.class, inspection))
        .isEqualTo("CANCELED");
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select canceled_by from inspection where id=?", UUID.class, inspection))
        .isEqualTo(supervisor);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select canceled_reason from inspection where id=?", String.class, inspection))
        .isEqualTo(reason);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select canceled_at from inspection where id=?", java.sql.Timestamp.class, inspection))
        .isNotNull();
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select count(*) from audit_event where entity_id=? and action='CANCELED' and actor_id=?",
        Integer.class, inspection, supervisor)).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select payload from audit_event where entity_id=? and action='CANCELED'", String.class, inspection))
        .contains("\"to\":\"CANCELED\"").contains(reason);
  }

  @Test
  @Tag("critical")
  void supervisorListsChronologicalInspectionAuditTrail() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion();
    String scheduleBody = body(version, client, site, technician);

    String created = mvc.perform(post("/api/v1/inspections").with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content(scheduleBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ASSIGNED"))
        .andReturn().getResponse().getContentAsString();
    UUID inspection = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).path("id").asText());

    mvc.perform(patch("/api/v1/inspections/{inspectionId}/cancel", inspection).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{\"reason\":\"Janela cancelada\",\"version\":0}"))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/inspections/{inspectionId}/audit", inspection).with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].action").value("SCHEDULED"))
        .andExpect(jsonPath("$[0].actorId").value(supervisor.toString()))
        .andExpect(jsonPath("$[0].payload").value(org.hamcrest.Matchers.containsString("\"to\":\"ASSIGNED\"")))
        .andExpect(jsonPath("$[0].createdAt").exists())
        .andExpect(jsonPath("$[1].action").value("CANCELED"))
        .andExpect(jsonPath("$[1].payload").value(org.hamcrest.Matchers.containsString("Janela cancelada")));

    mvc.perform(get("/api/v1/inspections/{inspectionId}/audit", inspection).with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(get("/api/v1/inspections/{inspectionId}/audit", UUID.randomUUID()).with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INSPECTION_NOT_FOUND"));
  }

  @Test
  void approvedInspectionCannotBeCanceledAndInvalidReasonUsesStandardError() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID templateVersion = insertPublishedVersion();
    UUID approved = insertInspection(templateVersion, client, site, technician, supervisor, "APPROVED", 0);
    UUID assigned = insertInspection(templateVersion, client, site, technician, supervisor, "ASSIGNED", 0);

    mvc.perform(patch("/api/v1/inspections/{inspectionId}/cancel", approved).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{\"reason\":\"Cancelamento\",\"version\":0}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSPECTION_CANNOT_BE_CANCELED"));
    mvc.perform(patch("/api/v1/inspections/{inspectionId}/cancel", assigned).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{\"reason\":\"   \",\"version\":0}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void supervisorListsInspectionsWithPaginationAndExplicitFilters() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technicianOne = insertUser("TECHNICIAN", "ACTIVE");
    UUID technicianTwo = insertUser("TECHNICIAN", "ACTIVE");
    UUID clientOne = insertClient(); UUID clientTwo = insertClient();
    UUID version = insertPublishedVersion();
    UUID first = insertInspection(version, clientOne, insertSite(clientOne), technicianOne, supervisor, "ASSIGNED", 0);
    UUID second = insertInspection(version, clientOne, insertSite(clientOne), technicianTwo, supervisor, "CANCELED", 0);
    insertInspection(version, clientTwo, insertSite(clientTwo), technicianOne, supervisor, "ASSIGNED", 0);
    OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).withNano(0);
    OffsetDateTime to = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0);

    mvc.perform(get("/api/v1/inspections")
            .param("status", "ASSIGNED").param("clientId", clientOne.toString()).param("technicianId", technicianOne.toString())
            .param("scheduledFrom", from.toString()).param("scheduledTo", to.toString())
            .param("page", "0").param("size", "1").param("sort", "scheduledFor,asc")
            .with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(first.toString()))
        .andExpect(jsonPath("$.page.page").value(0))
        .andExpect(jsonPath("$.page.size").value(1))
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.page.totalPages").value(1));
    org.assertj.core.api.Assertions.assertThat(second).isNotEqualTo(first);
  }

  @Test
  void monitoringRejectsInvalidDateRangeAndTechnicianAccess() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
    OffsetDateTime to = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0);

    mvc.perform(get("/api/v1/inspections").param("scheduledFrom", from.toString()).param("scheduledTo", to.toString())
            .with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    mvc.perform(get("/api/v1/inspections").with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void supervisorReadsChecklistAnswersAggregatedBySection() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion();
    UUID inspection = insertInspection(version, client, site, technician, supervisor, "SUBMITTED", 1);
    UUID item = insertSnapshot(inspection);
    jdbc.update(
        "insert into inspection_response (id,inspection_id,inspection_item_id,value_text,value_number,value_boolean,value_date,conformity,observation,answered_by) values (?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(), inspection, item, "texto", new java.math.BigDecimal("12.50"), true,
        java.sql.Date.valueOf("2026-08-01"), "NON_CONFORMING", "Trinca na base", technician);

    mvc.perform(get("/api/v1/inspections/{inspectionId}/checklist", inspection).with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").exists())
        .andExpect(jsonPath("$[0].items[0].id").value(item.toString()))
        .andExpect(jsonPath("$[0].items[0].valueText").value("texto"))
        .andExpect(jsonPath("$[0].items[0].valueNumber").value(12.50))
        .andExpect(jsonPath("$[0].items[0].valueBoolean").value(true))
        .andExpect(jsonPath("$[0].items[0].valueDate").value("2026-08-01"))
        .andExpect(jsonPath("$[0].items[0].conformity").value("NON_CONFORMING"))
        .andExpect(jsonPath("$[0].items[0].observation").value("Trinca na base"));
    mvc.perform(get("/api/v1/inspections/{inspectionId}/checklist", inspection).with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(get("/api/v1/inspections/{inspectionId}/checklist", UUID.randomUUID()).with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INSPECTION_NOT_FOUND"));
    mvc.perform(get("/api/v1/inspections/{inspectionId}/checklist", inspection))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void supervisorViewsSynchronizedEvidenceAndNonConformities() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion();
    UUID inspection = insertInspection(version, client, site, technician, supervisor, "SUBMITTED", 1);
    UUID item = insertSnapshot(inspection);
    UUID response = UUID.randomUUID(); UUID evidence = UUID.randomUUID(); UUID nonConformity = UUID.randomUUID();
    jdbc.update("insert into inspection_response (id,inspection_id,inspection_item_id,conformity,answered_by) values (?,?,?,?,?)", response, inspection, item, "NON_CONFORMING", technician);
    jdbc.update("insert into evidence (id,inspection_response_id,storage_key,file_name,content_type,size_bytes,created_by) values (?,?,?,?,?,?,?)", evidence, response, "inspections/" + inspection + "/foto.jpg", "foto.jpg", "image/jpeg", 4, technician);
    jdbc.update("insert into non_conformity (id,inspection_id,inspection_response_id,severity,description,status) values (?,?,?,?,?,?)", nonConformity, inspection, response, "HIGH", "Proteção ausente", "OPEN");

    mvc.perform(get("/api/v1/inspections/{inspectionId}/monitoring", inspection).with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inspectionId").value(inspection.toString()))
        .andExpect(jsonPath("$.evidences[0].fileName").value("foto.jpg"))
        .andExpect(jsonPath("$.evidences[0].inspectionItemId").value(item.toString()))
        .andExpect(jsonPath("$.evidences[0].itemTitle").value("Item"))
        .andExpect(jsonPath("$.evidences[0].sectionTitle").value("Secao"))
        .andExpect(jsonPath("$.evidences[0].sectionOrder").value(0))
        .andExpect(jsonPath("$.evidences[0].itemOrder").value(0))
        .andExpect(jsonPath("$.nonConformities[0].severity").value("HIGH"))
        .andExpect(jsonPath("$.nonConformities[0].description").value("Proteção ausente"));
    mvc.perform(get("/api/v1/inspections/{inspectionId}/monitoring", inspection).with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void supervisorViewsDashboardIndicatorsByStatusAndOpenNonConformitySeverity() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion();
    UUID assigned = insertInspection(version, client, site, technician, supervisor, "ASSIGNED", 0);
    UUID submitted = insertInspection(version, client, site, technician, supervisor, "SUBMITTED", 1);
    UUID approved = insertInspection(version, client, site, technician, supervisor, "APPROVED", 2);
    jdbc.update("insert into non_conformity (id,inspection_id,severity,description,status) values (?,?,?,?,?)", UUID.randomUUID(), submitted, "HIGH", "High open", "OPEN");
    jdbc.update("insert into non_conformity (id,inspection_id,severity,description,status) values (?,?,?,?,?)", UUID.randomUUID(), approved, "CRITICAL", "Critical open", "OPEN");
    jdbc.update("insert into non_conformity (id,inspection_id,severity,description,status) values (?,?,?,?,?)", UUID.randomUUID(), assigned, "HIGH", "Resolved", "RESOLVED");

    mvc.perform(get("/api/v1/dashboard/indicators").with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.generatedAt").exists())
        .andExpect(jsonPath("$.inspectionsByStatus.ASSIGNED").value(1))
        .andExpect(jsonPath("$.inspectionsByStatus.SUBMITTED").value(1))
        .andExpect(jsonPath("$.inspectionsByStatus.APPROVED").value(1))
        .andExpect(jsonPath("$.inspectionsByStatus.CANCELED").value(0))
        .andExpect(jsonPath("$.openNonConformitiesBySeverity.HIGH").value(1))
        .andExpect(jsonPath("$.openNonConformitiesBySeverity.CRITICAL").value(1))
        .andExpect(jsonPath("$.openNonConformitiesBySeverity.LOW").value(0));
    mvc.perform(get("/api/v1/dashboard/indicators").with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void supervisorDownloadsEvidenceContentLinkedToInspection() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion();
    UUID inspection = insertInspection(version, client, site, technician, supervisor, "SUBMITTED", 1);
    UUID otherInspection = insertInspection(version, client, site, technician, supervisor, "SUBMITTED", 1);
    UUID item = insertSnapshot(inspection);
    UUID response = UUID.randomUUID(); UUID evidence = UUID.randomUUID();
    byte[] bytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    String storageKey = "inspections/" + inspection + "/" + evidence + ".png";
    evidenceFiles.write(storageKey, bytes);
    jdbc.update("insert into inspection_response (id,inspection_id,inspection_item_id,conformity,answered_by) values (?,?,?,?,?)",
        response, inspection, item, "NON_CONFORMING", technician);
    jdbc.update("insert into evidence (id,inspection_response_id,storage_key,file_name,content_type,size_bytes,created_by) values (?,?,?,?,?,?,?)",
        evidence, response, storageKey, "foto.png", "image/png", bytes.length, technician);

    mvc.perform(get("/api/v1/inspections/{inspectionId}/evidence/{evidenceId}/content", inspection, evidence)
            .with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"))
        .andExpect(header().string("Cache-Control", "private"))
        .andExpect(content().bytes(bytes));
    mvc.perform(get("/api/v1/inspections/{inspectionId}/evidence/{evidenceId}/content", otherInspection, evidence)
            .with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EVIDENCE_NOT_FOUND"));
    mvc.perform(get("/api/v1/inspections/{inspectionId}/evidence/{evidenceId}/content", inspection, evidence)
            .with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void technicianSeesRejectedInspectionWithMotivoOnMyDetailAndList() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID other = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion();
    UUID inspection = insertInspection(version, client, site, technician, supervisor, "REJECTED", 3);
    jdbc.update(
        "insert into inspection_review (id,inspection_id,reviewer_id,decision,comment) values (?,?,?,?,?)",
        UUID.randomUUID(), inspection, supervisor, "REJECTED", "Corrigir NC e reenviar evidências");

    mvc.perform(get("/api/v1/inspections/my/{id}", inspection).with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.review.decision").value("REJECTED"))
        .andExpect(jsonPath("$.review.motivo").value("Corrigir NC e reenviar evidências"))
        .andExpect(jsonPath("$.review.reviewerId").value(supervisor.toString()))
        .andExpect(jsonPath("$.review.reviewerName").value("SUPERVISOR"))
        .andExpect(jsonPath("$.review.reviewedAt").exists());
    mvc.perform(get("/api/v1/inspections/my").with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(inspection.toString()))
        .andExpect(jsonPath("$[0].review.motivo").value("Corrigir NC e reenviar evidências"));
    mvc.perform(get("/api/v1/inspections/my/{id}", inspection).with(principal(other, "TECHNICIAN")))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INSPECTION_NOT_FOUND"));
  }

  @Test
  @Tag("critical")
  void supervisorStartsFormalReviewAndWritesAudit() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion();
    UUID inspection = insertInspection(version, client, site, technician, supervisor, "SUBMITTED", 1);
    jdbc.update("update inspection set submitted_at_server=current_timestamp where id=?", inspection);

    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/start", inspection).with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(inspection.toString()))
        .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
        .andExpect(jsonPath("$.version").value(2));
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select count(*) from audit_event where entity_type='INSPECTION' and entity_id=? and action='REVIEW_STARTED' and actor_id=?",
        Integer.class, inspection, supervisor)).isEqualTo(1);
    mvc.perform(get("/api/v1/inspections/review-queue").with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/start", inspection).with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
        .andExpect(jsonPath("$.version").value(2));
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select count(*) from audit_event where entity_id=? and action='REVIEW_STARTED'", Integer.class, inspection)).isEqualTo(1);

    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/start", inspection).with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

    UUID assigned = insertInspection(version, client, site, technician, supervisor, "ASSIGNED", 0);
    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/start", assigned).with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSPECTION_NOT_REVIEWABLE"));
    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/start", UUID.randomUUID()).with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INSPECTION_NOT_FOUND"));
  }

  @Test
  @Tag("critical")
  void supervisorApprovesInspectionUnderReviewWithOptionalComment() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion();
    UUID inspection = insertInspection(version, client, site, technician, supervisor, "UNDER_REVIEW", 2);
    UUID item = insertSnapshot(inspection);

    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/approve", inspection).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{\"comentario\":\"Aprovado com ressalvas menores\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.version").value(3));
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select decision from inspection_review where inspection_id=?", String.class, inspection)).isEqualTo("APPROVED");
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select reviewer_id from inspection_review where inspection_id=?", UUID.class, inspection)).isEqualTo(supervisor);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select comment from inspection_review where inspection_id=?", String.class, inspection)).isEqualTo("Aprovado com ressalvas menores");
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select approved_at is not null from inspection where id=?", Boolean.class, inspection)).isTrue();
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select count(*) from audit_event where entity_id=? and action='REVIEW_APPROVED' and actor_id=?",
        Integer.class, inspection, supervisor)).isEqualTo(1);

    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/approve", inspection).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.version").value(3));
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select count(*) from inspection_review where inspection_id=?", Integer.class, inspection)).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select count(*) from audit_event where entity_id=? and action='REVIEW_APPROVED'", Integer.class, inspection)).isEqualTo(1);

    mvc.perform(patch("/api/v1/inspections/{inspectionId}/cancel", inspection).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{\"reason\":\"tentativa\",\"version\":3}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSPECTION_CANNOT_BE_CANCELED"));
    mvc.perform(put("/api/v1/inspections/my/{id}/items/{itemId}/observation", inspection, item)
            .with(principal(technician, "TECHNICIAN")).contentType("application/json")
            .content("{\"observation\":\"não deveria gravar\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSPECTION_NOT_IN_PROGRESS"));

    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/approve", inspection).with(principal(technician, "TECHNICIAN"))
            .contentType("application/json").content("{}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    UUID submitted = insertInspection(version, client, site, technician, supervisor, "SUBMITTED", 1);
    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/approve", submitted).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSPECTION_NOT_REVIEWABLE"));
    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/approve", UUID.randomUUID()).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{}"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INSPECTION_NOT_FOUND"));
  }

  @Test
  @Tag("critical")
  void supervisorRejectsInspectionUnderReviewWithRequiredMotivo() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion();
    UUID inspection = insertInspection(version, client, site, technician, supervisor, "UNDER_REVIEW", 2);

    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/reject", inspection).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/reject", inspection).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{\"motivo\":\"   \"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/reject", inspection).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{\"motivo\":\"Checklist incompleto no item de solda\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.version").value(3));
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select decision from inspection_review where inspection_id=?", String.class, inspection)).isEqualTo("REJECTED");
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select reviewer_id from inspection_review where inspection_id=?", UUID.class, inspection)).isEqualTo(supervisor);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select comment from inspection_review where inspection_id=?", String.class, inspection))
        .isEqualTo("Checklist incompleto no item de solda");
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select count(*) from audit_event where entity_id=? and action='REVIEW_REJECTED' and actor_id=?",
        Integer.class, inspection, supervisor)).isEqualTo(1);

    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/reject", inspection).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{\"motivo\":\"outro\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.version").value(3));
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select count(*) from inspection_review where inspection_id=?", Integer.class, inspection)).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select count(*) from audit_event where entity_id=? and action='REVIEW_REJECTED'", Integer.class, inspection)).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
        "select comment from inspection_review where inspection_id=?", String.class, inspection))
        .isEqualTo("Checklist incompleto no item de solda");

    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/reject", inspection).with(principal(technician, "TECHNICIAN"))
            .contentType("application/json").content("{\"motivo\":\"x\"}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    UUID submitted = insertInspection(version, client, site, technician, supervisor, "SUBMITTED", 1);
    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/reject", submitted).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{\"motivo\":\"cedo\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSPECTION_NOT_REVIEWABLE"));
    mvc.perform(post("/api/v1/inspections/{inspectionId}/review/reject", UUID.randomUUID()).with(principal(supervisor, "SUPERVISOR"))
            .contentType("application/json").content("{\"motivo\":\"x\"}"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INSPECTION_NOT_FOUND"));
  }

  @Test
  void supervisorListsOnlySubmittedInspectionsInReviewQueueWithFilters() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE"); UUID otherTechnician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient(); UUID otherClient = insertClient(); UUID site = insertSite(client); UUID otherSite = insertSite(otherClient);
    UUID version = insertPublishedVersion();
    UUID pending = insertInspection(version, client, site, technician, supervisor, "SUBMITTED", 1);
    UUID otherPending = insertInspection(version, otherClient, otherSite, otherTechnician, supervisor, "SUBMITTED", 1);
    insertInspection(version, client, site, technician, supervisor, "IN_PROGRESS", 1);
    jdbc.update("update inspection set submitted_at_server=current_timestamp where id in (?,?)", pending, otherPending);

    mvc.perform(get("/api/v1/inspections/review-queue").param("technicianId", technician.toString()).param("clientId", client.toString())
            .with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(pending.toString()))
        .andExpect(jsonPath("$.items[0].technicianId").value(technician.toString()))
        .andExpect(jsonPath("$.page.totalElements").value(1));
    mvc.perform(get("/api/v1/inspections/review-queue").with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(get("/api/v1/inspections/review-queue").param("submittedFrom", "2026-12-31T00:00:00Z").param("submittedTo", "2026-01-01T00:00:00Z")
            .with(principal(supervisor, "SUPERVISOR")))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
  }

  @Test
  void technicianRegistersNonConformityOnlyWhenTheItemHasEvidence() throws Exception {
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion();
    UUID inspection = insertInspection(version, client, site, technician, supervisor, "IN_PROGRESS", 1);
    UUID item = insertSnapshot(inspection);
    UUID bare = insertSnapshot(inspection);
    UUID response = UUID.randomUUID();
    jdbc.update("insert into inspection_response (id,inspection_id,inspection_item_id,conformity,answered_by) values (?,?,?,?,?)", response, inspection, item, "NON_CONFORMING", technician);
    jdbc.update("insert into evidence (id,inspection_response_id,storage_key,file_name,content_type,size_bytes,created_by) values (?,?,?,?,?,?,?)", UUID.randomUUID(), response, inspection + "/foto.jpg", "foto.jpg", "image/jpeg", 4, technician);

    mvc.perform(post("/api/v1/inspections/my/{id}/non-conformities", inspection).with(principal(technician, "TECHNICIAN"))
            .contentType("application/json").content("{\"itemId\":\"" + item + "\",\"severity\":\"HIGH\",\"description\":\"Proteção ausente\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.severity").value("HIGH")).andExpect(jsonPath("$.status").value("OPEN"));
    mvc.perform(get("/api/v1/inspections/my/{id}/non-conformities", inspection).with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].itemId").value(item.toString()));
    mvc.perform(post("/api/v1/inspections/my/{id}/non-conformities", inspection).with(principal(technician, "TECHNICIAN"))
            .contentType("application/json").content("{\"itemId\":\"" + bare + "\",\"severity\":\"LOW\",\"description\":\"Sem foto\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NC_EVIDENCE_REQUIRED"));
    mvc.perform(post("/api/v1/inspections/my/{id}/non-conformities", inspection).with(principal(technician, "TECHNICIAN"))
            .contentType("application/json").content("{\"itemId\":\"" + item + "\",\"severity\":\"URGENT\",\"description\":\"Proteção ausente\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NC_SEVERITY_INVALID"));
  }

  @Test
  void technicianStoresCoordinatesWhenStartingAndCompleting() throws Exception {
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion();
    UUID inspection = insertInspection(version, client, site, technician, supervisor, "ASSIGNED", 0);
    UUID item = insertSnapshot(inspection);

    mvc.perform(post("/api/v1/inspections/my/{id}/start", inspection).with(principal(technician, "TECHNICIAN"))
            .contentType("application/json").content("{\"startedAtDevice\":\"2026-11-05T14:00:00Z\",\"latitude\":-23.55052,\"longitude\":-46.633308}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select started_latitude from inspection where id=?", java.math.BigDecimal.class, inspection)).isEqualByComparingTo("-23.5505200");

    mvc.perform(post("/api/v1/inspections/my/{id}/start", inspection).with(principal(technician, "TECHNICIAN"))
            .contentType("application/json").content("{\"latitude\":1}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LOCATION_INVALID"));

    mvc.perform(post("/api/v1/inspections/my/{id}/complete", inspection).with(principal(technician, "TECHNICIAN"))
            .contentType("application/json").content("{\"version\":1,\"latitude\":-23.55,\"longitude\":-46.63,\"answers\":[{\"itemId\":\"" + item + "\",\"valueBoolean\":true}]}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select completed_longitude from inspection where id=?", java.math.BigDecimal.class, inspection)).isEqualByComparingTo("-46.6300000");
  }

  @Test
  void technicianAttachesPhotoToTheChecklistItem() throws Exception {
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion();
    UUID inspection = insertInspection(version, client, site, technician, supervisor, "IN_PROGRESS", 1);
    UUID item = insertSnapshot(inspection);
    MockMultipartFile photo = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[] {1, 2, 3, 4});

    mvc.perform(multipart("/api/v1/inspections/my/{inspectionId}/items/{itemId}/evidence", inspection, item)
            .file(photo).with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inspectionItemId").value(item.toString()))
        .andExpect(jsonPath("$.fileName").value("foto.jpg"))
        .andExpect(jsonPath("$.contentType").value("image/jpeg"))
        .andExpect(jsonPath("$.sizeBytes").value(4));
    Integer count = jdbc.queryForObject("select count(*) from evidence e join inspection_response r on r.id=e.inspection_response_id where r.inspection_item_id=?", Integer.class, item);
    org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);

    MockMultipartFile text = new MockMultipartFile("file", "nota.txt", "text/plain", "nao".getBytes());
    mvc.perform(multipart("/api/v1/inspections/my/{inspectionId}/items/{itemId}/evidence", inspection, item)
            .file(text).with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EVIDENCE_INVALID"));
  }

  @Test
  void retryingMutationWithTheSameOperationIdReplaysTheFirstResponse() throws Exception {
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID client = insertClient(); UUID site = insertSite(client); UUID version = insertPublishedVersion();
    UUID inspection = insertInspection(version, client, site, technician, supervisor, "IN_PROGRESS", 1);
    UUID item = insertSnapshot(inspection);
    String operationId = UUID.randomUUID().toString();
    MockMultipartFile photo = new MockMultipartFile("file", "retry.jpg", "image/jpeg", new byte[] {1, 2, 3, 4});
    String first = mvc.perform(multipart("/api/v1/inspections/my/{inspectionId}/items/{itemId}/evidence", inspection, item).file(photo)
            .header("X-Operation-Id", operationId).with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    String second = mvc.perform(multipart("/api/v1/inspections/my/{inspectionId}/items/{itemId}/evidence", inspection, item).file(photo)
            .header("X-Client-Mutation-Id", operationId).with(principal(technician, "TECHNICIAN")))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from evidence", Integer.class)).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from sync_idempotency where user_id=? and idempotency_key=?", Integer.class, technician, operationId)).isEqualTo(1);
  }

  @Test
  void technicianConfirmsEquipmentByQrAndRejectsAMismatch() throws Exception {
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID client = insertClient();
    UUID site = insertSite(client);
    UUID otherSite = insertSite(client);
    UUID version = insertPublishedVersion();
    UUID inspection = insertInspection(version, client, site, technician, supervisor, "ASSIGNED", 0);
    UUID equipment = insertEquipment(site, "QR-PUMP-01", "ACTIVE");
    UUID other = insertEquipment(otherSite, "QR-OTHER", "ACTIVE");
    UUID inactive = insertEquipment(site, "QR-OLD", "INACTIVE");

    mvc.perform(post("/api/v1/inspections/my/{inspectionId}/confirm-equipment", inspection).with(principal(technician, "TECHNICIAN"))
            .contentType("application/json").content("{\"qrCode\":\"QR-PUMP-01\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.equipmentName").value("Pump"))
        .andExpect(jsonPath("$.equipmentConfirmedAt").exists())
        .andExpect(jsonPath("$.version").value(1));
    org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select equipment_id from inspection where id=?", UUID.class, inspection)).isEqualTo(equipment);

    mvc.perform(post("/api/v1/inspections/my/{inspectionId}/confirm-equipment", inspection).with(principal(technician, "TECHNICIAN"))
            .contentType("application/json").content("{\"qrCode\":\"QR-OTHER\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EQUIPMENT_MISMATCH"));
    mvc.perform(post("/api/v1/inspections/my/{inspectionId}/confirm-equipment", inspection).with(principal(technician, "TECHNICIAN"))
            .contentType("application/json").content("{\"qrCode\":\"QR-OLD\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_ACTIVE"));
    mvc.perform(post("/api/v1/inspections/my/{inspectionId}/confirm-equipment", inspection).with(principal(technician, "TECHNICIAN"))
            .contentType("application/json").content("{\"qrCode\":\"missing\"}"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
    org.assertj.core.api.Assertions.assertThat(other).isNotEqualTo(inactive);
  }

  private String body(UUID version, UUID client, UUID site, UUID technician) {
    return "{\"templateVersionId\":\"" + version + "\",\"clientId\":\"" + client + "\",\"siteId\":\"" + site + "\",\"technicianId\":\"" + technician + "\",\"scheduledFor\":\"" + OffsetDateTime.now().plusDays(1) + "\"}";
  }
  private UUID insertUser(String role, String status) { UUID id=UUID.randomUUID(); jdbc.update("insert into app_user (id,email,name,password_hash,role,status,version) values (?,?,?,?,?,?,0)", id, id + "@test.local", role, "x", role, status); return id; }
  private UUID insertClient() { UUID id=UUID.randomUUID(); jdbc.update("insert into client (id,name,status,version) values (?,?,?,0)", id, "Client " + id, "ACTIVE"); return id; }
  private UUID insertSite(UUID client) { UUID id=UUID.randomUUID(); jdbc.update("insert into inspection_site (id,client_id,name,status,version) values (?,?,?,?,0)", id, client, "Site " + id, "ACTIVE"); return id; }
  private UUID insertPublishedVersion() { return insertVersion(true); }
  private UUID insertInspection(UUID version, UUID client, UUID site, UUID technician, UUID supervisor, String status, int versionNumber) { UUID id=UUID.randomUUID(); jdbc.update("insert into inspection (id,client_id,site_id,template_version_id,technician_id,supervisor_id,created_by,title,priority,status,scheduled_for,version) values (?,?,?,?,?,?,?,?,?,?,?,?)", id, client, site, version, technician, supervisor, supervisor, "Inspection", "MEDIUM", status, java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)), versionNumber); return id; }
  private UUID insertEquipment(UUID siteId, String qrCode, String status) { UUID id = UUID.randomUUID(); jdbc.update("insert into equipment (id,site_id,name,qr_code,status) values (?,?,?,?,?)", id, siteId, "Pump", qrCode, status); return id; }
  private UUID insertSnapshot(UUID inspectionId) { UUID id = UUID.randomUUID(); jdbc.update("insert into inspection_item_snapshot (id,inspection_id,section_title,section_order,item_title,response_type,required,item_order) values (?,?,?,?,?,?,?,?)", id, inspectionId, "Secao", 0, "Item", "CONFORMITY", true, 0); return id; }
  private UUID insertVersion(boolean published) { UUID template=UUID.randomUUID(); UUID version=UUID.randomUUID(); jdbc.update("insert into inspection_template (id,title,category,status,version) values (?,?,?,?,0)", template, "Template " + template, "SAFETY", "ACTIVE"); jdbc.update("insert into inspection_template_version (id,template_id,version_number,title_snapshot,published_at,active_for_new_inspections) values (?,?,?,?,?,?)", version, template, 1, "Published template", published ? java.sql.Timestamp.from(java.time.Instant.now()) : null, published); if (published) { UUID section=UUID.randomUUID(); jdbc.update("insert into template_section (id,template_version_id,title,display_order) values (?,?,?,0)", section, version, "Section"); jdbc.update("insert into template_item (id,section_id,title,response_type,display_order) values (?,?,?,?,0)", UUID.randomUUID(), section, "Item", "BOOLEAN"); } return version; }
  private static org.springframework.test.web.servlet.request.RequestPostProcessor principal(UUID id, String role) { var principal=new FieldOpsUserPrincipal(id, Set.of(Role.valueOf(role)), Set.of()); return authentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))); }
}
