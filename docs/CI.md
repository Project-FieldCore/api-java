# CI/CD — api-java

Actions pinned to commit SHAs; least-privilege `permissions`.

## Pull Request Validation

Workflow: `.github/workflows/pull-request-validation.yml`  
Name: **0. [Auto] Pull Request Validation**

Runs on pull requests targeting `develop` / `main` (and manual `workflow_dispatch`).

```bash
./mvnw -B verify
```

- JDK **25** (Temurin)
- Maven Wrapper (`./mvnw`) with dependency cache
- Unit/integration tests (H2 by default)
- `PostgresMigrationIT` uses Testcontainers (`postgres:16-alpine`) and is
  annotated `@Testcontainers(disabledWithoutDocker = true)` — skips when Docker
  is unavailable; on GitHub-hosted runners Docker is present

**Branch protection:** require this check on `develop` before merge. Deploy DEV
packages with `-DskipTests` and relies on that gate.

## Deploy DEV (automatic)

Workflow: `.github/workflows/deploy-dev.yml`  
Triggers: `push` to `develop` + `workflow_dispatch`  
Environment: `development`

1. `./mvnw -B clean package -DskipTests`
2. Publish `fieldops-api.jar`
3. SCP/rsync to EC2, patch env, restart systemd, health check

## Deploy Production (manual)

Workflow: `.github/workflows/deploy-production.yml`  
Trigger: `workflow_dispatch` only  
Environment: `production` (separate EC2 — different `AWS_HOST`)

Same shape as DEV; paths/service names **without** `dev-` prefix.

## Docs

- Host bootstrap (JDK, Nginx, systemd, SG, DB options): [DEPLOY_EC2.md](DEPLOY_EC2.md)
- Secrets/variables from scratch: [SECRETS.md](SECRETS.md)

## Run the same checks locally

```bash
./mvnw -B verify
```

Optional: only the PostgreSQL migration IT (needs Docker):

```bash
./mvnw -B -Dtest=PostgresMigrationIT test
```

No secrets are required for PR validation.
