package com.fieldops.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
@TestPropertySource(properties = "fieldops.security.jwt.secret=test-signing-key-for-auth-integration-only-32")
class AuthControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;
  ObjectMapper json = new ObjectMapper();

  @BeforeEach void clean() { jdbc.update("delete from refresh_token"); jdbc.update("delete from app_user"); }

  @Test void validLoginAndRefreshRotationWork() throws Exception {
    createUser("active@example.com", "ACTIVE");
    String login = mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"active@example.com\",\"password\":\"correct-password\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty()).andExpect(jsonPath("$.user.role").value("TECHNICIAN")).andReturn().getResponse().getContentAsString();
    String refresh = json.readTree(login).get("refreshToken").asText();
    mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content("{\"refreshToken\":\"" + refresh + "\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").isNotEmpty());
    mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content("{\"refreshToken\":\"" + refresh + "\"}"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
  }

  @Test void invalidAndInactiveLoginsAreDenied() throws Exception {
    createUser("inactive@example.com", "INACTIVE");
    mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"missing@example.com\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"inactive@example.com\",\"password\":\"correct-password\"}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
  }

  private void createUser(String email, String status) {
    jdbc.update("insert into app_user (id,email,name,password_hash,role,status) values (?,?,?,?,?,?)", UUID.randomUUID(), email, "Test User", passwords.encode("correct-password"), "TECHNICIAN", status);
  }
}
