-- agent platform schema V6: data catalog, read-only ChatBI and reports

BEGIN;

CREATE TABLE IF NOT EXISTS agent_data_source (
    id                  BIGINT PRIMARY KEY,
    source_key          VARCHAR(128) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    db_type             VARCHAR(32) NOT NULL,
    endpoint_url        VARCHAR(1024) NOT NULL,
    database_name       VARCHAR(255),
    credential_ref      VARCHAR(255),
    readonly            BOOLEAN NOT NULL DEFAULT TRUE,
    status              VARCHAR(16) NOT NULL DEFAULT 'testing',
    config_json         JSONB,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_data_source_status CHECK (status IN ('active', 'disabled', 'testing', 'error')),
    CONSTRAINT ck_agent_data_source_readonly CHECK (readonly)
);

CREATE TABLE IF NOT EXISTS agent_data_dataset (
    id                  BIGINT PRIMARY KEY,
    data_source_id      BIGINT NOT NULL,
    dataset_key         VARCHAR(128) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    description         TEXT,
    status              VARCHAR(16) NOT NULL DEFAULT 'disabled',
    enable_row_policy   BOOLEAN NOT NULL DEFAULT FALSE,
    row_policy_json     JSONB,
    external_knowledge_id VARCHAR(128),
    owner_id            BIGINT,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_data_dataset_status CHECK (status IN ('active', 'disabled', 'syncing', 'error'))
);

CREATE TABLE IF NOT EXISTS agent_data_table (
    id                  BIGINT PRIMARY KEY,
    dataset_id          BIGINT NOT NULL,
    table_key           VARCHAR(128) NOT NULL,
    physical_name       VARCHAR(255) NOT NULL,
    display_name        VARCHAR(255) NOT NULL,
    description         TEXT,
    table_type          VARCHAR(24) NOT NULL DEFAULT 'table',
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    synonyms_json       JSONB,
    metadata_json       JSONB,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB
);

CREATE TABLE IF NOT EXISTS agent_data_column (
    id                  BIGINT PRIMARY KEY,
    table_id            BIGINT NOT NULL,
    column_key          VARCHAR(128) NOT NULL,
    physical_name       VARCHAR(255) NOT NULL,
    display_name        VARCHAR(255) NOT NULL,
    data_type           VARCHAR(128) NOT NULL,
    description         TEXT,
    is_primary          BOOLEAN NOT NULL DEFAULT FALSE,
    is_sensitive        BOOLEAN NOT NULL DEFAULT FALSE,
    enum_json           JSONB,
    synonyms_json       JSONB,
    sample_values_json  JSONB,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,
    CONSTRAINT uk_agent_data_column_key UNIQUE (table_id, column_key),
    CONSTRAINT uk_agent_data_column_physical UNIQUE (table_id, physical_name)
);

CREATE TABLE IF NOT EXISTS agent_data_metric (
    id                  BIGINT PRIMARY KEY,
    dataset_id          BIGINT NOT NULL,
    metric_key          VARCHAR(128) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    description         TEXT,
    calculation_logic   TEXT NOT NULL,
    unit                VARCHAR(64),
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    version_no          INTEGER NOT NULL DEFAULT 1,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,
    CONSTRAINT uk_agent_data_metric UNIQUE (dataset_id, metric_key, version_no)
);

CREATE TABLE IF NOT EXISTS agent_data_relation (
    id                  BIGINT PRIMARY KEY,
    dataset_id          BIGINT NOT NULL,
    source_table_id     BIGINT NOT NULL,
    target_table_id     BIGINT NOT NULL,
    join_type           VARCHAR(16) NOT NULL DEFAULT 'inner',
    join_condition      TEXT NOT NULL,
    description         TEXT,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_data_relation_join CHECK (join_type IN ('inner', 'left', 'right', 'full'))
);

CREATE TABLE IF NOT EXISTS agent_data_query (
    id                  BIGINT PRIMARY KEY,
    task_id             BIGINT,
    run_id              BIGINT,
    conversation_id     BIGINT,
    data_source_id      BIGINT NOT NULL,
    dataset_id          BIGINT NOT NULL,
    user_query          TEXT NOT NULL,
    sql_plan_json       JSONB,
    sql_text            TEXT,
    sql_hash            CHAR(64),
    permission_summary_json JSONB,
    row_count           BIGINT,
    result_artifact_id  BIGINT,
    status              VARCHAR(16) NOT NULL DEFAULT 'planning',
    error_summary       TEXT,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_data_query_status CHECK (status IN ('planning', 'approved', 'running', 'succeeded', 'failed', 'rejected', 'cancelled'))
);

CREATE TABLE IF NOT EXISTS agent_report (
    id                  BIGINT PRIMARY KEY,
    report_key          VARCHAR(128) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    dataset_id          BIGINT NOT NULL,
    sql_template        TEXT NOT NULL,
    params_schema_json  JSONB,
    visibility          VARCHAR(24) NOT NULL DEFAULT 'private',
    owner_id            BIGINT,
    status              VARCHAR(16) NOT NULL DEFAULT 'draft',
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_report_visibility CHECK (visibility IN ('private', 'enterprise_shared', 'restricted')),
    CONSTRAINT ck_agent_report_status CHECK (status IN ('draft', 'active', 'disabled', 'archived'))
);

CREATE TABLE IF NOT EXISTS agent_report_run (
    id                  BIGINT PRIMARY KEY,
    report_id           BIGINT NOT NULL,
    run_id              BIGINT,
    trigger_type        VARCHAR(20) NOT NULL,
    resolved_params_json JSONB,
    executed_sql        TEXT,
    result_artifact_id  BIGINT,
    row_count           BIGINT,
    status              VARCHAR(16) NOT NULL DEFAULT 'queued',
    error_summary       TEXT,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agent_report_subscription (
    id                  BIGINT PRIMARY KEY,
    report_id           BIGINT NOT NULL,
    trigger_id          BIGINT,
    timezone            VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    params_json         JSONB,
    notify_policy_json  JSONB,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    last_run_at         TIMESTAMP,
    next_run_at         TIMESTAMP,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB
);

COMMIT;
