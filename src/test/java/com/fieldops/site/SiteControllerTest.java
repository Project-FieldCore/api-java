package com.fieldops.site;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SiteControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  ObjectMapper json = new ObjectMapper();

  @BeforeEach void clean() {
    jdbc.update("delete from equipment");
    jdbc.update("delete from inspection_site");
    jdbc.update("delete from client");
  }

  @Test
  void supervisorCanCreateEditListAndInactivateClientSiteWithoutChangingItsClient() throws Exception {
    UUID clientId = insertClient("Acme Ltda");
    String response = mvc.perform(post("/api/v1/clients/{clientId}/sites", clientId).with(supervisor())
            .contentType("application/json")
            .content("{\"name\":\"Planta Sul\",\"city\":\"Sorocaba\",\"state\":\"SP\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.clientId").value(clientId.toString()))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.version").value(0))
        .andReturn().getResponse().getContentAsString();
    UUID siteId = UUID.fromString(json.readTree(response).get("id").asText());

    mvc.perform(patch("/api/v1/sites/{siteId}", siteId).with(supervisor()).contentType("application/json")
            .content("{\"name\":\"Planta Sorocaba\",\"postalCode\":\"18000-000\",\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Planta Sorocaba"))
        .andExpect(jsonPath("$.clientId").value(clientId.toString()))
        .andExpect(jsonPath("$.version").value(1));

    mvc.perform(delete("/api/v1/sites/{siteId}", siteId).with(supervisor()).contentType("application/json")
            .content("{\"version\":1}"))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/v1/clients/{clientId}/sites", clientId).with(supervisor()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(siteId.toString()))
        .andExpect(jsonPath("$[0].clientId").value(clientId.toString()))
        .andExpect(jsonPath("$[0].status").value("INACTIVE"));
  }

  @Test
  void missingClientDuplicateNameAndStaleVersionReturnStandardErrors() throws Exception {
    UUID clientId = insertClient("Acme Ltda");
    mvc.perform(post("/api/v1/clients/{clientId}/sites", UUID.randomUUID()).with(admin()).contentType("application/json")
            .content("{\"name\":\"Unknown\"}"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));

    UUID siteId = insertSite(clientId, "Planta Sul", 0);
    mvc.perform(post("/api/v1/clients/{clientId}/sites", clientId).with(admin()).contentType("application/json")
            .content("{\"name\":\"Planta Sul\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SITE_NAME_ALREADY_EXISTS"));
    mvc.perform(patch("/api/v1/sites/{siteId}/status", siteId).with(admin()).contentType("application/json")
            .content("{\"status\":\"INACTIVE\",\"version\":3}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void onlyAdministratorsAndSupervisorsCanManageSites() throws Exception {
    UUID clientId = insertClient("Acme Ltda");
    mvc.perform(get("/api/v1/clients/{clientId}/sites", clientId))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(get("/api/v1/clients/{clientId}/sites", clientId).with(user("tech").roles("TECHNICIAN")))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(get("/api/v1/clients/{clientId}/sites", clientId).with(admin()))
        .andExpect(status().isOk());
  }

  private UUID insertClient(String name) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into client (id,name,status,version) values (?,?,?,?)", id, name, "ACTIVE", 0);
    return id;
  }

  private UUID insertSite(UUID clientId, String name, int version) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into inspection_site (id,client_id,name,status,version) values (?,?,?,?,?)", id, clientId, name, "ACTIVE", version);
    return id;
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
    return user("admin").roles("ADMIN");
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor supervisor() {
    return user("supervisor").roles("SUPERVISOR");
  }
}
