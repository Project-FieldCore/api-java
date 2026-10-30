# Deploy EC2 — FieldOps API (api-java)

Bootstrap de uma EC2 **vazia** para rodar o JAR via **systemd** atrás de **Nginx**.
Sem Docker nesta entrega.

| Ambiente | Prefixo | Exemplo `TARGET_DIR` | Exemplo unit | Gatilho |
| -------- | ------- | -------------------- | ------------ | ------- |
| **DEV** | `dev-` | `/srv/dev-fieldops-api` | `dev-fieldops-api.service` | push/`workflow_dispatch` em `develop` |
| **PRD** | *(nenhum)* | `/srv/fieldops-api` | `fieldops-api.service` | só `workflow_dispatch` (outra EC2) |

Ambientes suportados neste pipeline: **development** e **production** apenas.

O profile Spring em EC2 é sempre **`postgres`**. A app **não sobe** sem `JWT_SECRET` (≥32) e `DATABASE_*` válidos.

Artefato publicado: `fieldops-api.jar` (cópia estável de `target/fieldcore-backend-*.jar`).

---

## 1. Pacotes (Ubuntu 22.04/24.04)

```bash
sudo apt-get update
sudo apt-get install -y nginx curl ca-certificates gnupg unzip

# JDK 25 Temurin
sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update
sudo apt-get install -y temurin-25-jdk
java -version
```

Postgres **na mesma EC2** (opcional — ver seção 9):

```bash
sudo apt-get install -y postgresql postgresql-contrib
```

---

## 2. Usuário SSH e sudo

Use o usuário definido em `AWS_USER` (ex. `ubuntu`). Confirme login por chave e, se o deploy usar `sudo` sem senha para `systemctl`/`tee`/`rsync`:

```bash
# exemplo mínimo — ajuste o usuário
echo 'ubuntu ALL=(ALL) NOPASSWD: /usr/bin/systemctl, /usr/bin/tee, /usr/bin/mkdir, /usr/bin/cp, /usr/bin/mv, /usr/bin/rm, /usr/bin/rsync, /usr/bin/chmod, /usr/bin/chown, /usr/bin/journalctl, /usr/bin/ss' \
  | sudo tee /etc/sudoers.d/fieldops-deploy
sudo chmod 440 /etc/sudoers.d/fieldops-deploy
```

---

## 3. Swap (recomendado em t3.micro)

