-- agent platform schema V2: project, task and workflow control plane

BEGIN;

CREATE TABLE IF NOT EXISTS agent_project (
    id                  BIGINT PRIMARY KEY,
    project_key         VARCHAR(64) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    description         TEXT,
    status              VARCHAR(20) NOT NULL DEFAULT 'active',
    owner_id            BIGINT,
    default_agent_version_id BIGINT,
    workspace_policy_json JSONB,
    notification_policy_json JSONB,
    tags_json           JSONB,
    archived_at         TIMESTAMP,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_project_status CHECK (status IN ('active', 'archived', 'suspended'))
);

CREATE TABLE IF NOT EXISTS agent_project_member (
    id                  BIGINT PRIMARY KEY,
    project_id          BIGINT NOT NULL,
    user_id             BIGINT NOT NULL,
    member_role         VARCHAR(20) NOT NULL DEFAULT 'member',
    permission_json     JSONB,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    joined_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_project_member_role CHECK (member_role IN ('owner', 'manager', 'member', 'viewer')),
    CONSTRAINT ck_agent_project_member_status CHECK (status IN ('active', 'removed'))
);

CREATE TABLE IF NOT EXISTS agent_project_rule (
    id                  BIGINT PRIMARY KEY,
    project_id          BIGINT NOT NULL,
    rule_type           VARCHAR(32) NOT NULL,
    version_no          INTEGER NOT NULL,
    content             TEXT,
    content_hash        CHAR(64),
    status              VARCHAR(16) NOT NULL DEFAULT 'draft',
    effective_at        TIMESTAMP,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT uk_agent_project_rule UNIQUE (project_id, rule_type, version_no),
    CONSTRAINT ck_agent_project_rule_status CHECK (status IN ('draft', 'active', 'archived'))
);

CREATE TABLE IF NOT EXISTS agent_task (
    id                  BIGINT PRIMARY KEY,
    task_key            VARCHAR(64) NOT NULL,
    project_id          BIGINT,
    title               VARCHAR(255) NOT NULL,
    objective           TEXT NOT NULL,
    background          TEXT,
    source_conversation_id BIGINT,
    context_snapshot_json JSONB,
    visibility          VARCHAR(24) NOT NULL DEFAULT 'enterprise_shared',
    category            VARCHAR(24) NOT NULL DEFAULT 'general',
    orchestration_mode  VARCHAR(24) NOT NULL DEFAULT 'single_agent',
    lifecycle_level     VARCHAR(16) NOT NULL DEFAULT 'L1_short_task',
    risk_level          VARCHAR(16) NOT NULL DEFAULT 'R1',
    status              VARCHAR(24) NOT NULL DEFAULT 'draft',
    importance          SMALLINT NOT NULL DEFAULT 0,
    urgency             SMALLINT NOT NULL DEFAULT 0,
    queue_priority      INTEGER NOT NULL DEFAULT 0,
    owner_id            BIGINT,
    start_at            TIMESTAMP,
    current_version_id  BIGINT,
    latest_run_id       BIGINT,
    acceptance_mode     VARCHAR(24) NOT NULL DEFAULT 'human',
    acceptance_config_json JSONB,
    budget_json         JSONB,
    external_refs_json  JSONB,
    tags_json           JSONB,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_task_priority CHECK (importance IN (0, 1) AND urgency IN (0, 1)),
    CONSTRAINT ck_agent_task_visibility CHECK (visibility IN ('enterprise_shared', 'restricted')),
    CONSTRAINT ck_agent_task_category CHECK (category IN ('development', 'data', 'knowledge', 'operations', 'document', 'general')),
    CONSTRAINT ck_agent_task_orchestration CHECK (orchestration_mode IN ('single_agent', 'multi_agent_template', 'human_in_loop', 'hybrid')),
    CONSTRAINT ck_agent_task_lifecycle CHECK (lifecycle_level IN ('L0_chat', 'L1_short_task', 'L2_workflow_task', 'L3_recurring_task')),
    CONSTRAINT ck_agent_task_risk CHECK (risk_level IN ('R0', 'R1', 'R2', 'R3')),
    CONSTRAINT ck_agent_task_acceptance CHECK (acceptance_mode IN ('rule', 'human', 'combined')),
    CONSTRAINT ck_agent_task_status CHECK (status IN ('draft', 'ready', 'scheduled', 'running', 'verifying', 'rework', 'completed', 'blocked', 'cancelled', 'archived'))
);

