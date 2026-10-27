package com.fieldops.client;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class ClientControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  ObjectMapper json = new ObjectMapper();

  @BeforeEach void clean() {
    jdbc.update("delete from equipment");
    jdbc.update("delete from inspection_site");
    jdbc.update("delete from client");
  }

  @Test
  void supervisorCanCreateUpdateListAndLogicallyInactivateAClient() throws Exception {
    String created = mvc.perform(post("/api/v1/clients").with(supervisor()).contentType("application/json")
            .content("{\"name\":\"Acme\",\"legalName\":\"Acme Ltda\",\"document\":\"12.345.678/0001-00\",\"email\":\"contact@acme.test\",\"phone\":\"11999999999\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Acme"))
        .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.version").value(0))
        .andReturn().getResponse().getContentAsString();
    UUID id = UUID.fromString(json.readTree(created).get("id").asText());

    mvc.perform(patch("/api/v1/clients/{clientId}", id).with(supervisor()).contentType("application/json")
            .content("{\"name\":\"Acme Updated\",\"version\":0}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Acme Updated"))
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(patch("/api/v1/clients/{clientId}/status", id).with(supervisor()).contentType("application/json")
            .content("{\"status\":\"INACTIVE\",\"version\":1}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"))
        .andExpect(jsonPath("$.version").value(2));
    mvc.perform(get("/api/v1/clients").with(supervisor()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].status").value("INACTIVE"));
  }

  @Test
  void duplicateDocumentAndInvalidVersionUseStandardErrors() throws Exception {
    UUID id = insertClient("11.111.111/0001-11", 0);
    mvc.perform(post("/api/v1/clients").with(supervisor()).contentType("application/json")
            .content("{\"name\":\"Duplicate\",\"document\":\"11.111.111/0001-11\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENT_ALREADY_EXISTS"));
    mvc.perform(patch("/api/v1/clients/{clientId}/status", id).with(supervisor()).contentType("application/json")
            .content("{\"status\":\"INACTIVE\",\"version\":3}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void onlyAdministratorsAndSupervisorsCanManageClients() throws Exception {
    mvc.perform(get("/api/v1/clients")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/clients").with(user("technician").roles("TECHNICIAN"))
            .contentType("application/json").content("{\"name\":\"Acme\"}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  private UUID insertClient(String document, int version) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into client (id,name,document,status,version) values (?,?,?,?,?)", id, "Existing", document, "ACTIVE", version);
    return id;
  }
  private static org.springframework.test.web.servlet.request.RequestPostProcessor supervisor() {
    return user("supervisor").roles("SUPERVISOR");
  }
}
