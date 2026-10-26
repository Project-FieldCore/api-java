package com.fieldops.inspection;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fieldops.shared.security.FieldOpsUserPrincipal;
import com.fieldops.shared.security.Role;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
class InspectionControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach void clean() {
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
  void supervisorSchedulesFromPublishedVersionWithRequiredDataAndSnapshot() throws Exception {
    UUID supervisor = insertUser("SUPERVISOR", "ACTIVE");
    UUID technician = insertUser("TECHNICIAN", "ACTIVE");
    UUID client = insertClient();
    UUID site = insertSite(client);
    UUID version = insertPublishedVersion();
    String body = body(version, client, site, technician);

    mvc.perform(post("/api/v1/inspections").with(principal(supervisor, "SUPERVISOR")).contentType("application/json").content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ASSIGNED"))
        .andExpect(jsonPath("$.clientId").value(client.toString()))
        .andExpect(jsonPath("$.siteId").value(site.toString()))
        .andExpect(jsonPath("$.technicianId").value(technician.toString()));
    Integer snapshots = jdbc.queryForObject("select count(*) from inspection_item_snapshot", Integer.class);
    org.assertj.core.api.Assertions.assertThat(snapshots).isEqualTo(1);
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

  private String body(UUID version, UUID client, UUID site, UUID technician) {
    return "{\"templateVersionId\":\"" + version + "\",\"clientId\":\"" + client + "\",\"siteId\":\"" + site + "\",\"technicianId\":\"" + technician + "\",\"scheduledFor\":\"" + OffsetDateTime.now().plusDays(1) + "\"}";
  }
  private UUID insertUser(String role, String status) { UUID id=UUID.randomUUID(); jdbc.update("insert into app_user (id,email,name,password_hash,role,status,version) values (?,?,?,?,?,?,0)", id, id + "@test.local", role, "x", role, status); return id; }
  private UUID insertClient() { UUID id=UUID.randomUUID(); jdbc.update("insert into client (id,name,status,version) values (?,?,?,0)", id, "Client " + id, "ACTIVE"); return id; }
  private UUID insertSite(UUID client) { UUID id=UUID.randomUUID(); jdbc.update("insert into inspection_site (id,client_id,name,status,version) values (?,?,?,?,0)", id, client, "Site " + id, "ACTIVE"); return id; }
  private UUID insertPublishedVersion() { return insertVersion(true); }
  private UUID insertVersion(boolean published) { UUID template=UUID.randomUUID(); UUID version=UUID.randomUUID(); jdbc.update("insert into inspection_template (id,title,category,status,version) values (?,?,?,?,0)", template, "Template " + template, "SAFETY", "ACTIVE"); jdbc.update("insert into inspection_template_version (id,template_id,version_number,title_snapshot,published_at,active_for_new_inspections) values (?,?,?,?,?,?)", version, template, 1, "Published template", published ? java.sql.Timestamp.from(java.time.Instant.now()) : null, published); if (published) { UUID section=UUID.randomUUID(); jdbc.update("insert into template_section (id,template_version_id,title,display_order) values (?,?,?,0)", section, version, "Section"); jdbc.update("insert into template_item (id,section_id,title,response_type,display_order) values (?,?,?,?,0)", UUID.randomUUID(), section, "Item", "BOOLEAN"); } return version; }
  private static org.springframework.test.web.servlet.request.RequestPostProcessor principal(UUID id, String role) { var principal=new FieldOpsUserPrincipal(id, Set.of(Role.valueOf(role)), Set.of()); return authentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))); }
}
