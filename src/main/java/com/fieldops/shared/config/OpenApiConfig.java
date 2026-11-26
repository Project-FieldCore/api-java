package com.fieldops.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  /** Contract version (same as {@code openapi/fieldops-v1.yaml}); not the artifact version. */
  static final String API_VERSION = "1.0.0";

  static final String DOCS_HUB = "https://github.com/Project-FieldCore/docs";
  static final String DOCS_MAIN = DOCS_HUB + "/blob/main";
  static final String CLASS_DIAGRAM_URL =
      DOCS_MAIN
          + "/NOTION/pages/22%20Anexo%20D%20-%20Diagrama%20de%20Classe%203c63130b4ab782fe962c018b0adf27b3.md";
  static final String DATA_MODEL_URL =
      DOCS_MAIN + "/NOTION/pages/10%20-%20Modelo%20de%20Dados%20eb43130b4ab7824f85b881c97289b28f.md";
  static final String FLOWS_URL = DOCS_MAIN + "/FLUXOS/README.md";
  static final String FLOW_JOURNEY_URL = DOCS_MAIN + "/FLUXOS/jornada.md";
  static final String FLOW_TECHNICIAN_URL = DOCS_MAIN + "/FLUXOS/tecnico.md";
  static final String FLOW_SUPERVISOR_URL = DOCS_MAIN + "/FLUXOS/supervisor.md";
  static final String FLOW_ADMIN_URL = DOCS_MAIN + "/FLUXOS/administrador.md";

  static final String DESCRIPTION =
      """
      API REST do **FieldOps** (FieldCore): autenticação JWT, cadastros, modelos de checklist, \
      inspeções, revisão e sincronização mobile.

      Erros seguem o formato padronizado (`timestamp`, `status`, `code`, `message`, `path`, \
      `requestId`, `fieldErrors`). Para chamar rotas protegidas, faça `POST /api/v1/auth/login` e \
      use **Authorize** com o `accessToken`.

      ### Diagramas e documentação
      - [Diagrama de classes (Anexo D)](%s)
      - [Modelo de dados](%s)
      - Fluxos por papel: [índice](%s) · [jornada](%s) · [técnico](%s) · [supervisor](%s) \
      · [administrador](%s)
      - [Hub de documentação](%s)
      """
          .formatted(
              CLASS_DIAGRAM_URL,
              DATA_MODEL_URL,
              FLOWS_URL,
              FLOW_JOURNEY_URL,
              FLOW_TECHNICIAN_URL,
              FLOW_SUPERVISOR_URL,
              FLOW_ADMIN_URL,
              DOCS_HUB);

  @Bean
  OpenAPI fieldOpsOpenApi() {
    final String bearer = "bearerAuth";
    return new OpenAPI()
        .info(
            new Info()
                .title("FieldOps API")
                .description(DESCRIPTION)
                .version(API_VERSION)
                .contact(new Contact().name("FieldCore").url(DOCS_HUB)))
        .externalDocs(
            new ExternalDocumentation()
                .description("FieldOps — documentação, diagramas e fluxos por papel")
                .url(DOCS_HUB))
        .addSecurityItem(new SecurityRequirement().addList(bearer))
        .components(
            new Components()
                .addSecuritySchemes(
                    bearer,
                    new SecurityScheme()
                        .name(bearer)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
  }
}
