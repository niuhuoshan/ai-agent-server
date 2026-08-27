#!/bin/sh

set -eu
. "$(dirname -- "$0")/common.sh"

WITH_SANDBOX=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --with-sandbox) WITH_SANDBOX=true ;;
    --env=*) ENV_FILE=${1#--env=} ;;
    *) fail "unknown install argument: $1" ;;
  esac
  shift
done

require_command docker
require_command openssl
require_command sha256sum
acquire_lock
verify_manifest

if [ ! -f "$ENV_FILE" ]; then
  cp "$BUNDLE_DIR/.env.example" "$ENV_FILE"
fi
chmod 600 "$ENV_FILE"

load_env
case ${POSTGRES_PASSWORD:-} in REPLACE_*|'') set_env_value POSTGRES_PASSWORD "$(random_hex 24)" ;; esac
case ${REDIS_PASSWORD:-} in REPLACE_*|'') set_env_value REDIS_PASSWORD "$(random_hex 24)" ;; esac
case ${SA_TOKEN_JWT_SECRET:-} in REPLACE_*|'') set_env_value SA_TOKEN_JWT_SECRET "$(random_hex 48)" ;; esac
case ${NHS_NOTIFICATION_CONFIG_KEY:-} in
  GENERATED_BY_INSTALL_SCRIPT|'')
    set_env_value NHS_NOTIFICATION_CONFIG_KEY "$(openssl rand -base64 32 | tr -d '\n')"
    ;;
esac

if [ "${API_DECRYPT_PRIVATE_KEY:-}" = "GENERATED_BY_INSTALL_SCRIPT" ] || [ -z "${API_DECRYPT_PRIVATE_KEY:-}" ]; then
  key_dir=$(mktemp -d "${TMPDIR:-/tmp}/nhs-key.XXXXXX")
  trap 'rm -rf "$key_dir"; rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT INT TERM
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$key_dir/private.pem" >/dev/null 2>&1
  private_key=$(openssl pkcs8 -topk8 -nocrypt -in "$key_dir/private.pem" -outform DER | base64 | tr -d '\n')
  public_key=$(openssl pkey -in "$key_dir/private.pem" -pubout -outform DER | base64 | tr -d '\n')
  set_env_value API_DECRYPT_PRIVATE_KEY "$private_key"
  printf 'window.__NHS_SERVER_CONFIG__ = { rsaPublicKey: "%s" };\n' "$public_key" > "$BUNDLE_DIR/runtime-config.js"
  chmod 644 "$BUNDLE_DIR/runtime-config.js"
  rm -rf "$key_dir"
fi

load_env
NHS_ENV_FILE=$ENV_FILE "$BUNDLE_DIR/bin/doctor.sh" --preflight

[ -f "$BUNDLE_DIR/images.tar" ] || fail "images.tar is missing"
info "Loading offline images..."
docker load -i "$BUNDLE_DIR/images.tar" >/dev/null

workspace_host=$(agent_workspace_host_path)
validate_agent_workspace_host_path "$workspace_host"
mkdir -p "$workspace_host" || fail "cannot create shared workspace: $workspace_host"

# Migrate workspaces created by releases that kept them inside app-data.  The copy is
# additive so a legacy sandbox directory already using this host path is preserved.
app_data_volume=${PROJECT_NAME}_app-data
if docker volume inspect "$app_data_volume" >/dev/null 2>&1; then
  docker run --rm --user 0 --entrypoint sh \
    -v "$app_data_volume:/legacy:ro" \
    -v "$workspace_host:/workspace" "$APP_IMAGE" \
    -ec 'if [ -d /legacy/agent-workspaces ]; then cp -an /legacy/agent-workspaces/. /workspace/; fi
         chown 10001:10001 /workspace
         chmod 0700 /workspace'
else
  docker run --rm --user 0 --entrypoint sh \
    -v "$workspace_host:/workspace" "$APP_IMAGE" \
    -ec 'chown 10001:10001 /workspace; chmod 0700 /workspace'
fi

profiles=
if [ "$WITH_SANDBOX" = true ]; then
  [ -n "${NHS_SANDBOX_BOOTSTRAP_TOKEN:-}" ] || fail "sandbox profile requires NHS_SANDBOX_BOOTSTRAP_TOKEN"
  case ${SANDBOX_TEMPLATE_CODE:-} in *@sha256:????????????????????????????????????????????????????????????????) : ;; *) fail "sandbox profile requires immutable SANDBOX_TEMPLATE_CODE" ;; esac
  profiles="--profile sandbox"
fi

info "Starting nhs..."
# shellcheck disable=SC2086
compose $profiles up -d --remove-orphans
NHS_ENV_FILE=$ENV_FILE "$BUNDLE_DIR/bin/doctor.sh"
info "nhs is available at http://${HTTP_BIND_ADDRESS:-127.0.0.1}:${HTTP_PORT:-8080}/"
