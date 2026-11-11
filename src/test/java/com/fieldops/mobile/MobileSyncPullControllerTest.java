package com.fieldops.mobile;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fieldops.shared.security.FieldOpsUserPrincipal;
import com.fieldops.shared.security.Role;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class MobileSyncPullControllerTest {
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
  void bootstrapEmptyReturnsCursorAndNoChanges() throws Exception {
    UUID technician = insertUser("TECHNICIAN");
    mvc.perform(get("/api/v1/mobile/sync/pull").with(technician(technician)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes").isArray())
        .andExpect(jsonPath("$.changes").isEmpty())
        .andExpect(jsonPath("$.hasMore").value(false))
        .andExpect(jsonPath("$.nextCursor").isString())
        .andExpect(jsonPath("$.serverTime").exists());
  }

  @Test
  @Tag("critical")
  void bootstrapReturnsAssignedInspectionWithItems() throws Exception {
    UUID technician = insertUser("TECHNICIAN");
    UUID inspection = insertAssignedInspection(technician, Instant.parse("2026-08-01T10:00:00.000Z"));
    UUID item = insertSnapshot(inspection, 0);

    mvc.perform(get("/api/v1/mobile/sync/pull").with(technician(technician)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasMore").value(false))
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.changes[0].entityType").value("INSPECTION"))
        .andExpect(jsonPath("$.changes[0].entityId").value(inspection.toString()))
        .andExpect(jsonPath("$.changes[0].operation").value("UPSERT"))
        .andExpect(jsonPath("$.changes[0].payload.id").value(inspection.toString()))
        .andExpect(jsonPath("$.changes[0].payload.status").value("ASSIGNED"))
        .andExpect(jsonPath("$.changes[0].payload.items.length()").value(1))
        .andExpect(jsonPath("$.changes[0].payload.items[0].id").value(item.toString()))
        .andExpect(jsonPath("$.nextCursor").value(Matchers.containsString(inspection.toString())));
  }

  @Test
  void incrementalPullReturnsResponseAndCanceledStatus() throws Exception {
    UUID technician = insertUser("TECHNICIAN");
    Instant base = Instant.parse("2026-08-01T10:00:00.000Z");
    UUID inspection = insertAssignedInspection(technician, base);
    UUID item = insertSnapshot(inspection, 0);

    MvcResult first = mvc.perform(get("/api/v1/mobile/sync/pull").with(technician(technician)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andReturn();
    String cursor = readJson(first).path("nextCursor").asText();

    UUID responseId = UUID.randomUUID();
    Instant responseAt = Instant.parse("2026-08-01T11:00:00.000Z");
    jdbc.update(
        "insert into inspection_response (id,inspection_id,inspection_item_id,value_text,answered_by,server_received_at,updated_at,version) values (?,?,?,?,?,?,?,0)",
        responseId, inspection, item, "alpha", technician, Timestamp.from(responseAt), Timestamp.from(responseAt));
    Instant canceledAt = Instant.parse("2026-08-01T12:00:00.000Z");
    jdbc.update(
        "update inspection set status='CANCELED',canceled_reason='stop',canceled_at=?,updated_at=?,version=version+1 where id=?",
        Timestamp.from(canceledAt), Timestamp.from(canceledAt), inspection);

    mvc.perform(get("/api/v1/mobile/sync/pull").param("cursor", cursor).with(technician(technician)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(2))
        .andExpect(jsonPath("$.changes[0].entityType").value("INSPECTION_RESPONSE"))
        .andExpect(jsonPath("$.changes[0].entityId").value(responseId.toString()))
        .andExpect(jsonPath("$.changes[0].operation").value("UPSERT"))
        .andExpect(jsonPath("$.changes[0].payload.valueText").value("alpha"))
        .andExpect(jsonPath("$.changes[1].entityType").value("INSPECTION"))
        .andExpect(jsonPath("$.changes[1].entityId").value(inspection.toString()))
        .andExpect(jsonPath("$.changes[1].payload.status").value("CANCELED"));
  }

  @Test
  void paginationSetsHasMoreAndSecondPageDrainsRemainder() throws Exception {
    UUID technician = insertUser("TECHNICIAN");
    Instant start = Instant.parse("2026-08-01T10:00:00.000Z");
    UUID firstId = null;
    UUID lastId = null;
    for (int i = 0; i < 101; i++) {
      Instant at = start.plusSeconds(i);
      UUID id = insertAssignedInspection(technician, at);
      insertSnapshot(id, 0);
      if (i == 0) firstId = id;
      if (i == 100) lastId = id;
    }

    MvcResult page1 = mvc.perform(get("/api/v1/mobile/sync/pull").with(technician(technician)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(100))
        .andExpect(jsonPath("$.hasMore").value(true))
        .andExpect(jsonPath("$.changes[0].entityId").value(firstId.toString()))
        .andReturn();
    String cursor = readJson(page1).path("nextCursor").asText();

    mvc.perform(get("/api/v1/mobile/sync/pull").param("cursor", cursor).with(technician(technician)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.hasMore").value(false))
        .andExpect(jsonPath("$.changes[0].entityId").value(lastId.toString()));
  }

  @Test
  void reassignmentEmitsDeleteForPreviousAndUpsertForNewTechnician() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR");
    UUID techA = insertUser("TECHNICIAN");
    UUID techB = insertUser("TECHNICIAN");
    Instant base = Instant.parse("2026-08-01T10:00:00.000Z");
    UUID inspection = insertAssignedInspection(techA, base);
    insertSnapshot(inspection, 0);

    MvcResult bootstrapA = mvc.perform(get("/api/v1/mobile/sync/pull").with(technician(techA)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andReturn();
    String cursorA = readJson(bootstrapA).path("nextCursor").asText();

    mvc.perform(patch("/api/v1/inspections/{id}/assignee", inspection).with(supervisor(supervisor))
            .contentType("application/json")
            .content("{\"technicianId\":\"" + techB + "\",\"version\":0}"))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/mobile/sync/pull").param("cursor", cursorA).with(technician(techA)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.changes[0].entityType").value("INSPECTION"))
        .andExpect(jsonPath("$.changes[0].entityId").value(inspection.toString()))
        .andExpect(jsonPath("$.changes[0].operation").value("DELETE"))
        .andExpect(jsonPath("$.changes[0].payload").value(Matchers.nullValue()));

    mvc.perform(get("/api/v1/mobile/sync/pull").with(technician(techB)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.changes[0].entityType").value("INSPECTION"))
        .andExpect(jsonPath("$.changes[0].entityId").value(inspection.toString()))
        .andExpect(jsonPath("$.changes[0].operation").value("UPSERT"))
        .andExpect(jsonPath("$.changes[0].payload.technicianId").value(techB.toString()));
  }

  @Test
  void cursorFromAnotherTechnicianDoesNotLeakRows() throws Exception {
    UUID techA = insertUser("TECHNICIAN");
    UUID techB = insertUser("TECHNICIAN");
    UUID inspectionB = insertAssignedInspection(techB, Instant.parse("2026-08-01T10:00:00.000Z"));
    insertSnapshot(inspectionB, 0);

    MvcResult pullB = mvc.perform(get("/api/v1/mobile/sync/pull").with(technician(techB)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andReturn();
    String cursorB = readJson(pullB).path("nextCursor").asText();

    mvc.perform(get("/api/v1/mobile/sync/pull").param("cursor", cursorB).with(technician(techA)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes").isEmpty())
        .andExpect(jsonPath("$.hasMore").value(false))
        .andExpect(jsonPath("$.nextCursor").value(cursorB));
  }

  @Test
  void rejectedInspectionPullIncludesMotivoReviewBlock() throws Exception {
    UUID technician = insertUser("TECHNICIAN");
    UUID other = insertUser("TECHNICIAN");
    UUID supervisor = insertUser("SUPERVISOR");
    Instant at = Instant.parse("2026-08-01T16:00:00.000Z");
    UUID inspection = insertAssignedInspection(technician, at);
    insertSnapshot(inspection, 0);
    jdbc.update("update inspection set status='REJECTED',version=3,updated_at=? where id=?", Timestamp.from(at), inspection);
    jdbc.update(
        "insert into inspection_review (id,inspection_id,reviewer_id,decision,comment,created_at) values (?,?,?,?,?,?)",
        UUID.randomUUID(), inspection, supervisor, "REJECTED", "Refazer item de solda com foto", Timestamp.from(at));

    mvc.perform(get("/api/v1/mobile/sync/pull").with(technician(technician)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.changes[0].entityId").value(inspection.toString()))
        .andExpect(jsonPath("$.changes[0].payload.status").value("REJECTED"))
        .andExpect(jsonPath("$.changes[0].payload.review.decision").value("REJECTED"))
        .andExpect(jsonPath("$.changes[0].payload.review.motivo").value("Refazer item de solda com foto"))
        .andExpect(jsonPath("$.changes[0].payload.review.reviewerId").value(supervisor.toString()))
        .andExpect(jsonPath("$.changes[0].payload.review.reviewerName").value("SUPERVISOR"))
        .andExpect(jsonPath("$.changes[0].payload.review.reviewedAt").exists());

    mvc.perform(get("/api/v1/mobile/sync/pull").with(technician(other)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes").isEmpty());
  }

  @Test
  void malformedCursorReturnsValidationError() throws Exception {
    UUID technician = insertUser("TECHNICIAN");
    mvc.perform(get("/api/v1/mobile/sync/pull").param("cursor", "not-a-cursor").with(technician(technician)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.path").value("/api/v1/mobile/sync/pull"));
  }

  @Test
  void missingTokenReturnsUnauthorized() throws Exception {
    mvc.perform(get("/api/v1/mobile/sync/pull"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void quietPollEchoesCursor() throws Exception {
    UUID technician = insertUser("TECHNICIAN");
    String cursor = "2026-08-01T14:15:00.123Z|ab18a638-1c45-4a15-a444-b4235506e912";
    mvc.perform(get("/api/v1/mobile/sync/pull").param("cursor", cursor).with(technician(technician)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes").isEmpty())
        .andExpect(jsonPath("$.hasMore").value(false))
        .andExpect(jsonPath("$.nextCursor").value(cursor));
  }

  private static com.fasterxml.jackson.databind.JsonNode readJson(MvcResult result) throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
        .readTree(result.getResponse().getContentAsString());
  }

  private UUID insertAssignedInspection(UUID technician, Instant updatedAt) {
    UUID supervisor = insertUser("SUPERVISOR");
    UUID client = UUID.randomUUID();
    UUID site = UUID.randomUUID();
    UUID template = UUID.randomUUID();
    UUID templateVersion = UUID.randomUUID();
    UUID inspection = UUID.randomUUID();
    jdbc.update("insert into client (id,name,status,version) values (?,?,?,0)", client, "Client", "ACTIVE");
    jdbc.update("insert into inspection_site (id,client_id,name,status,version) values (?,?,?,?,0)", site, client, "Site", "ACTIVE");
    jdbc.update("insert into inspection_template (id,title,category,status,version) values (?,?,?,?,0)", template, "Template " + template, "SAFETY", "ACTIVE");
    jdbc.update("insert into inspection_template_version (id,template_id,version_number,title_snapshot,published_at,active_for_new_inspections) values (?,?,?,?,current_timestamp,true)",
        templateVersion, template, 1, "Published");
    jdbc.update(
        "insert into inspection (id,client_id,site_id,template_version_id,technician_id,supervisor_id,created_by,title,priority,status,scheduled_for,version,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        inspection, client, site, templateVersion, technician, supervisor, supervisor, "Inspection", "MEDIUM", "ASSIGNED",
        Timestamp.from(updatedAt), 0, Timestamp.from(updatedAt));
    return inspection;
  }

  private UUID insertSnapshot(UUID inspectionId, int order) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into inspection_item_snapshot (id,inspection_id,section_title,section_order,item_title,response_type,required,item_order) values (?,?,?,?,?,?,?,?)",
        id, inspectionId, "Secao", 0, "Item " + order, "TEXT_SHORT", false, order);
    return id;
  }

  private UUID insertUser(String role) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into app_user (id,email,name,password_hash,role,status,version) values (?,?,?,?,?,?,0)",
        id, id + "@test.local", role, "x", role, "ACTIVE");
    return id;
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor technician(UUID id) {
    var principal = new FieldOpsUserPrincipal(id, Set.of(Role.TECHNICIAN), Set.of());
    return authentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))));
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor supervisor(UUID id) {
    var principal = new FieldOpsUserPrincipal(id, Set.of(Role.SUPERVISOR), Set.of());
    return authentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))));
  }
}
