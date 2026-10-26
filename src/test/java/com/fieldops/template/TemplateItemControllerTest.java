package com.fieldops.template;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class TemplateItemControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach void clean() {
    jdbc.update("delete from template_item");
    jdbc.update("delete from template_section");
    jdbc.update("delete from inspection_template_version");
    jdbc.update("delete from inspection_template");
  }

  @Test
  void supervisorCanCreateEverySupportedResponseTypeIncludingSingleChoiceOptions() throws Exception {
    UUID sectionId = insertSection("DRAFT");
    String[] types = {"TEXT_SHORT", "TEXT_LONG", "NUMBER", "BOOLEAN", "CONFORMITY", "SINGLE_CHOICE", "DATE"};
    for (int index = 0; index < types.length; index++) {
      String options = "SINGLE_CHOICE".equals(types[index]) ? ",\"options\":[\"Yes\",\"No\"]" : "";
      mvc.perform(post("/api/v1/template-sections/{sectionId}/items", sectionId).with(supervisor())
              .contentType("application/json").content("{\"title\":\"Item " + index + "\",\"responseType\":\"" + types[index]
                  + "\",\"displayOrder\":" + index + options + "}"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.responseType").value(types[index]));
    }
    mvc.perform(get("/api/v1/template-sections/{sectionId}/items", sectionId).with(supervisor()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(7))
        .andExpect(jsonPath("$[5].optionsJson").value("[\"Yes\",\"No\"]"));
  }

  @Test
  void missingTypeInvalidOptionsAndDuplicateOrderReturnStandardErrors() throws Exception {
    UUID sectionId = insertSection("DRAFT");
    mvc.perform(post("/api/v1/template-sections/{sectionId}/items", sectionId).with(admin()).contentType("application/json")
            .content("{\"title\":\"Without type\",\"displayOrder\":0}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(post("/api/v1/template-sections/{sectionId}/items", sectionId).with(admin()).contentType("application/json")
            .content("{\"title\":\"Choice\",\"responseType\":\"SINGLE_CHOICE\",\"displayOrder\":0}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESPONSE_OPTIONS"));
    mvc.perform(post("/api/v1/template-sections/{sectionId}/items", sectionId).with(admin()).contentType("application/json")
            .content("{\"title\":\"Text\",\"responseType\":\"TEXT_SHORT\",\"displayOrder\":0,\"options\":[\"Unexpected\"]}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESPONSE_OPTIONS"));
    mvc.perform(post("/api/v1/template-sections/{sectionId}/items", sectionId).with(admin()).contentType("application/json")
            .content("{\"title\":\"First\",\"responseType\":\"TEXT_SHORT\",\"displayOrder\":1}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/api/v1/template-sections/{sectionId}/items", sectionId).with(admin()).contentType("application/json")
            .content("{\"title\":\"Duplicate order\",\"responseType\":\"DATE\",\"displayOrder\":1}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ITEM_ORDER_ALREADY_EXISTS"));
  }

  @Test
  void onlyAdministratorsAndSupervisorsCanCreateItemsAndOnlyDraftsAreEditable() throws Exception {
    UUID draftSection = insertSection("DRAFT");
    UUID activeSection = insertSection("ACTIVE");
    String request = "{\"title\":\"Item\",\"responseType\":\"BOOLEAN\",\"displayOrder\":0}";
    mvc.perform(post("/api/v1/template-sections/{sectionId}/items", draftSection).contentType("application/json").content(request))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(post("/api/v1/template-sections/{sectionId}/items", draftSection).with(user("tech").roles("TECHNICIAN"))
            .contentType("application/json").content(request))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(post("/api/v1/template-sections/{sectionId}/items", activeSection).with(supervisor())
            .contentType("application/json").content(request))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEMPLATE_NOT_EDITABLE"));
  }

  private UUID insertSection(String templateStatus) {
    UUID templateId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID sectionId = UUID.randomUUID();
    jdbc.update("insert into inspection_template (id,title,category,status,version) values (?,?,?,?,?)", templateId,
        "Template " + templateId, "SAFETY", templateStatus, 0);
    jdbc.update("insert into inspection_template_version (id,template_id,version_number,title_snapshot) values (?,?,?,?)",
        versionId, templateId, 1, "Template version");
    jdbc.update("insert into template_section (id,template_version_id,title,display_order) values (?,?,?,?)",
        sectionId, versionId, "Section", 0);
    return sectionId;
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() { return user("admin").roles("ADMIN"); }
  private static org.springframework.test.web.servlet.request.RequestPostProcessor supervisor() { return user("supervisor").roles("SUPERVISOR"); }
}
