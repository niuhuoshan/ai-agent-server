#!/bin/sh

set -eu
. "$(dirname -- "$0")/common.sh"

BACKUP_DIR=
CONFIRMED=false
ADOPT_INSTALLATION_IDENTITY=false
SANDBOX_MODE=auto
while [ "$#" -gt 0 ]; do
  case "$1" in
    --backup=*) BACKUP_DIR=${1#--backup=} ;;
    --yes) CONFIRMED=true ;;
    --adopt-installation-identity) ADOPT_INSTALLATION_IDENTITY=true ;;
    --with-sandbox) SANDBOX_MODE=with ;;
    --without-sandbox) SANDBOX_MODE=without ;;
    *) fail "unknown restore argument: $1" ;;
  esac
  shift
done

[ -n "$BACKUP_DIR" ] || fail "--backup=PATH is required"
[ "$CONFIRMED" = true ] || fail "restore replaces current data; pass --yes after verifying the target"
BACKUP_DIR=$(CDPATH= cd -- "$BACKUP_DIR" && pwd)
[ -f "$BACKUP_DIR/SHA256SUMS" ] || fail "backup checksum manifest is missing"
require_command docker
require_command sha256sum
(cd "$BACKUP_DIR" && sha256sum -c SHA256SUMS)

acquire_lock
load_env
backup_env=$BACKUP_DIR/environment.env
[ -f "$backup_env" ] || fail "backup environment is missing"
identity_mismatch=false
for identity_key in SA_TOKEN_JWT_SECRET API_DECRYPT_PRIVATE_KEY NHS_NOTIFICATION_CONFIG_KEY; do
  current_identity=$(env_file_value "$ENV_FILE" "$identity_key")
  backup_identity=$(env_file_value "$backup_env" "$identity_key")
  if [ "$current_identity" != "$backup_identity" ]; then
    identity_mismatch=true
    if [ "$ADOPT_INSTALLATION_IDENTITY" != true ]; then
      fail "$identity_key differs from the backup; pass --adopt-installation-identity to restore it"
    fi
  fi
done
workspace_host=$(agent_workspace_host_path)
validate_agent_workspace_host_path "$workspace_host"
mkdir -p "$workspace_host" || fail "cannot create shared workspace: $workspace_host"
runner_container=$(compose ps -a -q sandbox-runner 2>/dev/null || true)
backup_runner_data=false
[ -f "$BACKUP_DIR/runner-data.tar.gz" ] && backup_runner_data=true
case "$SANDBOX_MODE" in
  with)
    [ "$backup_runner_data" = true ] || fail "--with-sandbox requires runner-data.tar.gz in the backup"
    ;;
  without)
    if [ -n "$runner_container" ]; then
      info "Removing the current Sandbox Runner because --without-sandbox was requested..."
      compose --profile sandbox rm -f sandbox-runner >/dev/null 2>&1 || true
      runner_container=
    fi
    ;;
  auto)
    if [ -n "$runner_container" ] && [ "$backup_runner_data" != true ]; then
      fail "current deployment has Sandbox Runner state but the backup has no runner-data.tar.gz"
    fi
    if [ -z "$runner_container" ] && [ "$backup_runner_data" = true ]; then
      fail "backup contains Sandbox Runner state; pass --with-sandbox or --without-sandbox explicitly"
    fi
    if [ -n "$runner_container" ]; then
      SANDBOX_MODE=with
    else
      SANDBOX_MODE=without
    fi
    ;;
  *) fail "invalid sandbox restore mode" ;;
esac
if [ "$SANDBOX_MODE" = with ]; then
  [ -n "${NHS_SANDBOX_BOOTSTRAP_TOKEN:-}" ] || fail "sandbox restore requires NHS_SANDBOX_BOOTSTRAP_TOKEN"
  case ${SANDBOX_TEMPLATE_CODE:-} in *@sha256:????????????????????????????????????????????????????????????????) : ;; *) fail "sandbox restore requires immutable SANDBOX_TEMPLATE_CODE" ;; esac
fi

info "Stopping application traffic..."
compose stop frontend app browser-worker sandbox-runner 2>/dev/null || true

info "Restoring PostgreSQL..."
compose exec -T postgres dropdb -U "$POSTGRES_USER" --if-exists "$POSTGRES_DB"
compose exec -T postgres createdb -U "$POSTGRES_USER" -O "$POSTGRES_USER" --template=template0 "$POSTGRES_DB"
compose exec -T postgres pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  --exit-on-error --no-owner --no-privileges < "$BACKUP_DIR/postgres.dump"

