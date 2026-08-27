-- agent platform schema V3: conversation and execution facts

BEGIN;

CREATE TABLE IF NOT EXISTS agent_conversation (
    id                  BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    project_id          BIGINT,
    task_id             BIGINT,
    agent_id            BIGINT,
    agent_version_id    BIGINT,
    title               VARCHAR(255),
    visibility          VARCHAR(16) NOT NULL DEFAULT 'private',
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    session_key         VARCHAR(128) NOT NULL,
    last_message_at     TIMESTAMP,
    summary             TEXT,
    metadata_json       JSONB,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_conversation_visibility CHECK (visibility = 'private'),
    CONSTRAINT ck_agent_conversation_status CHECK (status IN ('active', 'archived', 'deleted'))
);

CREATE TABLE IF NOT EXISTS agent_conversation_message (
    id                  BIGINT PRIMARY KEY,
    conversation_id     BIGINT NOT NULL,
    seq_no              INTEGER NOT NULL,
    trace_id            VARCHAR(64),
    role                VARCHAR(16) NOT NULL,
    content             TEXT,
    content_json        JSONB,
    agent_id            BIGINT,
    agent_version_id    BIGINT,
    model_id            BIGINT,
    status              VARCHAR(16) NOT NULL DEFAULT 'completed',
    prompt_tokens       INTEGER NOT NULL DEFAULT 0,
    completion_tokens   INTEGER NOT NULL DEFAULT 0,
    total_tokens        INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_conversation_message UNIQUE (conversation_id, seq_no),
    CONSTRAINT ck_agent_conversation_message_role CHECK (role IN ('user', 'assistant', 'tool', 'system')),
    CONSTRAINT ck_agent_conversation_message_status CHECK (status IN ('streaming', 'completed', 'failed', 'cancelled'))
);

CREATE TABLE IF NOT EXISTS agent_task_run (
    id                  BIGINT PRIMARY KEY,
    task_id             BIGINT NOT NULL,
    task_version_id     BIGINT NOT NULL,
    workflow_version_id BIGINT,
    trace_id            VARCHAR(64) NOT NULL,
    status              VARCHAR(24) NOT NULL DEFAULT 'queued',
    attempt_no          INTEGER NOT NULL DEFAULT 1,
    parent_run_id       BIGINT,
    worker_id           VARCHAR(128),
    lease_until         TIMESTAMP,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    wait_reason         VARCHAR(64),
    error_code          VARCHAR(64),
    error_summary       TEXT,
    cancel_reason       TEXT,
    authorization_snapshot_json JSONB,
    runtime_snapshot_json JSONB,
    budget_snapshot_json JSONB,
    usage_json          JSONB,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_task_run_attempt UNIQUE (task_id, attempt_no),
    CONSTRAINT uk_agent_task_run_trace UNIQUE (trace_id),
    CONSTRAINT ck_agent_task_run_status CHECK (status IN ('queued', 'preparing', 'running', 'waiting_approval', 'waiting_input', 'blocked', 'verifying', 'succeeded', 'paused', 'failed', 'cancelled', 'expired'))
);

CREATE TABLE IF NOT EXISTS agent_run_step (
    id                  BIGINT PRIMARY KEY,
    run_id              BIGINT NOT NULL,
    step_key            VARCHAR(128) NOT NULL,
    parent_step_id      BIGINT,
    step_type           VARCHAR(24) NOT NULL,
    sequence_no         INTEGER NOT NULL,
    status              VARCHAR(24) NOT NULL DEFAULT 'pending',
    agent_version_id    BIGINT,
    tool_id             BIGINT,
    input_summary       TEXT,
    output_summary      TEXT,
    input_json          JSONB,
    output_json         JSONB,
    error_code          VARCHAR(64),
    error_summary       TEXT,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    retry_count         INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_run_step_sequence UNIQUE (run_id, sequence_no),
    CONSTRAINT ck_agent_run_step_type CHECK (step_type IN ('agent', 'tool', 'human', 'approval', 'condition', 'aggregate')),
    CONSTRAINT ck_agent_run_step_status CHECK (status IN ('pending', 'running', 'waiting', 'succeeded', 'failed', 'skipped', 'cancelled'))
);

CREATE TABLE IF NOT EXISTS agent_execution_event (
    id                  BIGINT PRIMARY KEY,
    event_id            VARCHAR(64) NOT NULL,
    trace_id            VARCHAR(64) NOT NULL,
    conversation_id     BIGINT,
    run_id              BIGINT,
    step_id             BIGINT,
    cursor              BIGINT NOT NULL,
    parent_event_id     VARCHAR(64),
    event_type          VARCHAR(48) NOT NULL,
    event_status        VARCHAR(20) NOT NULL DEFAULT 'success',
    summary             TEXT,
    payload_json        JSONB,
    sensitive_level     VARCHAR(12) NOT NULL DEFAULT 'internal',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_execution_event_id UNIQUE (event_id),
    CONSTRAINT ck_agent_execution_event_status CHECK (event_status IN ('pending', 'success', 'failed')),
    CONSTRAINT ck_agent_execution_event_sensitive CHECK (sensitive_level IN ('public', 'internal', 'sensitive', 'secret'))
);

CREATE TABLE IF NOT EXISTS agent_run_checkpoint (
    id                  BIGINT PRIMARY KEY,
    run_id              BIGINT NOT NULL,
    step_id             BIGINT,
    checkpoint_no       INTEGER NOT NULL,
    state_type          VARCHAR(24) NOT NULL,
    state_ref           VARCHAR(255),
    state_hash          CHAR(64),
    state_json          JSONB,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_run_checkpoint UNIQUE (run_id, checkpoint_no),
    CONSTRAINT ck_agent_run_checkpoint_type CHECK (state_type IN ('session', 'workspace', 'approval', 'workflow'))
);

COMMIT;
