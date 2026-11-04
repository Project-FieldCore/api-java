# Secrets e variáveis — FieldOps API (api-java)

Criar **do zero** no GitHub. Nada sensível deve ser commitado.

## Visão geral

| Camada | Onde no GitHub | Uso |
| ------ | -------------- | --- |
| Sensível (por ambiente) | Environment secrets | `JWT_SECRET`, `DATABASE_*`, `AWS_HOST` |
| Não sensível (por ambiente) | Environment variables | `TARGET_DIR`, `SYSTEMD_SERVICE`, `SERVER_PORT` |
| Compartilhado | Repository ou Organization secrets | `AWS_USER`, `AWS_SSH_KEY` |

Mesmos **nomes** em `development` e `production`; **valores** diferentes (EC2 e DB distintos).

Bootstrap completo do host: [DEPLOY_EC2.md](DEPLOY_EC2.md).

---

## Passo a passo — criar do zero

### 1. Environments

**Settings → Environments** → New environment:

1. `development` (obrigatório para deploy DEV)
2. `production` (quando a EC2 PRD existir; recomendado: required reviewers)

### 2. Secrets do Environment `development`

| Secret | Como obter / exemplo |
| ------ | -------------------- |
| `AWS_HOST` | IP ou DNS público/privado da EC2 DEV |
| `JWT_SECRET` | `openssl rand -base64 48` (resultado com ≥32 caracteres) |
| `DATABASE_URL` | JDBC Postgres, ex. `jdbc:postgresql://127.0.0.1:5432/fieldops` ou endpoint RDS |
| `DATABASE_USER` | Usuário do banco |
| `DATABASE_PASSWORD` | Senha do banco |

Repita no Environment `production` com a **outra** EC2 e o DB de produção.

### 3. Variables do Environment `development`

| Variable | Exemplo DEV | Exemplo PRD |
| -------- | ----------- | ----------- |
| `TARGET_DIR` | `/srv/dev-fieldops-api` | `/srv/fieldops-api` |
| `SYSTEMD_SERVICE` | `dev-fieldops-api.service` | `fieldops-api.service` |
| `SERVER_PORT` | `8080` | `8080` |

### 4. Secrets de repositório / organização

| Secret | Uso |
| ------ | --- |
| `AWS_USER` | Usuário SSH (ex. `ubuntu`) |
| `AWS_SSH_KEY` | Chave privada PEM completa (inclui `BEGIN`/`END`) |

Se forem Organization secrets, libere **Repository access** para `Project-FieldCore/api-java`.

### 5. Gerar `JWT_SECRET`

```bash
openssl rand -base64 48
```

Cole o valor no Environment secret `JWT_SECRET`. O deploy e o `patch-application-ec2.sh` rejeitam secrets com menos de 32 caracteres.

---

## Runtime no EC2 (`fieldops-api.env`)

O script `.github/scripts/patch-application-ec2.sh` grava (permissão `600`):

| Variável | Origem |
| -------- | ------ |
| `SPRING_PROFILES_ACTIVE` | fixo `postgres` no workflow |
| `SERVER_PORT` | `vars.SERVER_PORT` |
| `JWT_SECRET` | secret (via base64 no SSH) |
| `DATABASE_URL` | secret |
| `DATABASE_USER` | secret |
| `DATABASE_PASSWORD` | secret |

Mapeamento Spring (já no repo):

- `JWT_SECRET` → `fieldops.security.jwt.secret`
- `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` → datasource (`application-postgres.yml`)
- `SERVER_PORT` → `server.port`

Secrets sensíveis passam pelo runner em base64 para o SSH (caracteres especiais seguros).

---

## Workflows

| Workflow | Environment | Secrets necessários |
| -------- | ----------- | ------------------- |
| `pull-request-validation.yml` | — | nenhum |
| `deploy-dev.yml` | `development` | todos acima + `AWS_USER` / `AWS_SSH_KEY` |
| `deploy-production.yml` | `production` | idem (outro `AWS_HOST`) |

Deploy DEV faz `./mvnw -B clean package -DskipTests` porque o gate de testes é o PR validation — mantenha branch protection em `develop` exigindo esse check.

---

## Local (sem secrets de EC2)

```bash
export JWT_SECRET='local-dev-only-jwt-secret-key-32chars!!'
export SERVER_PORT=8081
./mvnw spring-boot:run
```

Profile default local: `dev` (H2). Profile EC2: `postgres`.