info "Restoring Redis..."
compose stop redis
redis_volume=${PROJECT_NAME}_redis-data
docker run --rm --user 0 --entrypoint sh -v "$redis_volume:/data" -v "$BACKUP_DIR:/backup:ro" "$REDIS_IMAGE" \
  -ec 'rm -rf /data/appendonlydir; rm -f /data/appendonly.aof /data/dump.rdb; cp /backup/redis.rdb /data/dump.rdb; chown redis:redis /data/dump.rdb'

info "Rebuilding Redis AOF from the restored RDB..."
docker run --rm --network none --user redis --entrypoint sh -v "$redis_volume:/data" "$REDIS_IMAGE" -ec '
  redis-server --dir /data --dbfilename dump.rdb --appendonly no --save "" --bind 127.0.0.1 --daemonize yes --pidfile /tmp/redis.pid
  attempt=0
  until redis-cli ping >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 30 ] || { echo "Redis RDB bootstrap did not start" >&2; exit 1; }
    sleep 1
  done
  redis-cli CONFIG SET appendonly yes >/dev/null
  attempt=0
  while :; do
    persistence=$(redis-cli INFO persistence)
    printf "%s\n" "$persistence" | grep -q "^aof_enabled:1" \
      && printf "%s\n" "$persistence" | grep -q "^aof_rewrite_in_progress:0" \
      && printf "%s\n" "$persistence" | grep -q "^aof_last_bgrewrite_status:ok" \
      && break
    attempt=$((attempt + 1))
    [ "$attempt" -lt 60 ] || { echo "Redis AOF rewrite did not complete" >&2; exit 1; }
    sleep 1
  done
  redis-cli SHUTDOWN NOSAVE >/dev/null 2>&1 || true
'

info "Restoring application data..."
app_volume=${PROJECT_NAME}_app-data
docker run --rm --user 0 --entrypoint sh -v "$app_volume:/restore" -v "$BACKUP_DIR:/backup:ro" "$APP_IMAGE" \
  -ec 'find /restore -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +; tar -C /restore -xzf /backup/app-data.tar.gz; chown -R 10001:10001 /restore'

info "Restoring shared AgentScope/Sandbox workspace..."
if [ -f "$BACKUP_DIR/agent-workspaces.tar.gz" ]; then
  docker run --rm --user 0 --entrypoint sh \
    -v "$workspace_host:/restore" -v "$BACKUP_DIR:/backup:ro" "$APP_IMAGE" \
    -ec 'find /restore -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
         tar -C /restore -xzf /backup/agent-workspaces.tar.gz
         chown -R 10001:10001 /restore
         chmod 0700 /restore'
else
  # Backups made before the shared-host bridge kept workspaces inside app-data.
  docker run --rm --user 0 --entrypoint sh \
    -v "$app_volume:/legacy:ro" -v "$workspace_host:/restore" "$APP_IMAGE" \
    -ec 'find /restore -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
         if [ -d /legacy/agent-workspaces ]; then cp -a /legacy/agent-workspaces/. /restore/; fi
         chown -R 10001:10001 /restore
         chmod 0700 /restore'
fi

if [ "$SANDBOX_MODE" = with ]; then
  runner_volume=${PROJECT_NAME}_runner-data
  docker run --rm --user 0 --entrypoint sh -v "$runner_volume:/restore" -v "$BACKUP_DIR:/backup:ro" "$SANDBOX_RUNNER_IMAGE" \
    -ec 'find /restore -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +; tar -C /restore -xzf /backup/runner-data.tar.gz; chown -R 10001:10001 /restore'
fi

if [ "$ADOPT_INSTALLATION_IDENTITY" = true ]; then
  for identity_key in SA_TOKEN_JWT_SECRET API_DECRYPT_PRIVATE_KEY NHS_NOTIFICATION_CONFIG_KEY; do
    set_env_value "$identity_key" "$(env_file_value "$backup_env" "$identity_key")"
  done
  cp "$BACKUP_DIR/runtime-config.js" "$BUNDLE_DIR/runtime-config.js"
  chmod 644 "$BUNDLE_DIR/runtime-config.js"
  load_env
  [ "$identity_mismatch" = true ] && info "Adopted installation identity from the backup"
fi

if [ "$SANDBOX_MODE" = with ]; then
  compose --profile sandbox up -d postgres redis app browser-worker frontend sandbox-runner
else
  compose up -d postgres redis app browser-worker frontend
fi
NHS_ENV_FILE=$ENV_FILE "$BUNDLE_DIR/bin/doctor.sh"
info "Restore completed from $BACKUP_DIR"
