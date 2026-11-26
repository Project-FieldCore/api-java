# API executável por container (demo) — PBI-0070

Imagem Docker da API + `docker-compose.yml` para demonstração local (AC-RELEASE). Não substitui o
deploy DEV/PRD em EC2 (jar + systemd, ver [DEPLOY_EC2.md](DEPLOY_EC2.md)), que continua igual.

## Pré-requisitos

- Docker com Compose v2 (`docker compose version`). Não é preciso JDK/Maven locais: o build roda
  dentro da imagem (`mvnw`, JDK 25).

## Opção A — API + PostgreSQL (docker compose, recomendado)

```bash
cp .env.example .env          # .env não é versionado
docker compose up --build     # ou: docker compose up -d --build
```

O `.env.example` traz **valores de exemplo só para demo** (`JWT_SECRET`, `POSTGRES_PASSWORD`).
**Não os use fora de demo local**; em qualquer outro ambiente gere um novo `JWT_SECRET`
(`openssl rand -base64 48`). Nenhum segredo é gravado na imagem: tudo entra por variável em runtime.

O compose sobe:

| Serviço | Detalhe |
| --- | --- |
| `postgres` | `postgres:16-alpine`, volume `fieldops-pgdata`, healthcheck `pg_isready`, porta **não** publicada no host |
| `api` | build do `Dockerfile`, espera o Postgres ficar saudável, publica `${API_PORT:-8080}` |

As migrations Flyway rodam no startup.

### Endereços (padrão `API_PORT=8080`)

| Recurso | URL |
| --- | --- |
| Health | http://localhost:8080/actuator/health |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI | http://localhost:8080/v3/api-docs |
| Base da API (painel web) | http://localhost:8080/api/v1 |

```bash
curl http://localhost:8080/actuator/health
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ana.silva@example.com","password":"DemoPass123!"}'
```

### Dados de demonstração

`DevDemoSeed` existe somente no profile Spring `dev`. Para a demo ter usuários, o
compose ativa `SPRING_PROFILES_ACTIVE=dev,postgres` (variável `DEMO_PROFILES`): `dev` liga o seed,
`postgres` (listado depois, por isso prevalece) aponta o banco para o PostgreSQL do compose e usa o
CORS de `CORS_ALLOWED_ORIGINS`. O console H2 fica desabilitado.

- Usuários, senha (`DemoPass123!`) e dados criados: [DEMO_SEED.md](DEMO_SEED.md).
  Roteiro ponta a ponta: [DEMO_END_TO_END.md](DEMO_END_TO_END.md).
- O seed é idempotente: reiniciar a API não duplica dados.
- **Comportamento de produção (sem seed, sem usuários):** `DEMO_PROFILES=postgres` no `.env`.
  Nesse caso é necessário criar usuários por outro meio; o seed nunca roda fora de `dev`.

### Operação

```bash
docker compose logs -f api     # logs
docker compose down            # para, mantém o volume do banco
docker compose down -v         # para e apaga o banco (reset da demo)
```

## Opção B — só a API com H2 (`docker run`)

Sem Postgres: profile `dev` padrão (H2 em memória, seed aplicado a cada start).

```bash
docker build -t fieldops-api .
docker run --rm -p 8080:8080 \
  -e JWT_SECRET='demo-only-do-not-use-in-any-real-env-0123456789' \
  fieldops-api
```

## Imagem

- Multi-stage: build com `mvnw` em `eclipse-temurin:25-jdk`; runtime `eclipse-temurin:25-jre-alpine`.
- Executa como usuário não-root (`fieldops`), `EXPOSE 8080`, `HEALTHCHECK` em `/actuator/health`.
- O `Dockerfile` não roda testes (`-DskipTests`); eles rodam no CI (`./mvnw -B verify`).
- JVM: ajuste com `JAVA_OPTS` (padrão `-XX:MaxRAMPercentage=75`).
- Variáveis: `JWT_SECRET` (obrigatória, ≥32), `SPRING_PROFILES_ACTIVE`, `DATABASE_URL`,
  `DATABASE_USER`, `DATABASE_PASSWORD`, `CORS_ALLOWED_ORIGINS`, `SERVER_PORT`
  (ver [SECRETS.md](SECRETS.md)).

## Apontar o painel web (web-react)

1. **CORS na API:** em `.env`, `CORS_ALLOWED_ORIGINS` = origem exata do painel (lista separada por
   vírgula, sem barra final e sem `*`), por exemplo `http://localhost:3000`. Recrie a API:
   `docker compose up -d`. Origem fora da lista recebe `403 Invalid CORS request` no preflight.
2. **Painel:** `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1` (a variável inclui
   `/api/v1`; é lida no **build** do Next.js, então rebuild/restart do painel após alterar).

## Erros

Os erros seguem o formato padronizado já existente da API (`timestamp`, `status`, `code`,
`message`, `path`, `requestId`, `fieldErrors`), por exemplo login inválido:
`401 {"code":"INVALID_CREDENTIALS", ...}`.
