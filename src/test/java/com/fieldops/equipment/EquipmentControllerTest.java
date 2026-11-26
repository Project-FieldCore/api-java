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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class EquipmentControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void clean() {
    jdbc.update("delete from equipment");
    jdbc.update("delete from inspection_site");
    jdbc.update("delete from client");
  }

  @Test
  void supervisorCanManageEquipmentLinkedToASiteAndLogicallyInactivateIt() throws Exception {
    UUID siteId = insertSite();
    String created =
        mvc.perform(
                post("/api/v1/sites/{siteId}/equipment", siteId)
                    .with(supervisor())
                    .contentType("application/json")
                    .content("{\"name\":\"Pump A\",\"qrCode\":\"pump-a-001\",\"serialNumber\":\"SN-1\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.qrCode").value(org.hamcrest.Matchers.startsWith("fieldops://equipment/")))
            .andExpect(jsonPath("$.qrCode").value(org.hamcrest.Matchers.not("pump-a-001")))
            .andExpect(jsonPath("$.siteId").value(siteId.toString()))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.version").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(json.readTree(created).get("id").asText());
    String qrCode = json.readTree(created).get("qrCode").asText();
    org.assertj.core.api.Assertions.assertThat(qrCode).isEqualTo("fieldops://equipment/" + id);

    mvc.perform(
            patch("/api/v1/equipment/{equipmentId}", id)
                .with(supervisor())
                .contentType("application/json")
                .content("{\"name\":\"Pump A2\",\"qrCode\":\"hijack\",\"manufacturer\":\"Acme\",\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.qrCode").value(qrCode))
        .andExpect(jsonPath("$.name").value("Pump A2"))
        .andExpect(jsonPath("$.siteId").value(siteId.toString()))
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(
            delete("/api/v1/equipment/{equipmentId}", id)
                .with(supervisor())
                .contentType("application/json")
                .content("{\"version\":1}"))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/sites/{siteId}/equipment", siteId).with(supervisor()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id.toString()))
        .andExpect(jsonPath("$[0].status").value("INACTIVE"));
  }

  @Test
  void missingSiteAndStaleVersionUseStandardErrors() throws Exception {
    UUID siteId = insertSite();
    mvc.perform(
            post("/api/v1/sites/{siteId}/equipment", UUID.randomUUID())
                .with(admin())
                .contentType("application/json")
                .content("{\"name\":\"Unknown\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SITE_NOT_FOUND"));

    UUID id = insertEquipment(siteId, "pump-01", 0);
    mvc.perform(
            patch("/api/v1/equipment/{equipmentId}/status", id)
                .with(admin())
                .contentType("application/json")
                .content("{\"status\":\"INACTIVE\",\"version\":3}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void eachCreatedEquipmentGetsItsOwnGeneratedQrCodeAndCanBeFoundByIt() throws Exception {
    UUID siteId = insertSite();
    UUID first = createEquipment(siteId, "Pump 1");
    UUID second = createEquipment(siteId, "Pump 2");

    for (UUID id : new UUID[] {first, second}) {
      mvc.perform(get("/api/v1/equipment/by-qr").param("qrCode", "fieldops://equipment/" + id).with(supervisor()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(id.toString()))
          .andExpect(jsonPath("$.siteId").value(siteId.toString()))
          .andExpect(jsonPath("$.qrCode").value("fieldops://equipment/" + id));
    }
    mvc.perform(get("/api/v1/equipment/by-qr").param("qrCode", "QR-UNKNOWN").with(supervisor()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
  }

  @Test
  void databaseConstraintAndEndpointAuthorizationProtectQrLookup() throws Exception {
    UUID siteId = insertSite();
    insertEquipment(siteId, "QR-UNIQUE", 0);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> insertEquipment(siteId, "QR-UNIQUE", 0))
        .isInstanceOf(DataIntegrityViolationException.class);

    mvc.perform(get("/api/v1/equipment/by-qr/{qrCode}", "QR-UNIQUE"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(get("/api/v1/equipment/by-qr/{qrCode}", "QR-UNIQUE").with(user("tech").roles("TECHNICIAN")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(get("/api/v1/equipment/by-qr/{qrCode}", "QR-UNIQUE").with(user("viewer").roles("CLIENT_VIEWER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(get("/api/v1/equipment/by-qr").param("qrCode", "QR-UNIQUE"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/equipment/by-qr").param("qrCode", "QR-UNIQUE").with(user("tech").roles("TECHNICIAN")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(get("/api/v1/equipment/by-qr/{qrCode}", "QR-UNIQUE").with(admin()))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/equipment/by-qr").param("qrCode", "QR-UNIQUE").with(admin()))
        .andExpect(status().isOk());
  }

  @Test
  void onlyAdministratorsAndSupervisorsCanManageEquipment() throws Exception {
    UUID siteId = insertSite();
    mvc.perform(get("/api/v1/sites/{siteId}/equipment", siteId)).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/sites/{siteId}/equipment", siteId)
                .with(user("tech").roles("TECHNICIAN"))
                .contentType("application/json")
                .content("{\"name\":\"Pump\",\"qrCode\":\"pump-01\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(
            post("/api/v1/sites/{siteId}/equipment", siteId)
                .with(user("viewer").roles("CLIENT_VIEWER"))
                .contentType("application/json")
                .content("{\"name\":\"Pump\",\"qrCode\":\"pump-02\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  private UUID createEquipment(UUID siteId, String name) throws Exception {
    String created =
        mvc.perform(
                post("/api/v1/sites/{siteId}/equipment", siteId)
                    .with(supervisor())
                    .contentType("application/json")
                    .content("{\"name\":\"" + name + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(created).get("id").asText());
  }

  private UUID insertSite() {
    UUID clientId = UUID.randomUUID();
    UUID siteId = UUID.randomUUID();
    jdbc.update("insert into client (id,name,status,version) values (?,?,?,?)", clientId, "Client", "ACTIVE", 0);
    jdbc.update(
        "insert into inspection_site (id,client_id,name,status,version) values (?,?,?,?,?)",
        siteId, clientId, "Site", "ACTIVE", 0);
    return siteId;
  }

  private UUID insertEquipment(UUID siteId, String qrCode, int version) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into equipment (id,site_id,name,qr_code,status,version) values (?,?,?,?,?,?)",
        id, siteId, "Pump", qrCode, "ACTIVE", version);
    return id;
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
    return user("admin").roles("ADMIN");
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor supervisor() {
    return user("supervisor").roles("SUPERVISOR");
  }
}
