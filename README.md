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

## Banco de dados e migrations

As migrations Flyway ficam em `src/main/resources/db/migration`. A V2 cria usuários, tokens,
clientes, unidades e equipamentos; a V3 cria templates versionados e a execução de inspeções.

```bash
# testes usuais (H2, incluindo a aplicação das migrations)
./mvnw test

# valida as migrations em PostgreSQL 16 real via Docker/Testcontainers
./mvnw -Dtest=PostgresMigrationIT test
```

O teste de integração verifica a presença das tabelas e as unicidades de e-mail, QR code,
template+versão e `inspection_item_id`.

## Status

Sprint 1 — **scaffold** (security skeleton, Flyway placeholder, OpenAPI skeleton). Auth real e CRUDs a partir da Sprint 2.

## Git

Segue a convenção do projeto em [BRANCH_COMMIT_CONVENTION.md](https://github.com/Project-FieldCore/docs/blob/docs/structure/PROJECT_MANAGEMENT/BRANCH_COMMIT_CONVENTION.md) (branches `tipo/PBI-XXXX-…`, commits `:gitmoji: type: …`, PRs `[PBI-XXXX] …`).
