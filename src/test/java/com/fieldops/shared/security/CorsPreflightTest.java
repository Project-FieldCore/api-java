package com.fieldops.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties =
        "fieldops.security.cors.allowed-origins=http://18.205.200.49,http://localhost:3000")
class CorsPreflightTest {

  private static final String LOGIN = "/api/v1/auth/login";

  @Autowired private MockMvc mockMvc;

  @Test
  void preflightFromConfiguredOriginIsAllowed() throws Exception {
    mockMvc
        .perform(
            options(LOGIN)
                .header("Origin", "http://18.205.200.49")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,authorization"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://18.205.200.49"));
  }

  @Test
  void preflightFromSecondCommaSeparatedOriginIsAllowed() throws Exception {
    mockMvc
        .perform(
            options(LOGIN)
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
  }

  @Test
  void preflightFromUnknownOriginIsRejected() throws Exception {
    mockMvc
        .perform(
            options(LOGIN)
                .header("Origin", "http://evil.example.com")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
  }
}
