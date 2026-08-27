#!/bin/sh

set -eu

BACKEND_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
FRONTEND_DIR=$(CDPATH= cd -- "$BACKEND_DIR/../frontend" && pwd)
BROWSER_WORKER_DIR=$(CDPATH= cd -- "$BACKEND_DIR/../browser-worker" && pwd)
VERSION=${RELEASE_VERSION:-6.0.0-phase1}
OUTPUT_ROOT=${OUTPUT_ROOT:-$BACKEND_DIR/build/offline}
POSTGRES_UPSTREAM_IMAGE=${POSTGRES_UPSTREAM_IMAGE:-dockerproxy.net/paradedb/paradedb:latest-pg17}
REDIS_UPSTREAM_IMAGE=${REDIS_UPSTREAM_IMAGE:-dockerproxy.net/library/redis:8-alpine}
SANDBOX_TEMPLATE_IMAGE=${SANDBOX_TEMPLATE_IMAGE:-}
APP_JAVA_IMAGE=${APP_JAVA_IMAGE:-dockerproxy.net/library/eclipse-temurin:21-jre-jammy}
RUNNER_JAVA_IMAGE=${RUNNER_JAVA_IMAGE:-dockerproxy.net/library/eclipse-temurin:21-jre-alpine}
NGINX_IMAGE=${NGINX_IMAGE:-dockerproxy.net/library/nginx:1.28-alpine}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version=*) VERSION=${1#--version=} ;;
    --output=*) OUTPUT_ROOT=${1#--output=} ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
  shift
done

for command in docker java node corepack sha256sum tar unzip; do
  command -v "$command" >/dev/null 2>&1 || { printf 'Missing command: %s\n' "$command" >&2; exit 1; }
done
docker info >/dev/null

SCHEMA_VERSION=$(find "$BACKEND_DIR/script/sql/postgres/agent" -maxdepth 1 -type f -name 'V*__*.sql' \
  -printf '%f\n' | sed -n 's/^V\([0-9][0-9]*\)__.*/\1/p' | sort -n | tail -1)
[ -n "$SCHEMA_VERSION" ] || { printf 'No numbered agent Flyway migrations found\n' >&2; exit 1; }

BUNDLE=$OUTPUT_ROOT/nhs-$VERSION
[ ! -e "$BUNDLE" ] || { printf 'Bundle already exists: %s\n' "$BUNDLE" >&2; exit 1; }
mkdir -p "$BUNDLE"

printf 'Building Java artifacts with tests enabled...\n'
(cd "$BACKEND_DIR" && ./mvnw -Dmaven.test.skip=false package)
(cd "$BACKEND_DIR" && ./mvnw -Dmaven.test.skip=false \
  org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
  -DoutputFormat=json -DoutputName=backend-sbom \
  -DincludeBomSerialNumber=true -DincludeLicenseText=false -DskipNotDeployed=false)
printf 'Building frontend...\n'
(cd "$FRONTEND_DIR" && corepack pnpm install --frozen-lockfile && corepack pnpm typecheck && corepack pnpm build)
(cd "$FRONTEND_DIR" && env -u NODE_PATH -u ORCA_NHS_HOOK_TOKEN -u ORCA_PANE_KEY \
  corepack pnpm dlx @cyclonedx/cdxgen@12.8.3 \
  -t js --no-recurse --no-install-deps --fail-on-error \
  --profile license-compliance --spec-version 1.6 --json-pretty \
  -o "$BACKEND_DIR/target/frontend-sbom.json" .)
node -e "const fs=require('fs'); for (const file of process.argv.slice(1)) { const bom=JSON.parse(fs.readFileSync(file)); if (bom.bomFormat !== 'CycloneDX' || !bom.components?.length) process.exit(1); }" \
  "$BACKEND_DIR/target/backend-sbom.json" "$BACKEND_DIR/target/frontend-sbom.json"

APP_IMAGE=nhs/app:$VERSION
FRONTEND_IMAGE=nhs/frontend:$VERSION
RUNNER_IMAGE=nhs/sandbox-runner:$VERSION
BROWSER_IMAGE=nhs/browser-worker:$VERSION
POSTGRES_IMAGE=nhs/postgres:17
REDIS_IMAGE=nhs/redis:8

docker pull "$POSTGRES_UPSTREAM_IMAGE"
docker pull "$REDIS_UPSTREAM_IMAGE"
docker tag "$POSTGRES_UPSTREAM_IMAGE" "$POSTGRES_IMAGE"
docker tag "$REDIS_UPSTREAM_IMAGE" "$REDIS_IMAGE"
docker build --build-arg JAVA_IMAGE="$APP_JAVA_IMAGE" -f "$BACKEND_DIR/deploy/docker/app.Dockerfile" -t "$APP_IMAGE" "$BACKEND_DIR/nhs-admin/target"
docker build --build-arg JAVA_IMAGE="$RUNNER_JAVA_IMAGE" -f "$BACKEND_DIR/deploy/docker/sandbox-runner.Dockerfile" -t "$RUNNER_IMAGE" "$BACKEND_DIR/nhs-modules/nhs-sandbox-runner/target"
docker build --build-arg NGINX_IMAGE="$NGINX_IMAGE" -f "$FRONTEND_DIR/deploy/Dockerfile" -t "$FRONTEND_IMAGE" "$FRONTEND_DIR"
docker build -f "$BROWSER_WORKER_DIR/Dockerfile" -t "$BROWSER_IMAGE" "$BROWSER_WORKER_DIR"

images="$APP_IMAGE $FRONTEND_IMAGE $RUNNER_IMAGE $BROWSER_IMAGE $POSTGRES_IMAGE $REDIS_IMAGE"
if [ -n "$SANDBOX_TEMPLATE_IMAGE" ]; then
  case "$SANDBOX_TEMPLATE_IMAGE" in *@sha256:*) : ;; *) printf 'SANDBOX_TEMPLATE_IMAGE must use @sha256\n' >&2; exit 1 ;; esac
  docker pull "$SANDBOX_TEMPLATE_IMAGE"
  images="$images $SANDBOX_TEMPLATE_IMAGE"
