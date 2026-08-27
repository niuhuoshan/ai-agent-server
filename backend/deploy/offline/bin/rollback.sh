#!/bin/sh

set -eu
. "$(dirname -- "$0")/common.sh"

PREVIOUS_BUNDLE=
BACKUP_DIR=
CONFIRMED=false
SANDBOX_MODE=auto
while [ "$#" -gt 0 ]; do
  case "$1" in
    --previous-bundle=*) PREVIOUS_BUNDLE=${1#--previous-bundle=} ;;
    --backup=*) BACKUP_DIR=${1#--backup=} ;;
    --yes) CONFIRMED=true ;;
    --with-sandbox) SANDBOX_MODE=with ;;
    --without-sandbox) SANDBOX_MODE=without ;;
    *) fail "unknown rollback argument: $1" ;;
  esac
  shift
done

[ -n "$PREVIOUS_BUNDLE" ] || fail "--previous-bundle=PATH is required"
[ -n "$BACKUP_DIR" ] || fail "--backup=PATH is required"
[ "$CONFIRMED" = true ] || fail "rollback restores an older database snapshot; pass --yes"
PREVIOUS_BUNDLE=$(CDPATH= cd -- "$PREVIOUS_BUNDLE" && pwd)
[ "$PREVIOUS_BUNDLE" != "$BUNDLE_DIR" ] || fail "previous bundle must differ from the current bundle"

require_command docker
require_command sha256sum
acquire_lock
load_env
BACKUP_DIR=$(CDPATH= cd -- "$BACKUP_DIR" && pwd)
[ -f "$BACKUP_DIR/SHA256SUMS" ] || fail "rollback backup checksum manifest is missing"
(cd "$BACKUP_DIR" && sha256sum -c SHA256SUMS)
if [ "$SANDBOX_MODE" = auto ]; then
  if [ -f "$BACKUP_DIR/runner-data.tar.gz" ]; then
    SANDBOX_MODE=with
  else
    SANDBOX_MODE=without
  fi
fi
compose down

cp "$ENV_FILE" "$PREVIOUS_BUNDLE/.env"
chmod 600 "$PREVIOUS_BUNDLE/.env"
merge_release_environment "$PREVIOUS_BUNDLE/.env.example" "$PREVIOUS_BUNDLE/.env"
cp "$BUNDLE_DIR/runtime-config.js" "$PREVIOUS_BUNDLE/runtime-config.js"
profiles=
[ "$SANDBOX_MODE" = with ] && profiles="--with-sandbox"
# shellcheck disable=SC2086
"$PREVIOUS_BUNDLE/bin/install.sh" $profiles
restore_args="--backup=$BACKUP_DIR --yes --adopt-installation-identity"
[ "$SANDBOX_MODE" = with ] && restore_args="$restore_args --with-sandbox"
[ "$SANDBOX_MODE" = without ] && restore_args="$restore_args --without-sandbox"
# shellcheck disable=SC2086
"$PREVIOUS_BUNDLE/bin/restore.sh" $restore_args
info "Rollback completed to $PREVIOUS_BUNDLE using $BACKUP_DIR"
