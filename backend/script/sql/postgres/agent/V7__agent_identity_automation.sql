-- agent platform schema V7: machine identity and automation

BEGIN;

CREATE TABLE IF NOT EXISTS agent_service_account (
    id                  BIGINT PRIMARY KEY,
    account_key         VARCHAR(128) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    description         TEXT,
    owner_id            BIGINT,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    last_used_at        TIMESTAMP,
    expires_at          TIMESTAMP,
    metadata_json       JSONB,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_service_account_status CHECK (status IN ('active', 'disabled', 'expired', 'revoked'))
);

CREATE TABLE IF NOT EXISTS agent_api_application (
    id                  BIGINT PRIMARY KEY,
    app_key             VARCHAR(128) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    app_type            VARCHAR(20) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    owner_id            BIGINT,
    callback_url        VARCHAR(1024),
    scope_json          JSONB,
    expires_at          TIMESTAMP,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_api_application_type CHECK (app_type IN ('embed', 'open_api', 'webhook', 'internal')),
    CONSTRAINT ck_agent_api_application_status CHECK (status IN ('active', 'disabled', 'expired', 'revoked'))
);

CREATE TABLE IF NOT EXISTS agent_api_credential (
    id                  BIGINT PRIMARY KEY,
    application_id      BIGINT NOT NULL,
    service_account_id  BIGINT,
    key_prefix          VARCHAR(32) NOT NULL,
    secret_hash         CHAR(64) NOT NULL,
    secret_ciphertext   TEXT,
    scope_json          JSONB,
    last_used_at        TIMESTAMP,
    expires_at          TIMESTAMP,
    revoked_at          TIMESTAMP,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_api_credential_prefix UNIQUE (application_id, key_prefix),
    CONSTRAINT uk_agent_api_credential_hash UNIQUE (secret_hash)
);

CREATE TABLE IF NOT EXISTS agent_automation_trigger (
    id                  BIGINT PRIMARY KEY,
    trigger_key         VARCHAR(128) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    trigger_type        VARCHAR(20) NOT NULL,
    task_version_id     BIGINT NOT NULL,
    service_account_id  BIGINT NOT NULL,
    cron_expr           VARCHAR(128),
    timezone            VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    event_filter_json   JSONB,
    idempotency_key_expr VARCHAR(255),
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    last_run_at         TIMESTAMP,
    next_run_at         TIMESTAMP,
    config_json         JSONB,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_automation_trigger_type CHECK (trigger_type IN ('manual', 'cron', 'webhook', 'event')),
    CONSTRAINT ck_agent_automation_trigger_status CHECK (status IN ('active', 'paused', 'error', 'archived')),
    CONSTRAINT ck_agent_automation_cron CHECK (trigger_type <> 'cron' OR cron_expr IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS agent_job_queue (
    id                  BIGINT PRIMARY KEY,
    job_type            VARCHAR(64) NOT NULL,
    biz_key             VARCHAR(255) NOT NULL,
    payload_json        JSONB,
    status              VARCHAR(16) NOT NULL DEFAULT 'queued',
    priority            INTEGER NOT NULL DEFAULT 0,
    attempt_no          INTEGER NOT NULL DEFAULT 0,
    max_attempts        INTEGER NOT NULL DEFAULT 3,
    available_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until         TIMESTAMP,
    worker_id           VARCHAR(128),
    last_error          TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,
    CONSTRAINT uk_agent_job_queue UNIQUE (job_type, biz_key),
    CONSTRAINT ck_agent_job_queue_status CHECK (status IN ('queued', 'running', 'success', 'failed', 'dead')),
    CONSTRAINT ck_agent_job_queue_attempt CHECK (attempt_no >= 0 AND max_attempts > 0)
);

CREATE TABLE IF NOT EXISTS agent_notification (
    id                  BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    category            VARCHAR(20) NOT NULL,
    level               VARCHAR(16) NOT NULL DEFAULT 'info',
    title               VARCHAR(255) NOT NULL,
    content             TEXT,
    resource_type       VARCHAR(32),
    resource_id         BIGINT,
    metadata_json       JSONB,
    read_at             TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_notification_category CHECK (category IN ('task', 'approval', 'artifact', 'system')),
    CONSTRAINT ck_agent_notification_level CHECK (level IN ('info', 'success', 'warning', 'error'))
);

CREATE TABLE IF NOT EXISTS agent_outbox_event (
    id                  BIGINT PRIMARY KEY,
    event_type          VARCHAR(64) NOT NULL,
    aggregate_type      VARCHAR(32) NOT NULL,
    aggregate_id        BIGINT NOT NULL,
    event_key           VARCHAR(128) NOT NULL,
    payload_json        JSONB NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'pending',
    attempt_no          INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at        TIMESTAMP,
    last_error          TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_outbox_event UNIQUE (event_key),
    CONSTRAINT ck_agent_outbox_event_status CHECK (status IN ('pending', 'published', 'failed'))
);

COMMIT;
