#!/bin/sh

set -eu

BUNDLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENV_FILE=${NHS_ENV_FILE:-$BUNDLE_DIR/.env}
COMPOSE_FILE=$BUNDLE_DIR/compose.yaml
PROJECT_NAME=${COMPOSE_PROJECT_NAME:-nhs}
LOCK_OWNED=false

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

info() {
  printf '%s\n' "$*"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is missing: $1"
}

require_env_file() {
  [ -f "$ENV_FILE" ] || fail "environment file does not exist: $ENV_FILE"
}

load_env() {
  require_env_file
  set -a
  # The environment file is created and chmod 0600 by install.sh.
  . "$ENV_FILE"
  set +a
}

compose() {
  docker compose --project-name "$PROJECT_NAME" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

acquire_lock() {
  requested_lock=$BUNDLE_DIR/.operation.lock
  if [ "${NHS_OPERATION_LOCK_DIR:-}" = "$requested_lock" ] && [ -d "$requested_lock" ]; then
    LOCK_DIR=$requested_lock
    LOCK_OWNED=false
    return
  fi
  LOCK_DIR=$requested_lock
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    fail "another install/backup/restore/upgrade operation is active"
  fi
  LOCK_OWNED=true
  NHS_OPERATION_LOCK_DIR=$LOCK_DIR
  export NHS_OPERATION_LOCK_DIR
  trap 'release_lock' EXIT
  trap 'release_lock; exit 130' INT
  trap 'release_lock; exit 143' TERM
}

release_lock() {
  if [ "${LOCK_OWNED:-false}" = true ] && [ -n "${LOCK_DIR:-}" ]; then
    rmdir "$LOCK_DIR" 2>/dev/null || true
    LOCK_OWNED=false
  fi
}

verify_manifest() {
  [ -f "$BUNDLE_DIR/manifest.sha256" ] || fail "manifest.sha256 is missing"
  (cd "$BUNDLE_DIR" && sha256sum -c manifest.sha256)
}

random_hex() {
  openssl rand -hex "$1"
}

set_env_value() {
  set_env_file_value "$ENV_FILE" "$1" "$2"
}

set_env_file_value() {
  target_env_file=$1
  key=$2
  value=$3
  temporary=$target_env_file.tmp.$$
  awk -v key="$key" -v value="$value" '
    BEGIN { found = 0 }
    index($0, key "=") == 1 { print key "=" value; found = 1; next }
    { print }
    END { if (!found) print key "=" value }
  ' "$target_env_file" > "$temporary"
  chmod 600 "$temporary"
  mv "$temporary" "$target_env_file"
}

env_file_value() {
  source_env_file=$1
  env_key=$2
  [ -f "$source_env_file" ] || fail "environment file does not exist: $source_env_file"
  env_key_count=$(awk -v key="$env_key" '
    index($0, key "=") == 1 { count += 1 }
    END { print count + 0 }
  ' "$source_env_file")
  [ "$env_key_count" = "1" ] || fail "$source_env_file must define $env_key exactly once"
  awk -v key="$env_key" '
    index($0, key "=") == 1 { print substr($0, length(key) + 2); exit }
  ' "$source_env_file"
}

merge_release_environment() {
  release_template=$1
  target_env_file=$2
  for release_key in \
    RELEASE_VERSION POSTGRES_IMAGE REDIS_IMAGE APP_IMAGE FRONTEND_IMAGE \
    SANDBOX_RUNNER_IMAGE BROWSER_WORKER_IMAGE NHS_REQUIRED_SCHEMA_VERSION
  do
    if ! grep -q "^${release_key}=" "$release_template"; then
      [ "$release_key" = NHS_REQUIRED_SCHEMA_VERSION ] && continue
      fail "$release_template must define $release_key"
    fi
    release_value=$(env_file_value "$release_template" "$release_key")
    [ -n "$release_value" ] || fail "$release_template contains an empty $release_key"
    set_env_file_value "$target_env_file" "$release_key" "$release_value"
  done
}

agent_workspace_host_path() {
  printf '%s\n' "${SANDBOX_WORKSPACE_HOST_PATH:-/var/lib/nhs/agent-workspaces}"
}

validate_agent_workspace_host_path() {
  agent_workspace_value=$1
  case "$agent_workspace_value" in
    /*) : ;;
    *) fail "SANDBOX_WORKSPACE_HOST_PATH must be absolute" ;;
  esac
  case "$agent_workspace_value" in
    *'/../'*|*/..|*'/./'*|*'//'*) fail "SANDBOX_WORKSPACE_HOST_PATH must be normalized" ;;
    *:*) fail "SANDBOX_WORKSPACE_HOST_PATH cannot contain a colon" ;;
  esac
  agent_workspace_name=${agent_workspace_value##*/}
  case "$agent_workspace_name" in
    agent-workspaces|agent-workspaces-*|sandbox-workspaces|sandbox-workspaces-*) : ;;
    *) fail "workspace basename must start with agent-workspaces or sandbox-workspaces" ;;
  esac
}
