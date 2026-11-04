package com.fieldops.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
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
  private static final String TEST_JWT_SECRET = "test-signing-key-for-auth-integration-only-32";
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;
  ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void clean() {
    jdbc.update("delete from refresh_token");
    jdbc.update("delete from app_user");
  }

  @Test
  void validLoginAndRefreshRotationWork() throws Exception {
    createUser("active@example.com", "ACTIVE");
    String login =
        mvc.perform(
                post("/api/v1/auth/login")
                    .contentType("application/json")
                    .content(
                        "{\"email\":\"active@example.com\",\"password\":\"correct-password\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.user.role").value("TECHNICIAN"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String refresh = json.readTree(login).get("refreshToken").asText();
    String rotation =
        mvc.perform(
                post("/api/v1/auth/refresh")
                    .header("Authorization", "Bearer " + expiredAccessToken())
                    .contentType("application/json")
                    .content("{\"refreshToken\":\"" + refresh + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String rotatedRefresh = json.readTree(rotation).get("refreshToken").asText();
    mvc.perform(
            post("/api/v1/auth/refresh")
                .contentType("application/json")
                .content("{\"refreshToken\":\"" + refresh + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    mvc.perform(
            post("/api/v1/auth/refresh")
                .contentType("application/json")
                .content("{\"refreshToken\":\"" + rotatedRefresh + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
  }

  @Test
  void invalidAndInactiveLoginsAreDenied() throws Exception {
    createUser("inactive@example.com", "INACTIVE");
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"missing@example.com\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(
                    "{\"email\":\"inactive@example.com\",\"password\":\"correct-password\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
  }

  @Test
  void meReturnsAuthenticatedProfileAndLogoutRevokesRefresh() throws Exception {
    createUser("session@example.com", "ACTIVE");
    String login =
        mvc.perform(
                post("/api/v1/auth/login")
                    .contentType("application/json")
                    .content(
                        "{\"email\":\"session@example.com\",\"password\":\"correct-password\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String access = json.readTree(login).get("accessToken").asText();
    String refresh = json.readTree(login).get("refreshToken").asText();

    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("session@example.com"))
        .andExpect(jsonPath("$.role").value("TECHNICIAN"))
        .andExpect(jsonPath("$.status").doesNotExist());

    mvc.perform(
            post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + access)
                .contentType("application/json")
                .content("{\"refreshToken\":\"" + refresh + "\"}"))
        .andExpect(status().isNoContent());

    mvc.perform(
            post("/api/v1/auth/refresh")
                .contentType("application/json")
                .content("{\"refreshToken\":\"" + refresh + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

    // Access JWT remains valid until TTL (stateless); session renewal via refresh is denied.
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("session@example.com"));
  }

  private void createUser(String email, String status) {
    jdbc.update(
        "insert into app_user (id,email,name,password_hash,role,status) values (?,?,?,?,?,?)",
        UUID.randomUUID(),
        email,
        "Test User",
        passwords.encode("correct-password"),
        "TECHNICIAN",
        status);
  }

  private String expiredAccessToken() {
    return Jwts.builder()
        .subject(UUID.randomUUID().toString())
        .expiration(Date.from(Instant.now().minusSeconds(60)))
        .signWith(Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
        .compact();
  }
}
