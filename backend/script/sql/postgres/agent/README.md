# Agent Platform PostgreSQL Schema

The current migration head is `V94`; V89 adds isolated browser control sessions,
V90/V93 add model credential persistence, V91 adds conversation branch context,
and V92 adds durable browser Tab state and active-Tab selection; V94 adds durable
browser human-handoff state and AI-operation gating. These migrations
build on the durable `waiting_user_question` conversation-turn state and atomic
resume claim after the user-question interaction and bounded execution timeline
snapshots.

## Baseline

- PostgreSQL 16 or later.
- pgvector must be installed on the server; V1 enables the `vector` extension.
- Run the NHS PostgreSQL base scripts before these platform migrations.
- The scripts intentionally contain no cross-table foreign keys or cascade deletes.

## Order

The application runs all released migration scripts through `V94` with Flyway before accepting traffic. Existing private deployments are baselined at version `0`, then all idempotent platform migrations are applied; `V58` is intentionally reserved and has no script. V11 adds Chinese table and column comments for the original platform baseline; V56 repairs historical gaps added after that baseline; V57 adds owner-scoped conversation canvases and immutable version history; V59 adds the Embed widget runtime; V60 adds governed web-search health, circuit and invocation facts; V61 adds private Agent debug runs; V62 adds platform configuration history; V63 adds Embed execution leases; V64 adds durable scenario-template uninstall runs; V65 adds OpenClaw provider artifacts; V66 adds durable log-retention maintenance; V67 adds binary-safe Skill bundle storage; V68 adds versioned metadata governance and content-addressed change history; V69 adds recoverable metadata-profile jobs, relationship recommendations, and selective smart-import previews; V70 adds canonical metadata YAML export support, durable DDL/YAML import previews and items, selective atomic application, declared table/column state, and related metadata-catalog import support; V71 adds local knowledge virtual directories and an independent document-catalog revision; V72 adds the remaining runtime auxiliary built-in tool facts; V73 adds identity-provider and user synchronization state; V74 adds private memory vector/runtime state; V75 adds dashboard token facts and quota projections; V76 adds Skill publication workflow and immutable snapshots; V77 adds ChatBI evidence, result references, and drilldown snapshots; V78 adds durable runtime confirmation and Agent delegation resume state; V84 adds version-scoped explicit Skill dependency installation status; V85 adds the immutable Skill manifest and shared workspace bridge for Sandbox Runner jobs; V86 adds durable Agent-initiated user questions; V87 adds bounded, redacted execution timeline snapshots; V88 adds the durable waiting-user-question turn state and resume claim; V89 adds owner-scoped browser sessions, worker leases, and browser events; V90/V93 add model credential persistence; V91 adds conversation branch context; V92 adds browser Tab state and active-Tab selection; V94 adds browser human-handoff state and AI-operation gating. Later migrations must comment every new object explicitly and remain compatible with this full replay.

Keep physical table and column names in English for stable Java, MyBatis, and API mappings. Every platform table and column must have a native PostgreSQL Chinese comment. V11 fails if a platform object is missing or any comment cannot be generated.

```bash
find . -maxdepth 1 -name 'V*.sql' -print0 \
  | sort -zV \
  | xargs -0 -n1 psql -v ON_ERROR_STOP=1 -d agent_server -f
```

Released migration files are immutable. Add a new version for later schema changes instead of editing an already deployed version.

## Verification

```sql
SELECT count(*)
FROM pg_tables
WHERE schemaname = 'public'
  AND (tablename LIKE 'agent_%' OR tablename LIKE 'iam_%' OR tablename = 'task_access_rule');
-- expected: 116

SELECT count(*)
FROM pg_constraint c
JOIN pg_class t ON t.oid = c.conrelid
WHERE (t.relname LIKE 'agent_%' OR t.relname LIKE 'iam_%' OR t.relname = 'task_access_rule')
  AND c.contype = 'f';
-- expected: 0

SELECT count(*)
FROM pg_indexes
WHERE schemaname = 'public'
  AND (tablename LIKE 'agent_%' OR tablename LIKE 'iam_%' OR tablename = 'task_access_rule');
-- expected: 429

SELECT count(*)
FROM information_schema.columns
WHERE table_schema = 'public'
  AND (table_name LIKE 'agent_%' OR table_name LIKE 'iam_%' OR table_name = 'task_access_rule')
  AND column_name = 'tenant_id';
-- expected: 0

SELECT count(*)
FROM information_schema.columns
WHERE table_schema = 'public'
  AND (table_name LIKE 'agent_%' OR table_name LIKE 'iam_%' OR table_name = 'task_access_rule');
-- expected: 1655

SELECT extversion FROM pg_extension WHERE extname = 'vector';
-- verified: 0.8.4

SELECT count(*) AS columns_without_chinese_comment
FROM pg_class t
JOIN pg_namespace n ON n.oid = t.relnamespace
JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum > 0 AND NOT a.attisdropped
LEFT JOIN pg_description d ON d.objoid = t.oid AND d.objsubid = a.attnum
WHERE n.nspname = 'public'
  AND t.relkind = 'r'
  AND (t.relname LIKE 'agent_%' OR t.relname LIKE 'iam_%' OR t.relname = 'task_access_rule')
  AND (d.description IS NULL OR d.description !~ '[一-龥]');
-- expected: 0

SELECT count(*) AS tables_without_chinese_comment
FROM pg_class t
JOIN pg_namespace n ON n.oid = t.relnamespace
LEFT JOIN pg_description d ON d.objoid = t.oid AND d.objsubid = 0
WHERE n.nspname = 'public'
  AND t.relkind = 'r'
  AND (t.relname LIKE 'agent_%' OR t.relname LIKE 'iam_%' OR t.relname = 'task_access_rule')
  AND (d.description IS NULL OR d.description !~ '[一-龥]');
-- expected: 0
```

AgentScope durable state uses `agentscope.agentscope_sessions`. It is intentionally outside the public business schema but follows the same rules: no foreign keys, no `tenant_id`, and Chinese table/column comments are required.

Do not place model keys, database passwords, API secrets, or connector credentials in seed SQL. Store only encrypted credential references or one-way secret hashes.
