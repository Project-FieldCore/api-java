package com.fieldops.equipment;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

  @BeforeEach void clean() {
    jdbc.update("delete from equipment");
    jdbc.update("delete from inspection_site");
    jdbc.update("delete from client");
  }

  @Test
  void supervisorCanAssignUniqueQrCodeAndFindEquipmentByIt() throws Exception {
    UUID siteId = insertSite();
    UUID equipmentId = insertEquipment(siteId, "QR-OLD");

    mvc.perform(patch("/api/v1/equipment/{equipmentId}/qr-code", equipmentId).with(supervisor())
            .contentType("application/json").content("{\"qrCode\":\"QR-2026-001\",\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.qrCode").value("QR-2026-001"))
        .andExpect(jsonPath("$.version").value(1));

    mvc.perform(get("/api/v1/equipment/by-qr/{qrCode}", "QR-2026-001").with(supervisor()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(equipmentId.toString()))
        .andExpect(jsonPath("$.siteId").value(siteId.toString()));
    mvc.perform(get("/api/v1/equipment/by-qr/{qrCode}", "QR-OLD").with(supervisor()))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
  }

  @Test
  void duplicateQrCodeAndStaleVersionUseStandardConflictResponses() throws Exception {
    UUID siteId = insertSite();
    UUID first = insertEquipment(siteId, "QR-ONE");
    UUID second = insertEquipment(siteId, "QR-TWO");

    mvc.perform(patch("/api/v1/equipment/{equipmentId}/qr-code", second).with(admin())
            .contentType("application/json").content("{\"qrCode\":\"QR-ONE\",\"version\":0}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QR_CODE_ALREADY_EXISTS"));
    mvc.perform(patch("/api/v1/equipment/{equipmentId}/qr-code", first).with(admin())
            .contentType("application/json").content("{\"qrCode\":\"QR-THREE\",\"version\":4}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void databaseConstraintAndEndpointAuthorizationProtectQrLookup() throws Exception {
    UUID siteId = insertSite();
    insertEquipment(siteId, "QR-UNIQUE");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> insertEquipment(siteId, "QR-UNIQUE"))
        .isInstanceOf(DataIntegrityViolationException.class);

    mvc.perform(get("/api/v1/equipment/by-qr/{qrCode}", "QR-UNIQUE"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(get("/api/v1/equipment/by-qr/{qrCode}", "QR-UNIQUE").with(user("tech").roles("TECHNICIAN")))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(get("/api/v1/equipment/by-qr/{qrCode}", "QR-UNIQUE").with(admin()))
        .andExpect(status().isOk());
  }

  private UUID insertSite() {
    UUID clientId = UUID.randomUUID();
    UUID siteId = UUID.randomUUID();
    jdbc.update("insert into client (id,name,status,version) values (?,?,?,?)", clientId, "Client", "ACTIVE", 0);
    jdbc.update("insert into inspection_site (id,client_id,name,status,version) values (?,?,?,?,?)", siteId, clientId, "Site", "ACTIVE", 0);
    return siteId;
  }

  private UUID insertEquipment(UUID siteId, String qrCode) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into equipment (id,site_id,name,qr_code,status,version) values (?,?,?,?,?,?)", id, siteId, "Pump", qrCode, "ACTIVE", 0);
    return id;
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() { return user("admin").roles("ADMIN"); }
  private static org.springframework.test.web.servlet.request.RequestPostProcessor supervisor() { return user("supervisor").roles("SUPERVISOR"); }
}
