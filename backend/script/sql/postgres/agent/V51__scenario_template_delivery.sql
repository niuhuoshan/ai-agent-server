-- Scenario template catalog and durable delivery workflow.
-- Templates are provider-neutral; resource bindings always point at local ids.
BEGIN;

CREATE TABLE IF NOT EXISTS agent_scenario_template (
    template_key           VARCHAR(128) PRIMARY KEY,
    name                    VARCHAR(255) NOT NULL,
    category                VARCHAR(64) NOT NULL,
    description             TEXT NOT NULL,
    tags_json               JSONB NOT NULL DEFAULT '[]'::jsonb,
    recommended             BOOLEAN NOT NULL DEFAULT FALSE,
    target_departments_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    delivery_time           VARCHAR(64),
    maturity                VARCHAR(64),
    included_capabilities_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    deliverables_json       JSONB NOT NULL DEFAULT '[]'::jsonb,
    business_goals_json     JSONB NOT NULL DEFAULT '[]'::jsonb,
    install_steps_json      JSONB NOT NULL DEFAULT '[]'::jsonb,
    acceptance_criteria_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    required_resources_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    sample_questions_json   JSONB NOT NULL DEFAULT '[]'::jsonb,
    manifest_json           JSONB NOT NULL DEFAULT '{}'::jsonb,
    status                  VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_scenario_template_status CHECK (status IN ('active', 'disabled'))
);

CREATE TABLE IF NOT EXISTS agent_scenario_instance (
    id                      BIGINT PRIMARY KEY,
    template_key            VARCHAR(128) NOT NULL,
    instance_key            VARCHAR(128) NOT NULL,
    display_name            VARCHAR(255) NOT NULL,
    description             TEXT,
    status                  VARCHAR(24) NOT NULL DEFAULT 'installed',
    owner_id                BIGINT NOT NULL,
    agent_id                BIGINT,
    agent_version_id        BIGINT,
    resource_bindings_json  JSONB NOT NULL DEFAULT '{}'::jsonb,
    acceptance_criteria_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    sample_questions_json   JSONB NOT NULL DEFAULT '[]'::jsonb,
    next_steps_json         JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    del_flag                CHAR(1) NOT NULL DEFAULT '0',
    CONSTRAINT uk_agent_scenario_instance_key UNIQUE (template_key, instance_key),
    CONSTRAINT ck_agent_scenario_instance_status CHECK (status IN ('prechecking', 'installing', 'installed', 'failed', 'disabled'))
);

CREATE INDEX IF NOT EXISTS idx_agent_scenario_instance_owner
    ON agent_scenario_instance (owner_id, updated_at DESC) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_scenario_instance_template
    ON agent_scenario_instance (template_key, updated_at DESC) WHERE del_flag = '0';

CREATE TABLE IF NOT EXISTS agent_scenario_install_run (
    id                      BIGINT PRIMARY KEY,
    instance_id             BIGINT NOT NULL,
    template_key            VARCHAR(128) NOT NULL,
    idempotency_key         VARCHAR(128) NOT NULL,
    status                  VARCHAR(24) NOT NULL,
    precheck_json           JSONB NOT NULL DEFAULT '{}'::jsonb,
    resource_bindings_json  JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_summary           VARCHAR(1000),
    created_by              BIGINT NOT NULL,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at            TIMESTAMP,
    CONSTRAINT uk_agent_scenario_install_idempotency UNIQUE (template_key, idempotency_key),
    CONSTRAINT ck_agent_scenario_install_status CHECK (status IN ('prechecked', 'running', 'succeeded', 'failed'))
);

CREATE INDEX IF NOT EXISTS idx_agent_scenario_install_instance
    ON agent_scenario_install_run (instance_id, created_at DESC);

COMMENT ON TABLE agent_scenario_template IS '企业场景模板目录，内容由平台发布维护';
COMMENT ON TABLE agent_scenario_instance IS '场景模板交付后的企业智能体实例';
COMMENT ON TABLE agent_scenario_install_run IS '场景模板预检/安装的幂等运行事实';

COMMIT;
