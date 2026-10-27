# CI — api-java

GitHub Actions (`.github/workflows/ci.yml`) runs on every pull request and on
pushes to `develop` / `main`. Actions are pinned to commit SHAs; the workflow
uses least-privilege `permissions: contents: read`.

## What it runs

```bash
./mvnw -B verify
```

- JDK **25** (Temurin)
- Maven Wrapper (`./mvnw`) with dependency cache
- Unit/integration tests (H2 by default)
- `PostgresMigrationIT` uses Testcontainers (`postgres:16-alpine`) and is
  annotated `@Testcontainers(disabledWithoutDocker = true)` — skips when Docker
  is unavailable; on GitHub-hosted runners Docker is present

## Run the same checks locally

```bash
# requires JDK 25 on PATH (or JAVA_HOME)
./mvnw -B verify
```

Optional: only the PostgreSQL migration IT (needs Docker):

```bash
./mvnw -B -Dtest=PostgresMigrationIT test
```

No secrets are required for the default pipeline.
