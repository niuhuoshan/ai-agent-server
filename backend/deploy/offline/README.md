# nhs offline operations

The release archive contains application, frontend, PostgreSQL, Redis, Browser Worker and Sandbox Runner images.
Only the frontend port is published. PostgreSQL and Redis stay on the Compose network.

## Install

1. Extract the archive onto a host with Docker Engine 24+ and Compose v2.
2. Run `bin/install.sh`. It verifies every bundled file, generates database/Redis/JWT/RSA
   secrets plus the personal notification credential key, loads images, starts the stack and
   runs Doctor.
3. Use `bin/install.sh --with-sandbox` only after configuring an immutable
`SANDBOX_TEMPLATE_CODE=repository@sha256:...`, the Docker socket group ID, and a bootstrap
token. The main JVM never receives the container socket. `SANDBOX_WORKSPACE_HOST_PATH` is the
shared host directory mounted at the same absolute path in the app and Runner containers; its
legacy name is retained so existing installations can upgrade in place.

The default bind address is `127.0.0.1:8080`. Put a TLS reverse proxy or load balancer in front
before exposing the service beyond the host.

Browser control runs in the bundled `browser-worker` container. The JVM calls it over the
private Compose network at `http://browser-worker:8787`; Playwright never runs inside the JVM.
Private and loopback browser targets are blocked by default. Set
`NHS_BROWSER_ALLOW_PRIVATE_TARGETS=true` only for an isolated development deployment.

## Operations

- `bin/doctor.sh`: manifests, secret policy, free space, shared-workspace access, Compose,
  service health (including Browser Worker) and the release's required Flyway version.
- `bin/backup.sh --output=/protected/backups`: briefly quiesces application writers, then takes a
  PostgreSQL custom dump, Redis RDB, application data, shared AgentScope/Sandbox workspace, Runner
  credentials, environment and checksums. A timestamp collision is rejected instead of merging
  two snapshots.
- `bin/restore.sh --backup=PATH --yes`: destructive restore with checksum validation and Doctor.
  Installation identity keys must match the running deployment; for disaster recovery onto a new
  installation, pass `--adopt-installation-identity` to restore the JWT/API/notification keys and
  the matching runtime config. Sandbox state must be selected explicitly with `--with-sandbox` or
  `--without-sandbox` when the current deployment and snapshot differ.
- `bin/upgrade.sh --bundle=PATH`: mandatory pre-upgrade backup, release-variable merge, image load,
  migration and health. User configuration and secrets are preserved while release image/schema
  values come from the new bundle. Sandbox is preserved automatically; use `--without-sandbox` to
  disable it explicitly.
- `bin/rollback.sh --previous-bundle=PATH --backup=PATH --yes`: old images plus old database/data
  snapshot, with release variables and installation identity restored. Database rollback is never
  attempted by reverse SQL.

Keep `.env`, backup directories and migration exports readable only by the deployment operator.
`NHS_NOTIFICATION_CONFIG_KEY` is installation identity: keep it unchanged during upgrades and
restore it with the database, otherwise existing Webhook and SMTP credentials cannot be decrypted.
Do not place backups in the same filesystem failure domain as the active Docker volumes.
