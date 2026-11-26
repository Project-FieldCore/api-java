package com.fieldops.contract;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Swagger/OpenAPI published by springdoc is public (no token) and links to the main diagrams. */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsEndpointTest {

  private static final String HUB = "https://github.com/Project-FieldCore/docs";

  @Autowired private MockMvc mockMvc;

  @Test
  void apiDocsArePublicAndDescribeTheApiWithDiagramLinks() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("FieldOps API"))
        .andExpect(jsonPath("$.info.version").value("1.0.0"))
        .andExpect(jsonPath("$.info.contact.url").value(HUB))
        .andExpect(jsonPath("$.externalDocs.url").value(HUB))
        .andExpect(jsonPath("$.info.description").value(containsString("Diagrama de classes")))
        .andExpect(jsonPath("$.info.description").value(containsString(HUB + "/blob/main/FLUXOS/README.md")))
        .andExpect(jsonPath("$.info.description").value(containsString("Modelo%20de%20Dados")))
        .andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
  }

  @Test
  void apiDocsDoNotExposeSecretsOrInternalConfiguration() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("jwt.secret"))))
        .andExpect(content().string(not(containsString("jdbc:"))))
        .andExpect(content().string(not(containsString("DATABASE_PASSWORD"))));
  }

  @Test
  void swaggerUiEntryPointIsPublic() throws Exception {
    mockMvc
        .perform(get("/swagger-ui.html"))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location", containsString("/swagger-ui/index.html")));
    mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }
}
