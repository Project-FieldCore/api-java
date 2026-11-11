# Demo seed (profile `dev`) — PBI-0066

Reproducible local dataset for AC-RELEASE demos (web review, mobile sync). **Never runs outside `dev`.**

## How to enable

```bash
export JWT_SECRET='local-dev-only-jwt-secret-key-32chars!!'
export SPRING_PROFILES_ACTIVE=dev   # default in application.yml
./mvnw spring-boot:run
```

On startup, `DevDemoSeed` inserts missing rows only (idempotent — safe to restart).
Toggle: `fieldops.dev.seed.enabled` (default `true` on `dev`; tests set `false`).

## Credentials (local demo only)

Password for **all** seeded users: `DemoPass123!`

| Email | Role |
|-------|------|
| `admin@fieldcore.com` | ADMIN |
| `ana.silva@example.com` | SUPERVISOR |
| `joao.tecnico@example.com` | TECHNICIAN |

## What appears

| Entity | Stable key / title |
|--------|--------------------|
| Cliente | document `DEMO-CLIENT-001` — Cliente Demo FieldOps |
| Local | `Unidade Demo SP` |
| Equipamento | QR `DEMO-EQ-001` — Bomba Demo |
| Modelo | `Checklist Demo Segurança` (publicado, 2 itens BOOLEAN) |
| Inspeção | `[Demo] Atribuída — sync mobile` → **ASSIGNED** |
| Inspeção | `[Demo] Em execução` → **IN_PROGRESS** |
| Inspeção | `[Demo] Enviada — fila revisão` → **SUBMITTED** (+ respostas) |
| Inspeção | `[Demo] Em revisão` → **UNDER_REVIEW** (+ respostas) |

## Reset

- **H2 (default `dev`)**: stop the process; in-memory DB is wiped on next start (seed re-applies).
- **Postgres**: delete demo rows by keys above, or drop/recreate the schema, then restart with `dev`.

## Verify quickly

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ana.silva@example.com","password":"DemoPass123!"}' | jq -r .accessToken

# use token:
curl -s http://localhost:8080/api/v1/inspections -H "Authorization: Bearer $TOKEN" | jq .
```

## Tests

```bash
./mvnw -B -Dtest=DevDemoSeedTest test
```
