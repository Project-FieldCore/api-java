package com.fieldops.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;
  ObjectMapper json = new ObjectMapper();

  @BeforeEach void clean() {
    jdbc.update("delete from refresh_token");
    jdbc.update("delete from app_user");
  }

  @Test
  void adminCanCreateEditListAndInactivateAUserWithoutDeletingHistory() throws Exception {
    String created = mvc.perform(post("/api/v1/users").with(admin()).contentType("application/json")
            .content("{\"name\":\"Ana Silva\",\"email\":\"ana@example.com\",\"password\":\"safe-password\",\"role\":\"TECHNICIAN\",\"phone\":\"11999999999\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value("ana@example.com"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.version").value(0))
        .andReturn().getResponse().getContentAsString();
    UUID id = UUID.fromString(json.readTree(created).get("id").asText());
    String hash = jdbc.queryForObject("select password_hash from app_user where id=?", String.class, id);
    org.assertj.core.api.Assertions.assertThat(passwords.matches("safe-password", hash)).isTrue();

    mvc.perform(patch("/api/v1/users/{userId}", id).with(admin()).contentType("application/json")
            .content("{\"name\":\"Ana Souza\",\"email\":\"ana.souza@example.com\",\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Ana Souza"))
        .andExpect(jsonPath("$.email").value("ana.souza@example.com"))
        .andExpect(jsonPath("$.version").value(1));

    mvc.perform(patch("/api/v1/users/{userId}/status", id).with(admin()).contentType("application/json")
            .content("{\"status\":\"INACTIVE\",\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("INACTIVE"))
        .andExpect(jsonPath("$.version").value(2));

    mvc.perform(get("/api/v1/users/{userId}", id).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("INACTIVE"));
    mvc.perform(get("/api/v1/users").with(admin())).andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(id.toString()));
  }

  @Test
  void duplicateEmailAndStaleVersionUseStandardConflictResponses() throws Exception {
    UUID id = insertUser("existing@example.com", "ACTIVE", 0);
    mvc.perform(post("/api/v1/users").with(admin()).contentType("application/json")
            .content("{\"name\":\"Duplicate\",\"email\":\"existing@example.com\",\"password\":\"safe-password\",\"role\":\"TECHNICIAN\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    mvc.perform(patch("/api/v1/users/{userId}/status", id).with(admin()).contentType("application/json")
            .content("{\"status\":\"INACTIVE\",\"version\":4}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void validationAndMissingUserUseStandardErrorResponses() throws Exception {
    mvc.perform(post("/api/v1/users").with(admin()).contentType("application/json")
            .content("{\"name\":\"Invalid\",\"email\":\"not-an-email\",\"password\":\"safe-password\",\"role\":\"TECHNICIAN\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(get("/api/v1/users/{userId}", UUID.randomUUID()).with(admin()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
  }

  @Test
  void nonAdminCannotManageUsers() throws Exception {
    mvc.perform(get("/api/v1/users").with(user("technician").roles("TECHNICIAN")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  private UUID insertUser(String email, String status, int version) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into app_user (id,email,name,password_hash,role,status,version) values (?,?,?,?,?,?,?)",
        id, email, "Existing user", passwords.encode("safe-password"), "TECHNICIAN", status, version);
    return id;
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
    return user("admin").roles("ADMIN");
  }
}
