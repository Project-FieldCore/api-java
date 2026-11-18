# api-java

API REST do **FieldOps** (org FieldCore) — Java 25 + Spring Boot 4.1.

## Documentação

Especificação do produto, gestão e convenções (fonte canônica):

- Hub: [Project-FieldCore/docs](https://github.com/Project-FieldCore/docs)
- Assistentes de IA: [AGENTS.md](https://github.com/Project-FieldCore/docs/blob/main/AGENTS.md) (canônico) · [AGENTS.md](AGENTS.md) (ponteiro local)
- Branches, commits, issues e PRs: [BRANCH_COMMIT_CONVENTION.md](https://github.com/Project-FieldCore/docs/blob/main/PROJECT_MANAGEMENT/BRANCH_COMMIT_CONVENTION.md)
- Backlog / DoD: Notion export em `docs` (páginas 15–18)

## Requisitos locais

- JDK **25**
- Maven 3.6.3+ (ou `./mvnw`)

## CI / CD

- **PR validation** (`develop` / `main`): `./mvnw -B verify` — [docs/CI.md](docs/CI.md)
- **Deploy DEV** (push em `develop`): EC2 + systemd + Nginx — [docs/DEPLOY_EC2.md](docs/DEPLOY_EC2.md)
- **Secrets / Environments**: [docs/SECRETS.md](docs/SECRETS.md)
- **Deploy PRD**: manual (`workflow_dispatch`), outra EC2

```bash
./mvnw -B verify
```

## Executar (dev)

```bash
export JWT_SECRET='local-dev-only-jwt-secret-key-32chars!!'
./mvnw spring-boot:run
```

Todas as rotas de negócio usam o prefixo **`/api/v1`** (ex.: `POST /api/v1/auth/login`).
`JWT_SECRET` é **obrigatório** (≥ 32 caracteres): sem ele a aplicação não sobe. O valor acima é só de
exemplo local — nunca versione ou logue um secret real.

| Endpoint | URL |
|----------|-----|
| Health | http://localhost:8080/actuator/health |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| H2 console | http://localhost:8080/h2-console |

Profile `postgres`: `SPRING_PROFILES_ACTIVE=postgres` (requer `JWT_SECRET` e PostgreSQL).

### Demo seed (`dev` only)

On startup with profile `dev`, `DevDemoSeed` creates (idempotently) admin / supervisor / technician,
cliente→local→equipamento, modelo publicado e inspeções em ASSIGNED / IN_PROGRESS / SUBMITTED / UNDER_REVIEW.

- Password (all demo users): `DemoPass123!`
- Details, credentials table, reset: **[docs/DEMO_SEED.md](docs/DEMO_SEED.md)**

### Smoke test (login)

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ana.silva@example.com","password":"DemoPass123!"}'
```

## Formato padronizado de erro

Toda resposta de erro (4xx/5xx) segue o contrato `ErrorResponse` (Notion doc. 12.2):

```json
{
  "timestamp": "2026-11-18T21:00:00Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Validation failed",
  "path": "/api/v1/auth/login",
  "requestId": "…",
  "fieldErrors": [{ "field": "email", "message": "must not be blank" }]
}
```

`fieldErrors` só vem preenchido em erros de validação (`VALIDATION_ERROR`); nos demais casos é `null`.
Códigos comuns: `UNAUTHORIZED` (401), `FORBIDDEN` (403), `VALIDATION_ERROR` (400). Stacktrace nunca é exposto.
Implementação: `shared/error/GlobalExceptionHandler`.

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

Em desenvolvimento: autenticação JWT, CRUDs (clientes, locais, equipamentos, templates, inspeções), migrations Flyway V2/V3 e seed de demo (`dev`). Contrato: `openapi/fieldops-v1.yaml`.

## Git

Segue a convenção do projeto em [BRANCH_COMMIT_CONVENTION.md](https://github.com/Project-FieldCore/docs/blob/main/PROJECT_MANAGEMENT/BRANCH_COMMIT_CONVENTION.md) (branches `tipo/PBI-XXXX-…`, commits `:gitmoji: type: …`, PRs `[PBI-XXXX] …`).
