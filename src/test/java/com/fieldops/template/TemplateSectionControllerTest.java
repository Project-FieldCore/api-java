package com.fieldops.template;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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

@SpringBootTest @AutoConfigureMockMvc
class TemplateSectionControllerTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; ObjectMapper json=new ObjectMapper();
 @BeforeEach void clean(){jdbc.update("delete from template_item");jdbc.update("delete from template_section");jdbc.update("delete from inspection_template_version");jdbc.update("delete from inspection_template");}
 @Test void supervisorCreatesAndReordersUniqueSections() throws Exception {
  UUID version=version(); UUID first=create(version,"First",0); UUID second=create(version,"Second",1);
  mvc.perform(patch("/api/v1/template-versions/{id}/sections/order",version).with(user("s").roles("SUPERVISOR")).contentType("application/json").content("{\"sections\":[{\"sectionId\":\""+first+"\",\"displayOrder\":1},{\"sectionId\":\""+second+"\",\"displayOrder\":0}]}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(second.toString())).andExpect(jsonPath("$[0].displayOrder").value(0));
  mvc.perform(post("/api/v1/template-versions/{id}/sections",version).with(user("s").roles("SUPERVISOR")).contentType("application/json").content("{\"title\":\"Duplicate\",\"displayOrder\":0}"))
   .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SECTION_ORDER_ALREADY_EXISTS"));
 }
 private UUID create(UUID v,String t,int o)throws Exception {String response=mvc.perform(post("/api/v1/template-versions/{id}/sections",v).with(user("s").roles("SUPERVISOR")).contentType("application/json").content("{\"title\":\""+t+"\",\"displayOrder\":"+o+"}")) .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();return UUID.fromString(json.readTree(response).get("id").asText());}
 private UUID version(){UUID t=UUID.randomUUID(),v=UUID.randomUUID();jdbc.update("insert into inspection_template (id,title,category,status,version) values (?,?,?,?,0)",t,"Template "+t,"SAFETY","DRAFT");jdbc.update("insert into inspection_template_version (id,template_id,version_number,title_snapshot) values (?,?,1,?)",v,t,"Template");return v;}
}