CREATE TABLE IF NOT EXISTS agent_task_version (
    id                  BIGINT PRIMARY KEY,
    task_id             BIGINT NOT NULL,
    version_no          INTEGER NOT NULL,
    title               VARCHAR(255) NOT NULL,
    objective           TEXT NOT NULL,
    agent_version_id    BIGINT,
    workflow_version_id BIGINT,
    context_snapshot_json JSONB,
    resource_snapshot_json JSONB,
    acceptance_snapshot_json JSONB,
    input_snapshot_json JSONB,
    content_hash        CHAR(64) NOT NULL,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_task_version UNIQUE (task_id, version_no)
);

CREATE TABLE IF NOT EXISTS agent_task_participant (
    id                  BIGINT PRIMARY KEY,
    task_id             BIGINT NOT NULL,
    user_id             BIGINT NOT NULL,
    participant_type    VARCHAR(20) NOT NULL,
    source              VARCHAR(20) NOT NULL DEFAULT 'manual',
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_task_participant_type CHECK (participant_type IN ('owner', 'assignee', 'collaborator', 'acceptor', 'watcher')),
    CONSTRAINT ck_agent_task_participant_source CHECK (source IN ('manual', 'template', 'system')),
    CONSTRAINT ck_agent_task_participant_status CHECK (status IN ('active', 'removed'))
);

CREATE TABLE IF NOT EXISTS agent_task_resource (
    id                  BIGINT PRIMARY KEY,
    task_id             BIGINT NOT NULL,
    resource_type       VARCHAR(32) NOT NULL,
    resource_id         BIGINT NOT NULL,
    permission          VARCHAR(20) NOT NULL,
    required            BOOLEAN NOT NULL DEFAULT TRUE,
    grant_source        VARCHAR(24) NOT NULL DEFAULT 'user',
    grant_snapshot_json JSONB,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_task_resource UNIQUE (task_id, resource_type, resource_id, permission),
    CONSTRAINT ck_agent_task_resource_permission CHECK (permission IN ('read', 'use', 'write', 'admin')),
    CONSTRAINT ck_agent_task_resource_source CHECK (grant_source IN ('user', 'project', 'agent', 'template'))
);

CREATE TABLE IF NOT EXISTS agent_workflow_definition (
    id                  BIGINT PRIMARY KEY,
    workflow_key        VARCHAR(64) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    workflow_type       VARCHAR(24) NOT NULL DEFAULT 'fixed_template',
    status              VARCHAR(16) NOT NULL DEFAULT 'draft',
    owner_id            BIGINT,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_workflow_definition_type CHECK (workflow_type IN ('fixed_template', 'dag', 'hybrid')),
    CONSTRAINT ck_agent_workflow_definition_status CHECK (status IN ('draft', 'active', 'archived'))
);

CREATE TABLE IF NOT EXISTS agent_workflow_version (
    id                  BIGINT PRIMARY KEY,
    workflow_id         BIGINT NOT NULL,
    version_no          INTEGER NOT NULL,
    graph_json          JSONB NOT NULL,
    runtime_policy_json JSONB,
    content_hash        CHAR(64) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'draft',
    published_at        TIMESTAMP,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_workflow_version UNIQUE (workflow_id, version_no),
    CONSTRAINT ck_agent_workflow_version_status CHECK (status IN ('draft', 'published', 'archived'))
);

COMMIT;
