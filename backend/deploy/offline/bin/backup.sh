#!/bin/sh

set -eu
. "$(dirname -- "$0")/common.sh"

BACKUP_ROOT=${NHS_BACKUP_ROOT:-$BUNDLE_DIR/backups}
while [ "$#" -gt 0 ]; do
  case "$1" in
    --output=*) BACKUP_ROOT=${1#--output=} ;;
    *) fail "unknown backup argument: $1" ;;
  esac
  shift
done

require_command docker
require_command sha256sum
acquire_lock
load_env
NHS_ENV_FILE=$ENV_FILE "$BUNDLE_DIR/bin/doctor.sh"
workspace_host=$(agent_workspace_host_path)
validate_agent_workspace_host_path "$workspace_host"
[ -d "$workspace_host" ] || fail "shared workspace does not exist: $workspace_host"

runner_container=$(compose ps -a -q sandbox-runner 2>/dev/null || true)
SERVICES_QUIESCED=false
backup_cleanup() {
  backup_status=$?
  trap - EXIT INT TERM
  if [ "${SERVICES_QUIESCED:-false}" = true ]; then
    if [ -n "${runner_container:-}" ]; then
      compose --profile sandbox up -d postgres redis app browser-worker frontend sandbox-runner \
        >/dev/null 2>&1 || true
    else
      compose up -d postgres redis app browser-worker frontend >/dev/null 2>&1 || true
    fi
  fi
  release_lock
  exit "$backup_status"
}
trap backup_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

info "Quiescing application writers for a consistent backup..."
SERVICES_QUIESCED=true
compose stop frontend app browser-worker >/dev/null
if [ -n "$runner_container" ]; then
  compose --profile sandbox stop sandbox-runner >/dev/null
fi

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
backup_dir=$BACKUP_ROOT/nhs-$timestamp
mkdir -p "$BACKUP_ROOT"
mkdir "$backup_dir" || fail "backup already exists for timestamp $timestamp: $backup_dir"
chmod 700 "$backup_dir"

info "Backing up PostgreSQL..."
compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  --format=custom --compress=6 --no-owner --no-privileges > "$backup_dir/postgres.dump"

info "Backing up Redis..."
compose exec -T redis redis-cli --no-auth-warning -a "$REDIS_PASSWORD" SAVE >/dev/null
redis_container=$(compose ps -q redis)
docker cp "$redis_container:/data/dump.rdb" "$backup_dir/redis.rdb"

info "Backing up application data..."
app_container=$(compose ps -a -q app)
[ -n "$app_container" ] || fail "application container does not exist"
docker run --rm --user 0 --entrypoint sh --volumes-from "$app_container" \
  -v "$backup_dir:/backup" "$APP_IMAGE" \
  -ec 'tar -C /opt/nhs/data -czf /backup/app-data.tar.gz .'

info "Backing up shared AgentScope/Sandbox workspace..."
docker run --rm --user 0 --entrypoint sh \
  -v "$workspace_host:/workspace:ro" -v "$backup_dir:/backup" "$APP_IMAGE" \
  -ec 'tar --exclude=./.skill-staging -C /workspace -czf /backup/agent-workspaces.tar.gz .'

if [ -n "$runner_container" ]; then
  docker run --rm --user 0 --entrypoint sh --volumes-from "$runner_container" \
    -v "$backup_dir:/backup" "$SANDBOX_RUNNER_IMAGE" \
    -ec 'tar -C /opt/agent-runner/data -czf /backup/runner-data.tar.gz .'
fi

cp "$ENV_FILE" "$backup_dir/environment.env"
chmod 600 "$backup_dir/environment.env"
cp "$BUNDLE_DIR/runtime-config.js" "$backup_dir/runtime-config.js"
cat > "$backup_dir/metadata.txt" <<EOF
created_at=$timestamp
release_version=${RELEASE_VERSION:-unknown}
app_image=$APP_IMAGE
frontend_image=$FRONTEND_IMAGE
postgres_image=$POSTGRES_IMAGE
redis_image=$REDIS_IMAGE
sandbox_runner_image=${SANDBOX_RUNNER_IMAGE:-disabled}
schema_version=${NHS_REQUIRED_SCHEMA_VERSION:-unknown}
workspace_host_path=$workspace_host
EOF

checksum_files="postgres.dump redis.rdb app-data.tar.gz agent-workspaces.tar.gz environment.env runtime-config.js metadata.txt"
if [ -n "$runner_container" ]; then
  checksum_files="$checksum_files runner-data.tar.gz"
fi
(cd "$backup_dir" && sha256sum $checksum_files > SHA256SUMS)
if [ -n "$runner_container" ]; then
  compose --profile sandbox up -d postgres redis app browser-worker frontend sandbox-runner >/dev/null
else
  compose up -d postgres redis app browser-worker frontend >/dev/null
fi
SERVICES_QUIESCED=false
info "Backup completed: $backup_dir"
