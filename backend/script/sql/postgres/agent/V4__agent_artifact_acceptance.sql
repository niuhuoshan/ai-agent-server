-- agent platform schema V4: artifacts, acceptance and human approval

BEGIN;

CREATE TABLE IF NOT EXISTS agent_artifact (
    id                  BIGINT PRIMARY KEY,
    project_id          BIGINT,
    task_id             BIGINT,
    run_id              BIGINT,
    step_id             BIGINT,
    artifact_type       VARCHAR(24) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    version_no          INTEGER NOT NULL DEFAULT 1,
    storage_type        VARCHAR(16) NOT NULL,
    storage_ref         VARCHAR(1024) NOT NULL,
    mime_type           VARCHAR(128),
    size_bytes          BIGINT,
    content_hash        CHAR(64),
    sensitive_level     VARCHAR(12) NOT NULL DEFAULT 'internal',
    visibility          VARCHAR(24) NOT NULL DEFAULT 'inherit',
    status              VARCHAR(16) NOT NULL DEFAULT 'created',
    metadata_json       JSONB,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_artifact_storage CHECK (storage_type IN ('local', 'oss', 's3', 'external')),
    CONSTRAINT ck_agent_artifact_sensitive CHECK (sensitive_level IN ('public', 'internal', 'sensitive', 'secret')),
    CONSTRAINT ck_agent_artifact_visibility CHECK (visibility IN ('inherit', 'private', 'enterprise_shared', 'restricted')),
    CONSTRAINT ck_agent_artifact_size CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT ck_agent_artifact_status CHECK (status IN ('created', 'available', 'quarantined', 'deleted'))
);

CREATE TABLE IF NOT EXISTS agent_acceptance_record (
    id                  BIGINT PRIMARY KEY,
    task_id             BIGINT NOT NULL,
    run_id              BIGINT NOT NULL,
    artifact_ids_json   JSONB,
    acceptance_type     VARCHAR(16) NOT NULL,
    result              VARCHAR(16) NOT NULL DEFAULT 'pending',
    rule_result_json    JSONB,
    comment             TEXT,
    reviewer_id         BIGINT,
    rework_no           INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_acceptance_type CHECK (acceptance_type IN ('rule', 'human', 'combined')),
    CONSTRAINT ck_agent_acceptance_result CHECK (result IN ('pending', 'passed', 'rework', 'rejected', 'taken_over'))
);

CREATE TABLE IF NOT EXISTS agent_approval_request (
    id                  BIGINT PRIMARY KEY,
    task_id             BIGINT,
    run_id              BIGINT NOT NULL,
    step_id             BIGINT,
    tool_id             BIGINT,
    risk_level          VARCHAR(8) NOT NULL,
    action_summary      TEXT NOT NULL,
    input_summary       TEXT,
    impact_scope        TEXT,
    credential_ref      VARCHAR(255),
    status              VARCHAR(20) NOT NULL DEFAULT 'pending',
    requested_by        BIGINT,
    reviewer_id         BIGINT,
    review_comment      TEXT,
    expires_at          TIMESTAMP,
    decision_token_hash CHAR(64),
    decided_at          TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_approval_risk CHECK (risk_level IN ('R0', 'R1', 'R2', 'R3')),
    CONSTRAINT ck_agent_approval_status CHECK (status IN ('pending', 'approved', 'rejected', 'revoked', 'expired'))
);

COMMIT;
