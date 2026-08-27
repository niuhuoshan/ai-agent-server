-- agent platform schema V8: audit and Nhs migration traceability

BEGIN;

CREATE TABLE IF NOT EXISTS agent_audit_event (
    id                  BIGINT PRIMARY KEY,
    trace_id            VARCHAR(64),
    actor_type          VARCHAR(20) NOT NULL,
    actor_id            BIGINT,
    action              VARCHAR(64) NOT NULL,
    resource_type       VARCHAR(32),
    resource_id         BIGINT,
    task_id             BIGINT,
    run_id              BIGINT,
    permission_profile_version VARCHAR(64),
    decision            VARCHAR(20) NOT NULL,
    decision_reason     TEXT,
    data_scope_json     JSONB,
    request_summary     TEXT,
    result_summary      TEXT,
    ip_address          VARCHAR(64),
    user_agent          VARCHAR(512),
    metadata_json       JSONB,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_audit_actor CHECK (actor_type IN ('user', 'service_account', 'application', 'agent', 'system')),
    CONSTRAINT ck_agent_audit_decision CHECK (decision IN ('allow', 'deny', 'approval_required', 'success', 'failure'))
);

CREATE TABLE IF NOT EXISTS agent_migration_run (
    id                  BIGINT PRIMARY KEY,
    source_system       VARCHAR(32) NOT NULL,
    source_version      VARCHAR(64),
    target_version      VARCHAR(64) NOT NULL,
    migration_type      VARCHAR(16) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'pending',
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    source_count        BIGINT NOT NULL DEFAULT 0,
    target_count        BIGINT NOT NULL DEFAULT 0,
    error_count         BIGINT NOT NULL DEFAULT 0,
    checksum            CHAR(64),
    report_artifact_id  BIGINT,
    operator_id         BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_migration_run_type CHECK (migration_type IN ('full', 'incremental', 'verify')),
    CONSTRAINT ck_agent_migration_run_status CHECK (status IN ('pending', 'running', 'succeeded', 'failed', 'cancelled'))
);

CREATE TABLE IF NOT EXISTS agent_migration_mapping (
    id                  BIGINT PRIMARY KEY,
    migration_run_id    BIGINT NOT NULL,
    source_type         VARCHAR(64) NOT NULL,
    source_id           VARCHAR(128) NOT NULL,
    target_type         VARCHAR(64) NOT NULL,
    target_id           BIGINT,
    source_hash         CHAR(64),
    target_hash         CHAR(64),
    status              VARCHAR(16) NOT NULL,
    error_message       TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_migration_mapping UNIQUE (migration_run_id, source_type, source_id, target_type),
    CONSTRAINT ck_agent_migration_mapping_status CHECK (status IN ('mapped', 'skipped', 'failed'))
);

CREATE TABLE IF NOT EXISTS agent_legacy_execution_archive (
    id                  BIGINT PRIMARY KEY,
    migration_run_id    BIGINT NOT NULL,
    source_system       VARCHAR(32) NOT NULL,
    source_trace_id     VARCHAR(128),
    source_execution_id VARCHAR(128),
    source_agent_id     VARCHAR(128),
    source_user_id      VARCHAR(128),
    source_conversation_id VARCHAR(128),
    source_status       VARCHAR(32),
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    summary             TEXT,
    payload_json        JSONB NOT NULL,
    content_hash        CHAR(64) NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_legacy_execution_identity CHECK (source_trace_id IS NOT NULL OR source_execution_id IS NOT NULL)
);

COMMIT;