```bash
sudo fallocate -l 1G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

---

## 4. Pastas e permissões (DEV)

```bash
export TARGET_DIR=/srv/dev-fieldops-api   # PRD: /srv/fieldops-api
sudo mkdir -p "${TARGET_DIR}/logs"
sudo chown -R "$USER:$USER" "${TARGET_DIR}"
```

---

## 5. Unit systemd (exemplo DEV)

Arquivo `/etc/systemd/system/dev-fieldops-api.service` (PRD: `fieldops-api.service` e paths sem `dev-`):

```ini
[Unit]
Description=FieldOps API (DEV)
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/srv/dev-fieldops-api
ExecStart=/usr/bin/java -Xms128m -Xmx384m -Dfile.encoding=UTF-8 -Duser.timezone=America/Sao_Paulo -jar /srv/dev-fieldops-api/fieldops-api.jar
EnvironmentFile=-/srv/dev-fieldops-api/fieldops-api.env
Restart=on-failure
RestartSec=10
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable dev-fieldops-api.service
# start só depois do primeiro deploy (JAR + .env)
```

O workflow grava um drop-in em `/etc/systemd/system/<service>.d/github-actions-deploy.conf` apontando `EnvironmentFile` e `WorkingDirectory`.

Heap sugerido para **t3.micro**: `-Xms128m -Xmx384m`.

---

## 6. Nginx (HTTP agora; TLS depois)

Site exemplo `/etc/nginx/sites-available/fieldops-api` (`proxy_pass` → porta do `SERVER_PORT`, ex. `8080`):

```nginx
server {
    listen 80;
    server_name _;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo ln -sf /etc/nginx/sites-available/fieldops-api /etc/nginx/sites-enabled/fieldops-api
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

---

## 7. Security group

| Porta | Uso |
| ----- | --- |
| 22 | SSH (restrinja ao IP do GitHub Actions / seu bastion se possível) |
| 80 / 443 | HTTP(S) público via Nginx |

A porta do Spring (`SERVER_PORT`) **não** precisa estar aberta na internet — só em `127.0.0.1`.

---

## 8. Checklist GitHub (criar do zero)

Detalhes e valores: [SECRETS.md](SECRETS.md).

1. **Settings → Environments** → criar `development` e (quando houver EC2 PRD) `production`.
2. Em cada Environment, secrets: `AWS_HOST`, `JWT_SECRET`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`.
3. Em cada Environment, variables: `TARGET_DIR`, `SYSTEMD_SERVICE`, `SERVER_PORT`.
4. Repo/org secrets: `AWS_USER`, `AWS_SSH_KEY` (acesso do repositório aos org secrets, se forem de org).
5. Branch protection em `develop`: exigir o check **Pull Request Validation** antes do merge (o deploy DEV usa `-DskipTests` no package).

---

## 9. Banco — ainda indefinido

A aplicação no EC2 usa o profile **`postgres`**. Escolha **uma** opção; preencha `DATABASE_*` de acordo.

### Opção A — Postgres na mesma EC2

```bash
sudo -u postgres psql -c "CREATE USER fieldops WITH PASSWORD 'CHANGE_ME';"
sudo -u postgres psql -c "CREATE DATABASE fieldops OWNER fieldops;"
```

Sugestão de secret:

```text
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/fieldops
DATABASE_USER=fieldops
DATABASE_PASSWORD=CHANGE_ME
```

Garanta que o serviço Postgres inicia antes da API (`After=postgresql.service` na unit, se quiser).

### Opção B — Amazon RDS (PostgreSQL)

1. Crie um RDS PostgreSQL 16 (ou compatível) na mesma VPC / com SG permitindo a EC2 na porta 5432.
2. Secrets:

```text
DATABASE_URL=jdbc:postgresql://<rds-endpoint>:5432/fieldops
DATABASE_USER=fieldops
DATABASE_PASSWORD=<rds-password>
```

Flyway roda na subida da API (`classpath:db/migration`).

---

## 10. Comandos auxiliares

```bash
# SSH
ssh -i ~/.ssh/sua-chave ubuntu@<AWS_HOST>

# Serviço
sudo systemctl status dev-fieldops-api.service
sudo journalctl -u dev-fieldops-api.service -n 80 --no-pager -f
sudo systemctl restart dev-fieldops-api.service

# Health local (mesmo alvo do deploy)
curl -sf http://127.0.0.1:8080/actuator/health && echo OK

# Env gerado pelo patch (não versionar)
sudo ls -la /srv/dev-fieldops-api/
sudo grep -E '^(SPRING_|SERVER_|JWT_|DATABASE_)' /srv/dev-fieldops-api/fieldops-api.env | sed 's/=.*/=***/'

# Nginx
sudo nginx -t
sudo systemctl reload nginx

# Teste SSH a partir da máquina local (simula Actions)
ssh -o BatchMode=yes -o ConnectTimeout=10 ubuntu@<AWS_HOST> 'echo ok && java -version'
```

---

## Workflows

| Arquivo | Quando |
| ------- | ------ |
| `.github/workflows/pull-request-validation.yml` | PR → `develop` / `main` |
| `.github/workflows/deploy-dev.yml` | push → `develop` + manual |
| `.github/workflows/deploy-production.yml` | só manual (`production`) |

Script remoto: `.github/scripts/patch-application-ec2.sh` (grava `fieldops-api.env`, drop-in systemd, restart, health).
