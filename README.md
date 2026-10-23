# api-java

API REST do **FieldOps** (org FieldCore) — Java 25 + Spring Boot 4.1.

## Documentação

Especificação do produto, gestão e convenções (fonte canônica):

- Hub: [Project-FieldCore/docs](https://github.com/Project-FieldCore/docs)
- Branches, commits, issues e PRs: [BRANCH_COMMIT_CONVENTION.md](https://github.com/Project-FieldCore/docs/blob/docs/structure/PROJECT_MANAGEMENT/BRANCH_COMMIT_CONVENTION.md)
- Backlog / DoD: Notion export em `docs` (páginas 15–18)

## Requisitos locais

- JDK **25**
- Maven 3.6.3+ (ou `./mvnw`)

## Executar (dev)

```bash
./mvnw spring-boot:run
```

| Endpoint | URL |
|----------|-----|
| Health | http://localhost:8080/actuator/health |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| H2 console | http://localhost:8080/h2-console |

Profile `postgres`: `SPRING_PROFILES_ACTIVE=postgres` (requer `JWT_SECRET` e PostgreSQL).

## Status

Sprint 1 — **scaffold** (security skeleton, Flyway placeholder, OpenAPI skeleton). Auth real e CRUDs a partir da Sprint 2.

## Git

Segue a convenção do projeto em [BRANCH_COMMIT_CONVENTION.md](https://github.com/Project-FieldCore/docs/blob/docs/structure/PROJECT_MANAGEMENT/BRANCH_COMMIT_CONVENTION.md) (branches `tipo/PBI-XXXX-…`, commits `:gitmoji: type: …`, PRs `[PBI-XXXX] …`).
