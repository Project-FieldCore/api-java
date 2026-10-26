package com.fieldops.template;

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
class InspectionTemplateControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  ObjectMapper json = new ObjectMapper();

  @BeforeEach void clean() {
    jdbc.update("delete from inspection_template_version");
    jdbc.update("delete from inspection_template");
  }

  @Test
  void supervisorCanCreateAndFreelyEditADraftTemplate() throws Exception {
    String response = mvc.perform(post("/api/v1/inspection-templates").with(supervisor()).contentType("application/json")
            .content("{\"title\":\"Daily Safety\",\"category\":\"SAFETY\",\"description\":\"First draft\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.title").value("Daily Safety"))
        .andExpect(jsonPath("$.category").value("SAFETY"))
        .andExpect(jsonPath("$.version").value(0))
        .andReturn().getResponse().getContentAsString();
    UUID id = UUID.fromString(json.readTree(response).get("id").asText());

    mvc.perform(patch("/api/v1/inspection-templates/{templateId}", id).with(supervisor()).contentType("application/json")
            .content("{\"title\":\"Weekly Safety\",\"category\":\"OPERATIONS\",\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.title").value("Weekly Safety"))
        .andExpect(jsonPath("$.category").value("OPERATIONS"))
        .andExpect(jsonPath("$.version").value(1));

    mvc.perform(get("/api/v1/inspection-templates/{templateId}", id).with(supervisor()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Weekly Safety"));
  }

  @Test
  void validationDuplicateTitleStaleVersionAndNonDraftReturnStandardErrors() throws Exception {
    mvc.perform(post("/api/v1/inspection-templates").with(admin()).contentType("application/json")
            .content("{\"title\":\"Missing category\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    UUID draft = insertTemplate("Existing", "DRAFT", 0);
    mvc.perform(post("/api/v1/inspection-templates").with(admin()).contentType("application/json")
            .content("{\"title\":\"Existing\",\"category\":\"SAFETY\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEMPLATE_TITLE_ALREADY_EXISTS"));
    mvc.perform(patch("/api/v1/inspection-templates/{templateId}", draft).with(admin()).contentType("application/json")
            .content("{\"title\":\"Updated\",\"version\":4}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    UUID active = insertTemplate("Published", "ACTIVE", 0);
    mvc.perform(patch("/api/v1/inspection-templates/{templateId}", active).with(admin()).contentType("application/json")
            .content("{\"title\":\"Should fail\",\"version\":0}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEMPLATE_NOT_EDITABLE"));
  }

  @Test
  void onlyAdministratorsAndSupervisorsCanManageTemplates() throws Exception {
    UUID id = insertTemplate("Draft", "DRAFT", 0);
    mvc.perform(get("/api/v1/inspection-templates/{templateId}", id))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(get("/api/v1/inspection-templates/{templateId}", id).with(user("tech").roles("TECHNICIAN")))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(get("/api/v1/inspection-templates/{templateId}", id).with(admin()))
        .andExpect(status().isOk());
  }

  private UUID insertTemplate(String title, String status, int version) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into inspection_template (id,title,category,status,version) values (?,?,?,?,?)", id, title, "SAFETY", status, version);
    return id;
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() { return user("admin").roles("ADMIN"); }
  private static org.springframework.test.web.servlet.request.RequestPostProcessor supervisor() { return user("supervisor").roles("SUPERVISOR"); }
}
