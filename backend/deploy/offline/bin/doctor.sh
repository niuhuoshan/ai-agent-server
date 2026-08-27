#!/bin/sh

set -eu
. "$(dirname -- "$0")/common.sh"

PREFLIGHT=false
[ "${1:-}" = "--preflight" ] && PREFLIGHT=true

require_command docker
require_command sha256sum
require_command base64
require_env_file
verify_manifest
load_env
workspace_host=$(agent_workspace_host_path)
validate_agent_workspace_host_path "$workspace_host"

docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required"
docker info >/dev/null 2>&1 || fail "Docker daemon is unavailable"

mode=$(stat -c '%a' "$ENV_FILE" 2>/dev/null || stat -f '%Lp' "$ENV_FILE")
[ "$mode" = "600" ] || fail "$ENV_FILE must have mode 0600, found $mode"

[ ${#POSTGRES_PASSWORD} -ge 24 ] || fail "POSTGRES_PASSWORD must be at least 24 characters"
[ ${#REDIS_PASSWORD} -ge 24 ] || fail "REDIS_PASSWORD must be at least 24 characters"
[ ${#SA_TOKEN_JWT_SECRET} -ge 48 ] || fail "SA_TOKEN_JWT_SECRET must be at least 48 characters"
[ "$SA_TOKEN_JWT_SECRET" != "abcdefghijklmnopqrstuvwxyz" ] || fail "default JWT secret is forbidden"
case $POSTGRES_PASSWORD in REPLACE_*|agent_server) fail "default PostgreSQL password is forbidden" ;; esac
case $REDIS_PASSWORD in REPLACE_*|nhs123) fail "default Redis password is forbidden" ;; esac
case ${API_DECRYPT_PRIVATE_KEY:-} in MII*) : ;; *) fail "API_DECRYPT_PRIVATE_KEY is not a generated PKCS#8 key" ;; esac
notification_key_bytes=$(printf '%s' "${NHS_NOTIFICATION_CONFIG_KEY:-}" | base64 -d 2>/dev/null | wc -c | tr -d ' ')
[ "$notification_key_bytes" = "32" ] || fail "NHS_NOTIFICATION_CONFIG_KEY must be a Base64-encoded 32-byte key"

free_kb=$(df -Pk "$BUNDLE_DIR" | awk 'NR==2 {print $4}')
[ "$free_kb" -ge 5242880 ] || fail "at least 5 GiB free disk space is required"

compose config -q

if [ "$PREFLIGHT" = true ]; then
  info "Doctor preflight passed"
  exit 0
fi

for service in postgres redis app browser-worker frontend; do
  container=$(compose ps -q "$service")
  [ -n "$container" ] || fail "service is not created: $service"
  running=$(docker inspect -f '{{.State.Running}}' "$container")
  [ "$running" = "true" ] || fail "service is not running: $service"
done

runner_container=$(compose ps -a -q sandbox-runner 2>/dev/null || true)
if [ -n "$runner_container" ]; then
  running=$(docker inspect -f '{{.State.Running}}' "$runner_container")
  [ "$running" = "true" ] || fail "service is not running: sandbox-runner"
fi

attempt=0
while [ "$attempt" -lt 60 ]; do
  if compose exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1 \
    && compose exec -T redis redis-cli --no-auth-warning -a "$REDIS_PASSWORD" ping 2>/dev/null | grep -q PONG \
    && compose exec -T browser-worker node -e "fetch('http://127.0.0.1:8787/health').then(r => { if (!r.ok) process.exit(1); }).catch(() => process.exit(1))" \
    && compose exec -T frontend wget -q -O /dev/null http://127.0.0.1:8080/healthz; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 2
done
[ "$attempt" -lt 60 ] || fail "services did not become healthy within 120 seconds"

flyway_version=$(compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc \
    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1" 2>/dev/null || true)
[ -n "$flyway_version" ] || fail "Flyway history is missing"
case "$flyway_version" in *[!0-9]*) fail "latest Flyway version is not numeric: $flyway_version" ;; esac
# Releases before the package-level schema gate used V85 as their compatibility
# floor; keep existing installations bootable until upgrade.sh writes the new gate.
required_schema=${NHS_REQUIRED_SCHEMA_VERSION:-85}
case "$required_schema" in ''|*[!0-9]*) fail "NHS_REQUIRED_SCHEMA_VERSION must be numeric" ;; esac
latest_number=$flyway_version
[ "$latest_number" -ge "$required_schema" ] \
  || fail "database schema is V$flyway_version; this release requires V$required_schema or later"

failed_migrations=$(compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc \
  "SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success")
[ "$failed_migrations" = "0" ] || fail "Flyway contains failed migrations"

compose exec -T app sh -ec 'test -d "$NHS_WORKSPACE_ROOT" && test -w "$NHS_WORKSPACE_ROOT"' \
  || fail "application cannot write the shared workspace"

if [ -n "$runner_container" ]; then
  compose exec -T sandbox-runner sh -ec \
    'test -d "$NHS_SANDBOX_WORKSPACE_ROOT" && test -w "$NHS_SANDBOX_WORKSPACE_ROOT"' \
    || fail "sandbox Runner cannot write the shared workspace"
  attempt=0
  while [ "$attempt" -lt 60 ]; do
    active_runners=$(compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc \
      "SELECT COUNT(*) FROM agent_sandbox_runner WHERE status = 'active' AND heartbeat_expires_at >= (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')" 2>/dev/null || true)
    [ "${active_runners:-0}" -ge 1 ] 2>/dev/null && break
    attempt=$((attempt + 1))
    sleep 2
  done
  [ "$attempt" -lt 60 ] || fail "sandbox Runner did not register and heartbeat within 120 seconds"
fi

if [ -n "$runner_container" ]; then
  info "Doctor passed: schema V$latest_number, PostgreSQL, Redis, app, Browser Worker, frontend and sandbox Runner are healthy"
else
  info "Doctor passed: schema V$latest_number, PostgreSQL, Redis, app, Browser Worker and frontend are healthy"
fi
