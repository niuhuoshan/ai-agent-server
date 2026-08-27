#!/bin/sh

set -eu
. "$(dirname -- "$0")/common.sh"

NEW_BUNDLE=
SANDBOX_MODE=auto
while [ "$#" -gt 0 ]; do
  case "$1" in
    --bundle=*) NEW_BUNDLE=${1#--bundle=} ;;
    --with-sandbox) SANDBOX_MODE=with ;;
    --without-sandbox) SANDBOX_MODE=without ;;
    *) fail "unknown upgrade argument: $1" ;;
  esac
  shift
done

[ -n "$NEW_BUNDLE" ] || fail "--bundle=PATH is required"
NEW_BUNDLE=$(CDPATH= cd -- "$NEW_BUNDLE" && pwd)
[ "$NEW_BUNDLE" != "$BUNDLE_DIR" ] || fail "new bundle must differ from the current bundle"

require_command docker
load_env
current_runner=$(compose ps -a -q sandbox-runner 2>/dev/null || true)
if [ "$SANDBOX_MODE" = auto ]; then
  if [ -n "$current_runner" ]; then
    SANDBOX_MODE=with
  else
    SANDBOX_MODE=without
  fi
fi

acquire_lock
backup_output=$(NHS_ENV_FILE=$ENV_FILE "$BUNDLE_DIR/bin/backup.sh" 2>&1) || fail "$backup_output"
printf '%s\n' "$backup_output"
backup_dir=$(printf '%s\n' "$backup_output" | awk '/Backup completed:/ {print $3}' | tail -1)
[ -n "$backup_dir" ] || fail "could not determine pre-upgrade backup path"

(cd "$NEW_BUNDLE" && sha256sum -c manifest.sha256)
cp "$ENV_FILE" "$NEW_BUNDLE/.env"
chmod 600 "$NEW_BUNDLE/.env"
merge_release_environment "$NEW_BUNDLE/.env.example" "$NEW_BUNDLE/.env"
cp "$BUNDLE_DIR/runtime-config.js" "$NEW_BUNDLE/runtime-config.js"
docker load -i "$NEW_BUNDLE/images.tar" >/dev/null

compose down
profiles=
[ "$SANDBOX_MODE" = with ] && profiles="--with-sandbox"
if ! "$NEW_BUNDLE/bin/install.sh" $profiles; then
  cat > "$NEW_BUNDLE/ROLLBACK_REQUIRED" <<EOF
previous_bundle=$BUNDLE_DIR
backup=$backup_dir
sandbox_mode=$SANDBOX_MODE
EOF
  fail "upgrade health check failed; run rollback.sh with the recorded previous bundle and backup"
fi

cat > "$NEW_BUNDLE/previous-release.env" <<EOF
PREVIOUS_BUNDLE=$BUNDLE_DIR
PREVIOUS_BACKUP=$backup_dir
PREVIOUS_SANDBOX_MODE=$SANDBOX_MODE
EOF
info "Upgrade completed. Rollback data: $NEW_BUNDLE/previous-release.env"
