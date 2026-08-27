-- agent platform schema V31: repeatable Nhs migration, verification and cutover evidence

BEGIN;

ALTER TABLE agent_migration_run
    ADD COLUMN IF NOT EXISTS run_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS source_schema VARCHAR(128),
    ADD COLUMN IF NOT EXISTS source_snapshot_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS manifest_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS verification_status VARCHAR(16) NOT NULL DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS report_json JSONB;

ALTER TABLE agent_migration_run
    DROP CONSTRAINT IF EXISTS ck_agent_migration_run_verification;
ALTER TABLE agent_migration_run
    ADD CONSTRAINT ck_agent_migration_run_verification
        CHECK (verification_status IN ('pending', 'passed', 'failed', 'waived'));

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_migration_run_key
    ON agent_migration_run (source_system, run_key)
    WHERE run_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS agent_migration_entity_stat (
    id                  BIGINT PRIMARY KEY,
    migration_run_id    BIGINT NOT NULL,
    entity_type         VARCHAR(64) NOT NULL,
    phase               VARCHAR(16) NOT NULL DEFAULT 'load',
    status              VARCHAR(16) NOT NULL DEFAULT 'pending',
    source_count        BIGINT NOT NULL DEFAULT 0,
    mapped_count        BIGINT NOT NULL DEFAULT 0,
    inserted_count      BIGINT NOT NULL DEFAULT 0,
    reused_count        BIGINT NOT NULL DEFAULT 0,
    skipped_count       BIGINT NOT NULL DEFAULT 0,
    failed_count        BIGINT NOT NULL DEFAULT 0,
    source_hash         CHAR(64),
    target_hash         CHAR(64),
    detail_json         JSONB,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_migration_entity_stat UNIQUE (migration_run_id, entity_type, phase),
    CONSTRAINT ck_agent_migration_entity_stat_phase
        CHECK (phase IN ('inventory', 'load', 'archive', 'verify')),
    CONSTRAINT ck_agent_migration_entity_stat_status
        CHECK (status IN ('pending', 'running', 'passed', 'failed', 'skipped')),
    CONSTRAINT ck_agent_migration_entity_stat_counts CHECK (
        source_count >= 0 AND mapped_count >= 0 AND inserted_count >= 0
        AND reused_count >= 0 AND skipped_count >= 0 AND failed_count >= 0
    )
);

CREATE INDEX IF NOT EXISTS idx_agent_migration_entity_stat_run
    ON agent_migration_entity_stat (migration_run_id, phase, status);

CREATE TABLE IF NOT EXISTS agent_migration_issue (
    id                  BIGINT PRIMARY KEY,
    migration_run_id    BIGINT NOT NULL,
    entity_type         VARCHAR(64),
    source_id           VARCHAR(128),
    severity            VARCHAR(12) NOT NULL,
    issue_code          VARCHAR(64) NOT NULL,
    summary             TEXT NOT NULL,
    detail_json         JSONB,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_migration_issue_severity
        CHECK (severity IN ('info', 'warning', 'error', 'fatal'))
);

CREATE INDEX IF NOT EXISTS idx_agent_migration_issue_run
    ON agent_migration_issue (migration_run_id, severity, entity_type, issue_code);

CREATE TABLE IF NOT EXISTS agent_migration_checkpoint (
    id                  BIGINT PRIMARY KEY,
    source_system       VARCHAR(32) NOT NULL,
    source_schema       VARCHAR(128) NOT NULL,
    entity_type         VARCHAR(64) NOT NULL,
    last_source_id      VARCHAR(128),
    last_source_updated_at TIMESTAMP,
    snapshot_at         TIMESTAMP NOT NULL,
    source_hash         CHAR(64),
    migration_run_id    BIGINT NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_migration_checkpoint
        UNIQUE (source_system, source_schema, entity_type, snapshot_at)
);

CREATE INDEX IF NOT EXISTS idx_agent_migration_checkpoint_latest
    ON agent_migration_checkpoint (source_system, source_schema, entity_type, snapshot_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_migration_mapping_latest_identity
    ON agent_migration_mapping (migration_run_id, source_type, source_id, target_type);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_legacy_execution_archive_source
    ON agent_legacy_execution_archive (
        migration_run_id,
        source_system,
        COALESCE(source_execution_id, ''),
        COALESCE(source_trace_id, '')
    );

COMMENT ON TABLE agent_migration_entity_stat IS '迁移实体级数量、哈希和阶段验证证据';
COMMENT ON TABLE agent_migration_issue IS '迁移问题清单，仅保存脱敏后的诊断信息';
COMMENT ON TABLE agent_migration_checkpoint IS '全量和增量迁移的不可变源快照断点';

COMMIT;
