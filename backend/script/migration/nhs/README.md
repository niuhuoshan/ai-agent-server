# Nhs migration CLI

The migration tool is a Java 21 fat JAR. It reads Nhs through JDBC and writes only to an
already migrated nhs PostgreSQL database. It never accepts passwords on the command
line and never copies Nhs passwords, API keys, database passwords or authentication headers.

## Build

```bash
./mvnw -pl nhs-modules/nhs-migration-cli -am \
  -Dmaven.test.skip=false package
```

The executable is:

```text
nhs-modules/nhs-migration-cli/target/nhs-migration-cli.jar
```

## Commands

Set these variables in a protected shell or environment file:

```bash
export NHS_SOURCE_JDBC_URL='jdbc:postgresql://source-db:5432/nhs'
export NHS_SOURCE_DB_USER='nhs_migration_reader'
export NHS_SOURCE_DB_PASSWORD='...'
export NHS_SOURCE_DB_SCHEMA='public'
export NHS_TARGET_JDBC_URL='jdbc:postgresql://target-db:5432/agent_server'
export NHS_TARGET_DB_USER='agent_migration_writer'
export NHS_TARGET_DB_PASSWORD='...'
export NHS_MIGRATION_REPORT_DIR='/var/lib/nhs/migration-reports'
```

Run inventory, migration and verification in order:

```bash
java -jar nhs-migration-cli.jar inventory --run-key=inventory-01
java -jar nhs-migration-cli.jar migrate --run-key=rehearsal-01 --migration-type=full
java -jar nhs-migration-cli.jar verify --run-key=verify-01 --run-id=<migration_run_id>
```

Export Redis while Nhs writes are frozen, using the Nhs Python environment where `redis`
is already installed. The export is created atomically with mode `0600`:

```bash
export NHS_REDIS_URL='redis://:password@nhs-redis:6379/0'
python script/migration/nhs/export_nhs_redis.py \
  --output=/protected/nhs-memory.jsonl
```

Import it after the SQL migration. User mappings are resolved from prior successful migration
runs; all conversations remain private/read-only and all long-term memory starts as `pending`
review with sensitivity `sensitive`:

```bash
java -jar nhs-migration-cli.jar memory-import \
  --input=/protected/nhs-memory.jsonl \
  --run-key=memory-import-01
```

Transient keys such as active-conversation pointers, current ChatBI result caches and session tool
artifacts are deliberately not exported. They are cache state, not durable business facts.

New users are disabled and receive no reusable password. Models, connectors and data sources use
`env:NAME` credential references. Migrated tools, reports and schedules remain disabled or paused
until an administrator reviews them. Nhs execution traces are stored in the read-only legacy
archive and are not converted into executable `TaskRun` facts.

The fixture under `fixtures/` deliberately contains old secrets. Rehearsal validation must prove
that none of those marker values occur in target tables or generated reports.
