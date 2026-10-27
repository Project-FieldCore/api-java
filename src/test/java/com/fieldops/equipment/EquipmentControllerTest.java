package com.fieldops.equipment;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class EquipmentControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  ObjectMapper json = new ObjectMapper();

  @BeforeEach void clean() {
    jdbc.update("delete from equipment");
    jdbc.update("delete from inspection_site");
    jdbc.update("delete from client");
  }

  @Test
  void supervisorCanManageEquipmentLinkedToASiteAndLogicallyInactivateIt() throws Exception {
    UUID siteId = insertSite();
    String created = mvc.perform(post("/api/v1/sites/{siteId}/equipment", siteId).with(supervisor())
            .contentType("application/json").content("{\"name\":\"Pump A\",\"qrCode\":\"pump-a-001\",\"serialNumber\":\"SN-1\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.siteId").value(siteId.toString()))
        .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.version").value(0))
        .andReturn().getResponse().getContentAsString();
    UUID id = UUID.fromString(json.readTree(created).get("id").asText());

    mvc.perform(patch("/api/v1/equipment/{equipmentId}", id).with(supervisor()).contentType("application/json")
            .content("{\"name\":\"Pump A2\",\"manufacturer\":\"Acme\",\"version\":0}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Pump A2"))
        .andExpect(jsonPath("$.siteId").value(siteId.toString())).andExpect(jsonPath("$.version").value(1));
    mvc.perform(delete("/api/v1/equipment/{equipmentId}", id).with(supervisor()).contentType("application/json")
            .content("{\"version\":1}"))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/sites/{siteId}/equipment", siteId).with(supervisor()))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()))
        .andExpect(jsonPath("$[0].status").value("INACTIVE"));
  }

  @Test
  void missingSiteDuplicateQrAndStaleVersionUseStandardErrors() throws Exception {
    UUID siteId = insertSite();
    mvc.perform(post("/api/v1/sites/{siteId}/equipment", UUID.randomUUID()).with(admin()).contentType("application/json")
            .content("{\"name\":\"Unknown\",\"qrCode\":\"unknown-01\"}"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SITE_NOT_FOUND"));

    UUID id = insertEquipment(siteId, "pump-01", 0);
    mvc.perform(post("/api/v1/sites/{siteId}/equipment", siteId).with(admin()).contentType("application/json")
            .content("{\"name\":\"Duplicate\",\"qrCode\":\"pump-01\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QR_CODE_ALREADY_EXISTS"));
    mvc.perform(patch("/api/v1/equipment/{equipmentId}/status", id).with(admin()).contentType("application/json")
            .content("{\"status\":\"INACTIVE\",\"version\":3}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void onlyAdministratorsAndSupervisorsCanManageEquipment() throws Exception {
    UUID siteId = insertSite();
    mvc.perform(get("/api/v1/sites/{siteId}/equipment", siteId)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/sites/{siteId}/equipment", siteId).with(user("tech").roles("TECHNICIAN"))
            .contentType("application/json").content("{\"name\":\"Pump\",\"qrCode\":\"pump-01\"}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  private UUID insertSite() {
    UUID clientId = UUID.randomUUID(); UUID siteId = UUID.randomUUID();
    jdbc.update("insert into client (id,name,status,version) values (?,?,?,0)", clientId, "Client", "ACTIVE");
    jdbc.update("insert into inspection_site (id,client_id,name,status,version) values (?,?,?,?,0)", siteId, clientId, "Site", "ACTIVE");
    return siteId;
  }
  private UUID insertEquipment(UUID siteId, String qrCode, int version) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into equipment (id,site_id,name,qr_code,status,version) values (?,?,?,?,?,?)", id, siteId, "Equipment", qrCode, "ACTIVE", version);
    return id;
  }
  private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() { return user("admin").roles("ADMIN"); }
  private static org.springframework.test.web.servlet.request.RequestPostProcessor supervisor() { return user("supervisor").roles("SUPERVISOR"); }
}
