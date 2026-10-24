package com.fieldops.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "fieldops.security.jwt.secret=authorization-matrix-test-signing-key-32")
class AuthorizationMatrixTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;
  ObjectMapper json = new ObjectMapper();

  @BeforeEach void clean() {
    jdbc.update("delete from refresh_token");
    jdbc.update("delete from app_user");
  }

  @Test
  void enforcesRolesAndPreventsNonAdminAccessToUserIdentifiers() throws Exception {
    UUID targetId = createUser("target@example.com", "TECHNICIAN");
    createUser("admin@example.com", "ADMIN");
    createUser("supervisor@example.com", "SUPERVISOR");
    createUser("technician@example.com", "TECHNICIAN");
    createUser("viewer@example.com", "CLIENT_VIEWER");

    mvc.perform(get("/api/v1/users"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    String adminToken = login("admin@example.com");
    mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/users/{userId}", targetId).header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(targetId.toString()));

    assertCannotReadUser("supervisor@example.com", targetId);
    assertCannotReadUser("technician@example.com", targetId);
    assertCannotReadUser("viewer@example.com", targetId);
  }

  @Test
  void everyActiveRoleCanReadOnlyItsOwnAuthenticatedProfile() throws Exception {
    createUser("admin@example.com", "ADMIN");
    createUser("supervisor@example.com", "SUPERVISOR");
    createUser("technician@example.com", "TECHNICIAN");
    createUser("viewer@example.com", "CLIENT_VIEWER");

    assertCanReadProfile("admin@example.com");
    assertCanReadProfile("supervisor@example.com");
    assertCanReadProfile("technician@example.com");
    assertCanReadProfile("viewer@example.com");
  }

  private void assertCannotReadUser(String email, UUID targetId) throws Exception {
    String token = login(email);
    mvc.perform(get("/api/v1/users/{userId}", targetId).header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  private void assertCanReadProfile(String email) throws Exception {
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + login(email)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(email));
  }

  private String login(String email) throws Exception {
    String response = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
            .content("{\"email\":\"" + email + "\",\"password\":\"safe-password\"}"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return json.readTree(response).get("accessToken").asText();
  }

  private UUID createUser(String email, String role) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into app_user (id,email,name,password_hash,role,status) values (?,?,?,?,?,?)",
        id, email, email, passwords.encode("safe-password"), role, "ACTIVE");
    return id;
  }
}