fi

cp -R "$BACKEND_DIR/deploy/offline/." "$BUNDLE/"
mkdir -p "$BUNDLE/init/sql" "$BUNDLE/evidence"
cp "$BACKEND_DIR/target/backend-sbom.json" "$BUNDLE/evidence/backend-sbom.cdx.json"
cp "$BACKEND_DIR/target/frontend-sbom.json" "$BUNDLE/evidence/frontend-sbom.cdx.json"
cp "$BACKEND_DIR/script/docker/postgres-init.sql" "$BUNDLE/init/postgres-init.sql"
cp "$BACKEND_DIR/script/sql/postgres/postgres_ry_vue.sql" "$BUNDLE/init/sql/postgres_ry_vue.sql"
cp "$BACKEND_DIR/script/sql/postgres/postgres_ry_job.sql" "$BUNDLE/init/sql/postgres_ry_job.sql"
cp "$BACKEND_DIR/script/sql/postgres/postgres_ry_workflow.sql" "$BUNDLE/init/sql/postgres_ry_workflow.sql"
cp "$BACKEND_DIR/script/sql/postgres/postgres_ry_ai.sql" "$BUNDLE/init/sql/postgres_ry_ai.sql"
printf 'window.__NHS_SERVER_CONFIG__ = window.__NHS_SERVER_CONFIG__ || {};\n' > "$BUNDLE/runtime-config.js"

sed -i "s|^RELEASE_VERSION=.*|RELEASE_VERSION=$VERSION|" "$BUNDLE/.env.example"
sed -i "s|^APP_IMAGE=.*|APP_IMAGE=$APP_IMAGE|" "$BUNDLE/.env.example"
sed -i "s|^FRONTEND_IMAGE=.*|FRONTEND_IMAGE=$FRONTEND_IMAGE|" "$BUNDLE/.env.example"
sed -i "s|^SANDBOX_RUNNER_IMAGE=.*|SANDBOX_RUNNER_IMAGE=$RUNNER_IMAGE|" "$BUNDLE/.env.example"
sed -i "s|^BROWSER_WORKER_IMAGE=.*|BROWSER_WORKER_IMAGE=$BROWSER_IMAGE|" "$BUNDLE/.env.example"
sed -i "s|^NHS_REQUIRED_SCHEMA_VERSION=.*|NHS_REQUIRED_SCHEMA_VERSION=$SCHEMA_VERSION|" "$BUNDLE/.env.example"
if [ -n "$SANDBOX_TEMPLATE_IMAGE" ]; then
  sed -i "s|^SANDBOX_TEMPLATE_CODE=.*|SANDBOX_TEMPLATE_CODE=$SANDBOX_TEMPLATE_IMAGE|" "$BUNDLE/.env.example"
fi

docker save -o "$BUNDLE/images.tar" $images
docker image inspect $images > "$BUNDLE/evidence/images.json"
{
  unzip -Z1 "$BACKEND_DIR/nhs-admin/target/nhs-admin.jar"
  unzip -Z1 "$BACKEND_DIR/nhs-modules/nhs-sandbox-runner/target/nhs-sandbox-runner-6.0.0.jar"
  unzip -Z1 "$BACKEND_DIR/nhs-modules/nhs-migration-cli/target/nhs-migration-cli.jar"
} | sed -n 's#^BOOT-INF/lib/##p' | sort -u > "$BUNDLE/evidence/java-runtime-libraries.txt"
(cd "$FRONTEND_DIR" && corepack pnpm licenses list --json > "$BUNDLE/evidence/frontend-licenses.json")
node -e "const fs=require('fs'); const value=JSON.parse(fs.readFileSync(process.argv[1])); if (!value || Array.isArray(value) || typeof value !== 'object' || !Object.keys(value).length) process.exit(1);" \
  "$BUNDLE/evidence/frontend-licenses.json"

chmod 755 "$BUNDLE/bin/"*.sh
chmod 600 "$BUNDLE/.env.example"
(cd "$BUNDLE" && find . -type f \
  ! -name manifest.sha256 ! -name .env ! -name runtime-config.js \
  -print0 | sort -z | xargs -0 sha256sum > manifest.sha256)

mkdir -p "$OUTPUT_ROOT"
tar -C "$OUTPUT_ROOT" -czf "$OUTPUT_ROOT/nhs-$VERSION.tar.gz" "nhs-$VERSION"
printf 'Offline bundle: %s\n' "$OUTPUT_ROOT/nhs-$VERSION.tar.gz"
