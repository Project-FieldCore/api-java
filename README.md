# api-java

## Bootstrap local

Este guia permite iniciar a API localmente sem depender de credenciais reais. O perfil padrão
`dev` usa H2 em memória, aplica as migrations Flyway e cria dados de demonstração.

### 1. Pré-requisitos

- JDK 25 (`java -version`)
- Git
- Não é necessário instalar Maven: o Maven Wrapper está versionado no repositório.
- PostgreSQL 16+ é necessário somente para executar com o profile `postgres`.

### 2. Configurar a variável de ambiente

`JWT_SECRET` é obrigatório e deve conter pelo menos 32 caracteres. Use um valor local, que não
deve ser compartilhado ou enviado ao Git.

**PowerShell (Windows)**

```powershell
$env:JWT_SECRET = 'troque-por-uma-chave-local-com-32-caracteres'
```

**bash (Linux/macOS)**

```bash
export JWT_SECRET='troque-por-uma-chave-local-com-32-caracteres'
```

### 3. Iniciar com H2 (recomendado)

O profile `dev` já é o padrão e não exige banco externo.

```powershell
.\mvnw.cmd spring-boot:run
```

```bash
./mvnw spring-boot:run
```

Após o log indicar que a aplicação iniciou, confira:

| Recurso | Endereço |
| --- | --- |
| Health | http://localhost:8080/actuator/health |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI | http://localhost:8080/v3/api-docs |
| Console H2 (dev) | http://localhost:8080/h2-console |

Para o console H2, use a URL JDBC configurada em `src/main/resources/application-dev.yml`,
usuário `sa` e senha vazia.

### 4. Executar com PostgreSQL

Crie um banco local e defina somente variáveis de ambiente. Os valores abaixo são exemplos;
não use senhas reais ou de ambientes compartilhados.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'postgres'
$env:DATABASE_URL = 'jdbc:postgresql://localhost:5432/fieldops'
$env:DATABASE_USER = 'fieldops'
$env:DATABASE_PASSWORD = 'sua-senha-local'
$env:JWT_SECRET = 'troque-por-uma-chave-local-com-32-caracteres'
.\mvnw.cmd spring-boot:run
```

O Flyway executa as migrations em `src/main/resources/db/migration` automaticamente no startup.

### 5. Validar a instalação

```powershell
.\mvnw.cmd -q test
Invoke-WebRequest http://localhost:8080/actuator/health | Select-Object -Expand Content
```

No Linux/macOS:

```bash
./mvnw -q test
curl http://localhost:8080/actuator/health
```

Para validar as migrations contra PostgreSQL real via Testcontainers/Docker:

```bash
./mvnw -Dtest=PostgresMigrationIT test
```

### Dados de demonstração

No profile `dev`, a aplicação cria dados de demonstração idempotentes. As credenciais locais,
o conteúdo gerado e as instruções de reset estão em [docs/DEMO_SEED.md](docs/DEMO_SEED.md).
Esses usuários existem somente para desenvolvimento e demonstração local.
O roteiro executável de login, execução e revisão está em [docs/DEMO_END_TO_END.md](docs/DEMO_END_TO_END.md).

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

| Endpoint | URL |
|----------|-----|
| Health | http://localhost:8080/actuator/health |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| H2 console | http://localhost:8080/h2-console |

Profile `postgres`: `SPRING_PROFILES_ACTIVE=postgres` (requer `JWT_SECRET` e PostgreSQL).

### Consultar OpenAPI e diagramas

Swagger UI e o OpenAPI gerado pelo springdoc são **públicos** (sem token) nos profiles `dev` e
`postgres`; as rotas protegidas exigem login (`POST /api/v1/auth/login` → **Authorize** na UI).

| Onde | Swagger UI | OpenAPI (JSON) |
|------|------------|----------------|
| Local (`./mvnw spring-boot:run`) | http://localhost:8080/swagger-ui.html | http://localhost:8080/v3/api-docs |
| Container (porta publicada, padrão 8080) | http://localhost:8080/swagger-ui.html | http://localhost:8080/v3/api-docs |
| DEV (EC2 + Nginx) | http://34.196.177.92/swagger-ui.html | http://34.196.177.92/v3/api-docs |

`/swagger-ui.html` redireciona para `/swagger-ui/index.html`. O IP do DEV pode mudar (Learner Lab);
confira o `AWS_HOST` do Environment `development`. O contrato versionado fica em
[`openapi/fieldops-v1.yaml`](openapi/fieldops-v1.yaml); a descrição do Swagger UI também traz os
links abaixo (`OpenApiConfig`).

Diagramas principais (repo [docs](https://github.com/Project-FieldCore/docs)):

- [Diagrama de classes (Anexo D)](https://github.com/Project-FieldCore/docs/blob/main/NOTION/pages/22%20Anexo%20D%20-%20Diagrama%20de%20Classe%203c63130b4ab782fe962c018b0adf27b3.md)
- [Modelo de dados](https://github.com/Project-FieldCore/docs/blob/main/NOTION/pages/10%20-%20Modelo%20de%20Dados%20eb43130b4ab7824f85b881c97289b28f.md)
- Fluxos por papel (Mermaid): [índice](https://github.com/Project-FieldCore/docs/blob/main/FLUXOS/README.md) ·
  [jornada](https://github.com/Project-FieldCore/docs/blob/main/FLUXOS/jornada.md) ·
  [técnico](https://github.com/Project-FieldCore/docs/blob/main/FLUXOS/tecnico.md) ·
  [supervisor](https://github.com/Project-FieldCore/docs/blob/main/FLUXOS/supervisor.md) ·
  [administrador](https://github.com/Project-FieldCore/docs/blob/main/FLUXOS/administrador.md)

Os erros da API seguem o formato padronizado (`timestamp`, `status`, `code`, `message`, `path`,
`requestId`, `fieldErrors`), descrito no OpenAPI.

### Demo seed (`dev` only)

On startup with profile `dev`, `DevDemoSeed` creates (idempotently) admin / supervisor / technician,
cliente→local→equipamento, modelo publicado e inspeções em ASSIGNED / IN_PROGRESS / SUBMITTED / UNDER_REVIEW.

- Password (all demo users): `DemoPass123!`
- Details, credentials table, reset: **[docs/DEMO_SEED.md](docs/DEMO_SEED.md)**

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

A API possui autenticação JWT, migrations Flyway, cadastros, templates e fluxos de inspeção.
Consulte o OpenAPI local para o contrato atualmente disponível e as issues/PRs para o roadmap.

## Git

Segue a convenção do projeto em [BRANCH_COMMIT_CONVENTION.md](https://github.com/Project-FieldCore/docs/blob/main/PROJECT_MANAGEMENT/BRANCH_COMMIT_CONVENTION.md) (branches `tipo/PBI-XXXX-…`, commits `:gitmoji: type: …`, PRs `[PBI-XXXX] …`).
