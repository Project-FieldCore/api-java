# FieldCore backend

API REST do **FieldOps / FieldCore** — Java 25 + Spring Boot 4.1.

## Documentação

Especificação do projeto (regras de negócio, arquitetura, contrato API, DoR/DoD):

**[`engine/projects/fieldcore/README.md`](../../engine/projects/fieldcore/README.md)** (no workspace Super)

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

Segue [`engine/CONVENTIONS.md`](../../engine/CONVENTIONS.md).
