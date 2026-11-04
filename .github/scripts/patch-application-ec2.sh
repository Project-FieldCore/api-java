#!/usr/bin/env bash
# Applies GitHub Environment secrets on the EC2 host and restarts the FieldOps API systemd unit.
set -euo pipefail

DEPLOY_TARGET="${DEPLOY_TARGET:?DEPLOY_TARGET is required (vars.TARGET_DIR)}"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:?SPRING_PROFILES_ACTIVE is required}"
SERVER_PORT="${SERVER_PORT:-8080}"
ENV_FILE_NAME="fieldops-api.env"

decode_b64_var() {
  local target_var="$1"
  local b64_var="${target_var}_B64"
  local b64="${!b64_var:-}"
  if [ -z "$b64" ]; then
    return 0
  fi
  local decoded
  if decoded="$(printf '%s' "$b64" | base64 -d 2>/dev/null)"; then
    :
  else
    decoded="$(printf '%s' "$b64" | base64 -D)"
  fi
  printf -v "$target_var" '%s' "$decoded"
}

decode_b64_var JWT_SECRET
decode_b64_var DATABASE_URL
decode_b64_var DATABASE_USER
decode_b64_var DATABASE_PASSWORD

echo "PATCH started: DEPLOY_TARGET=${DEPLOY_TARGET} SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE} SERVER_PORT=${SERVER_PORT}"

write_env_file() {
  local env_file="${DEPLOY_TARGET}/${ENV_FILE_NAME}"
  {
    echo "SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}"
    echo "SERVER_PORT=${SERVER_PORT}"
    echo "JWT_SECRET=${JWT_SECRET}"
    echo "DATABASE_URL=${DATABASE_URL}"
    echo "DATABASE_USER=${DATABASE_USER}"
    echo "DATABASE_PASSWORD=${DATABASE_PASSWORD}"
  } | sudo tee "$env_file" >/dev/null
  sudo chmod 600 "$env_file"
  echo "Wrote ${env_file}"
}

verify_required_secrets() {
  if [ -z "${JWT_SECRET:-}" ] || [ "${#JWT_SECRET}" -lt 32 ]; then
    echo "ERROR: JWT_SECRET missing or shorter than 32 characters after patch." >&2
    exit 1
  fi
  if [ -z "${DATABASE_URL:-}" ]; then
    echo "ERROR: DATABASE_URL empty after patch." >&2
    exit 1
  fi
  if [ -z "${DATABASE_USER:-}" ]; then
    echo "ERROR: DATABASE_USER empty after patch." >&2
    exit 1
  fi
  if [ -z "${DATABASE_PASSWORD:-}" ]; then
    echo "ERROR: DATABASE_PASSWORD empty after patch." >&2
    exit 1
  fi
}

verify_systemd_and_health() {
  local service="$1"
  local url="http://127.0.0.1:${SERVER_PORT}/actuator/health"

  if ! sudo systemctl is-active --quiet "$service"; then
    echo "ERROR: ${service} is not active after restart." >&2
    sudo systemctl status "$service" --no-pager -n 30 || true
    sudo journalctl -u "$service" -n 40 --no-pager || true
    exit 1
  fi
  echo "Service active: ${service}"

  local attempt code
  for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
    code="$(curl -s -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || true)"
    if [ "$code" = "200" ]; then
      echo "Health OK (${url} → HTTP ${code})"
      return 0
    fi
    echo "Waiting for Spring Boot (${url}) attempt ${attempt}/12 (HTTP ${code:-000})..."
    sleep 5
  done

  echo "ERROR: health check failed at ${url} (last HTTP ${code})." >&2
  echo "Check Nginx: proxy_pass must target 127.0.0.1:${SERVER_PORT}." >&2
  sudo ss -tlnp | grep -E ":${SERVER_PORT}\\b" || echo "Nothing listening on port ${SERVER_PORT}." >&2
  sudo journalctl -u "$service" -n 40 --no-pager || true
  exit 1
}

write_env_file
verify_required_secrets
echo "Application env patch completed."

SYSTEMD_SERVICE_NAME="${SYSTEMD_SERVICE_NAME:-${SYSTEMD_SERVICE_FALLBACK:-fieldops-api.service}}"

if [ -n "${SYSTEMD_SERVICE_NAME}" ]; then
  ENV_FILE="${DEPLOY_TARGET}/${ENV_FILE_NAME}"
  DROPIN_DIR="/etc/systemd/system/${SYSTEMD_SERVICE_NAME}.d"
  DROPIN_FILE="${DROPIN_DIR}/github-actions-deploy.conf"
  sudo mkdir -p "$DROPIN_DIR"
  {
    echo "[Service]"
    echo "EnvironmentFile=-${ENV_FILE}"
    echo "WorkingDirectory=${DEPLOY_TARGET}"
  } | sudo tee "$DROPIN_FILE" >/dev/null
  sudo systemctl daemon-reload
  sudo systemctl restart "$SYSTEMD_SERVICE_NAME"
  echo "Service restarted (${SYSTEMD_SERVICE_NAME})."
  verify_systemd_and_health "$SYSTEMD_SERVICE_NAME"
fi
