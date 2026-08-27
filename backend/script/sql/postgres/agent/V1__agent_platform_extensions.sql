-- agent platform schema V1
-- Target: PostgreSQL 16+ with pgvector
-- This migration deliberately has no cross-table foreign keys. References are
-- validated by the application transaction and protected by V9 indexes.

BEGIN;

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS agent_model (
    id                  BIGINT PRIMARY KEY,
    model_key           VARCHAR(128) NOT NULL,
    display_name        VARCHAR(128) NOT NULL,
    provider_type       VARCHAR(32) NOT NULL,
    model_name          VARCHAR(255) NOT NULL,
    model_type          VARCHAR(20) NOT NULL,
    endpoint_url        VARCHAR(512),
    credential_ref      VARCHAR(255),
    context_size        INTEGER,
    max_output_tokens   INTEGER,
    reasoning_config_json JSONB,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    capability_json     JSONB,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_model_status CHECK (status IN ('active', 'disabled', 'testing')),
    CONSTRAINT ck_agent_model_type CHECK (model_type IN ('chat', 'embedding', 'multimodal', 'rerank'))
);

CREATE TABLE IF NOT EXISTS agent_definition (
    id                  BIGINT PRIMARY KEY,
    agent_key           VARCHAR(128) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    description         TEXT,
    agent_type          VARCHAR(32) NOT NULL DEFAULT 'general',
    engine_type         VARCHAR(32) NOT NULL DEFAULT 'agentscope_java',
    avatar_url          VARCHAR(512),
    is_system           BOOLEAN NOT NULL DEFAULT FALSE,
    is_default          BOOLEAN NOT NULL DEFAULT FALSE,
    status              VARCHAR(16) NOT NULL DEFAULT 'draft',
    owner_id            BIGINT,
    sort_order          INTEGER NOT NULL DEFAULT 0,
    engine_config_json  JSONB,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_definition_status CHECK (status IN ('draft', 'active', 'disabled', 'archived'))
);

CREATE TABLE IF NOT EXISTS agent_definition_version (
    id                  BIGINT PRIMARY KEY,
    agent_id            BIGINT NOT NULL,
    version_no          INTEGER NOT NULL,
    system_prompt       TEXT NOT NULL,
    model_id            BIGINT,
    synthesis_model_id  BIGINT,
    runtime_config_json JSONB,
    welcome_config_json JSONB,
    routing_tags_json   JSONB,
    status              VARCHAR(16) NOT NULL DEFAULT 'draft',
    content_hash        CHAR(64) NOT NULL,
    published_at        TIMESTAMP,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_definition_version UNIQUE (agent_id, version_no),
    CONSTRAINT ck_agent_definition_version_status CHECK (status IN ('draft', 'published', 'archived'))
);

CREATE TABLE IF NOT EXISTS agent_connector (
    id                  BIGINT PRIMARY KEY,
    connector_key       VARCHAR(128) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    provider_type       VARCHAR(32) NOT NULL,
    endpoint_url        VARCHAR(1024),
    credential_ref      VARCHAR(255),
    config_json         JSONB,
    status              VARCHAR(16) NOT NULL DEFAULT 'testing',
    last_check_at       TIMESTAMP,
    last_error          TEXT,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_connector_status CHECK (status IN ('active', 'disabled', 'testing'))
);

CREATE TABLE IF NOT EXISTS agent_tool (
    id                  BIGINT PRIMARY KEY,
    tool_key            VARCHAR(128) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    description         TEXT,
    connector_id        BIGINT,
    tool_type           VARCHAR(24) NOT NULL,
    risk_level          VARCHAR(8) NOT NULL DEFAULT 'R1',
    parameter_schema_json JSONB,
    execution_policy_json JSONB,
    external_name       VARCHAR(255),
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    version_no          INTEGER NOT NULL DEFAULT 1,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_tool_risk CHECK (risk_level IN ('R0', 'R1', 'R2', 'R3')),
    CONSTRAINT ck_agent_tool_status CHECK (status IN ('active', 'disabled', 'deprecated'))
);

CREATE TABLE IF NOT EXISTS agent_skill (
    id                  BIGINT PRIMARY KEY,
    skill_key           VARCHAR(128) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    scope_type          VARCHAR(16) NOT NULL DEFAULT 'system',
    scope_id            BIGINT,
    owner_id            BIGINT,
    status              VARCHAR(16) NOT NULL DEFAULT 'draft',
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_skill_scope CHECK (scope_type IN ('system', 'project', 'user')),
    CONSTRAINT ck_agent_skill_status CHECK (status IN ('draft', 'active', 'disabled', 'archived'))
);

CREATE TABLE IF NOT EXISTS agent_skill_version (
    id                  BIGINT PRIMARY KEY,
    skill_id            BIGINT NOT NULL,
    version_no          INTEGER NOT NULL,
    content             TEXT NOT NULL,
    content_hash        CHAR(64) NOT NULL,
    manifest_json       JSONB,
    runtime_requirements_json JSONB,
    status              VARCHAR(16) NOT NULL DEFAULT 'draft',
    published_at        TIMESTAMP,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_skill_version UNIQUE (skill_id, version_no),
    CONSTRAINT ck_agent_skill_version_status CHECK (status IN ('draft', 'published', 'archived'))
);

CREATE TABLE IF NOT EXISTS agent_agent_version_tool (
    id                  BIGINT PRIMARY KEY,
    agent_version_id    BIGINT NOT NULL,
    resource_id         BIGINT NOT NULL,
    permission          VARCHAR(16) NOT NULL DEFAULT 'use',
    config_json         JSONB,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_version_tool UNIQUE (agent_version_id, resource_id)
);

CREATE TABLE IF NOT EXISTS agent_agent_version_skill (
    id                  BIGINT PRIMARY KEY,
    agent_version_id    BIGINT NOT NULL,
    resource_id         BIGINT NOT NULL,
    permission          VARCHAR(16) NOT NULL DEFAULT 'use',
    config_json         JSONB,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_version_skill UNIQUE (agent_version_id, resource_id)
);

CREATE TABLE IF NOT EXISTS agent_agent_version_knowledge (
    id                  BIGINT PRIMARY KEY,
    agent_version_id    BIGINT NOT NULL,
    resource_id         BIGINT NOT NULL,
    permission          VARCHAR(16) NOT NULL DEFAULT 'read',
    config_json         JSONB,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_version_knowledge UNIQUE (agent_version_id, resource_id)
);

COMMIT;
