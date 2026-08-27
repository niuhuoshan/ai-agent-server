-- Deterministic Nhs source fixture for full migration and adversarial rehearsal.
-- This schema intentionally includes credential material that must never reach nhs.

CREATE TABLE ai_agent_users (
    id BIGINT PRIMARY KEY,
    user_name VARCHAR(50) NOT NULL,
    real_name VARCHAR(50),
    role VARCHAR(20),
    api_key_encrypted TEXT,
    api_key_hash VARCHAR(64),
    password_hash VARCHAR(128),
    remark VARCHAR(255),
    status INTEGER,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE ai_models (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    model_id VARCHAR(255) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    type VARCHAR(50) NOT NULL,
    api_base_url VARCHAR(512),
    api_key TEXT,
    context_size INTEGER,
    max_output_tokens INTEGER,
    thinking_enable BOOLEAN,
    thinking_only BOOLEAN,
    allow_disable_thinking BOOLEAN,
    reasoning_effort VARCHAR(32),
    is_active BOOLEAN,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE ai_agents (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    description TEXT,
    avatar_url VARCHAR(255),
    capabilities JSONB,
    agent_type VARCHAR(32),
    is_system BOOLEAN,
    sort_order INTEGER,
    is_enabled BOOLEAN,
    engine_type VARCHAR(20),
    engine_config JSONB,
    created_by VARCHAR(64),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE ai_agent_versions (
    id VARCHAR(36) PRIMARY KEY,
    agent_id VARCHAR(36) NOT NULL,
    version_number INTEGER NOT NULL,
    model_name VARCHAR(100),
    temperature NUMERIC(5,2),
    synthesis_model_name VARCHAR(100),
    synthesis_temperature NUMERIC(5,2),
    system_prompt TEXT NOT NULL,
    tools JSONB,
    skills_custom BOOLEAN,
    skills JSONB,
    welcome_config JSONB,
    status VARCHAR(20),
    comment VARCHAR(255),
    created_at TIMESTAMP
);

CREATE TABLE sys_api_tools (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    method VARCHAR(20),
    url_template TEXT,
    headers TEXT,
    parameter_schema TEXT,
    is_active BOOLEAN,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE sys_mcp_servers (
    id VARCHAR(36) PRIMARY KEY,
    server_name VARCHAR(100) NOT NULL,
    remark VARCHAR(500),
    sse_url TEXT NOT NULL,
    auth_headers TEXT,
    enabled_status INTEGER,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE sys_mcp_tool_cache (
    id VARCHAR(36) PRIMARY KEY,
    server_id VARCHAR(36) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    tool_description TEXT,
    parameter_schema TEXT,
    is_published BOOLEAN,
    is_available BOOLEAN,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE skill_publications (
    id VARCHAR(36) PRIMARY KEY,
    platform_skill_id VARCHAR(128),
    source_user_id BIGINT NOT NULL,
    source_personal_skill_id VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    current_version INTEGER,
    status VARCHAR(32),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE skill_publication_versions (
    id VARCHAR(36) PRIMARY KEY,
    publication_id VARCHAR(36) NOT NULL,
    version_number INTEGER NOT NULL,
    status VARCHAR(32),
    snapshot_path VARCHAR(1024),
    content_sha256 VARCHAR(64),
    file_count INTEGER,
    total_size BIGINT,
    submitted_by BIGINT,
    submitted_at TIMESTAMP,
    published_at TIMESTAMP
);

CREATE TABLE knowledge_base_metadata (
    id BIGINT PRIMARY KEY,
    ragflow_dataset_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    owner VARCHAR(100),
    visibility VARCHAR(32),
    tags JSONB,
    notes TEXT,
    extra_config JSONB,
    status VARCHAR(32),
    created_by VARCHAR(64),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE meta_db_connection_configs (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    db_type VARCHAR(20) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    db_user VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    database_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_by BIGINT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE meta_datasets (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(100),
    description TEXT,
    tags JSONB,
    data_source VARCHAR(50),
    status INTEGER,
    enable_data_perm BOOLEAN,
    row_filter_config JSONB,
    rag_dataset_id VARCHAR(64),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE meta_tables (
    id BIGINT PRIMARY KEY,
    dataset_id BIGINT NOT NULL,
    physical_name VARCHAR(255) NOT NULL,
    term VARCHAR(255) NOT NULL,
    description TEXT,
    synonyms JSONB,
    status INTEGER,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE meta_columns (
    id BIGINT PRIMARY KEY,
    table_id BIGINT NOT NULL,
    physical_name VARCHAR(255) NOT NULL,
    term VARCHAR(255) NOT NULL,
    type VARCHAR(50),
    description TEXT,
    enums JSONB,
    synonyms JSONB,
    examples JSONB,
    foreign_key VARCHAR(255),
    is_primary INTEGER,
    created_at TIMESTAMP
);

CREATE TABLE meta_metrics (
    id BIGINT PRIMARY KEY,
    dataset_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    description TEXT,
    calculation_logic TEXT,
    unit VARCHAR(20),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE meta_relationships (
    id BIGINT PRIMARY KEY,
    source_table_id BIGINT NOT NULL,
    target_table_id BIGINT NOT NULL,
    join_condition VARCHAR(255) NOT NULL,
    join_type VARCHAR(20),
    description TEXT
);

CREATE TABLE ai_agent_resource_permissions (
    id BIGINT PRIMARY KEY,
    user_id BIGINT,
    role_id BIGINT,
    resource_type VARCHAR(20) NOT NULL,
    resource_id VARCHAR(100) NOT NULL,
    enabled BOOLEAN,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE portal_saved_reports (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    sql_content TEXT NOT NULL,
    dataset_id BIGINT,
    data_source VARCHAR(100),
    original_query TEXT,
    sql_template TEXT,
    params_schema JSONB,
    owner_user_id BIGINT NOT NULL,
    visibility VARCHAR(32),
    status VARCHAR(32),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE ai_agent_scheduled_tasks (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    user_id BIGINT NOT NULL,
    agent_id VARCHAR(50) NOT NULL,
    conversation_id VARCHAR(50) NOT NULL,
    cron_expr VARCHAR(50) NOT NULL,
    prompt TEXT NOT NULL,
    source VARCHAR(20),
    status SMALLINT,
    config JSONB,
    run_count INTEGER,
    last_run_id VARCHAR(50),
    last_run_at TIMESTAMP,
    next_run_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE ai_agent_execution_history (
    id BIGINT PRIMARY KEY,
    agent_id VARCHAR(36) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(50),
    user_id VARCHAR(64),
    username VARCHAR(64),
    query TEXT,
    summary TEXT,
    reasoning_content TEXT,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    execution_time_ms NUMERIC,
    status VARCHAR(50),
    agent_version VARCHAR(32),
    model_id VARCHAR(255),
    model_config_id VARCHAR(36),
    feedback VARCHAR(10),
    created_at TIMESTAMP
);

CREATE TABLE ai_agent_execution_traces (
    id BIGINT PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    step_number INTEGER NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    agent_name VARCHAR(50),
    tool_name VARCHAR(100),
    tool_input JSONB,
    tool_output JSONB,
    execution_time_ms NUMERIC,
    status VARCHAR(50),
    error_message TEXT,
    model VARCHAR(100),
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    meta_info JSONB,
    created_at TIMESTAMP
);

CREATE TABLE ai_agent_access_logs (
    id BIGINT PRIMARY KEY,
    trace_id VARCHAR(64),
    user_name VARCHAR(64),
    feature_name VARCHAR(100),
    endpoint VARCHAR(255) NOT NULL,
    method VARCHAR(10) NOT NULL,
    status_code INTEGER NOT NULL,
    process_time_ms NUMERIC,
    client_ip VARCHAR(50),
    request_params TEXT,
    response_body TEXT,
    error_message TEXT,
    created_at TIMESTAMP
);

INSERT INTO ai_agent_users VALUES
    (101, 'legacy_admin', '旧管理员', 'admin', 'ENC-DO-NOT-COPY', 'HASH-DO-NOT-COPY', '$2b$legacy-password', 'source admin must not become platform admin', 1, '2026-01-01 08:00:00', '2026-01-01 08:00:00'),
    (102, 'legacy_member', '旧成员', 'user', 'ENC-MEMBER-KEY', 'MEMBER-KEY-HASH', '$2b$member-password', 'ordinary member', 1, '2026-01-02 08:00:00', '2026-01-02 08:00:00');

INSERT INTO ai_models VALUES
    ('model-chat', 'Nhs Chat', 'qwen-plus', 'dashscope', 'llm', 'https://dashscope.example.invalid/compatible-mode/v1', 'sk-live-do-not-copy', 32768, 4096, false, false, true, 'medium', true, '2026-01-03 08:00:00', '2026-01-03 08:00:00'),
    ('model-embed', 'Nhs Embedding', 'text-embedding-v3', 'openai', 'embedding', 'https://api.openai.example.invalid/v1', 'sk-embed-do-not-copy', 8192, 1024, false, false, true, NULL, true, '2026-01-03 08:00:00', '2026-01-03 08:00:00');

INSERT INTO sys_api_tools VALUES
    ('tool-weather', 'weather_lookup', 'Weather API lookup', 'GET', 'https://api.example.invalid/weather?q={city}', '{"Authorization":"Bearer old-secret"}', '{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}', true, '2026-01-04 08:00:00', '2026-01-04 08:00:00');

INSERT INTO sys_mcp_servers VALUES
    ('mcp-files', 'Legacy Files MCP', 'legacy MCP', 'https://mcp.example.invalid/sse', '{"X-Api-Key":"mcp-old-secret"}', 1, '2026-01-04 08:00:00', '2026-01-04 08:00:00');

INSERT INTO sys_mcp_tool_cache VALUES
    ('mcp-tool-list', 'mcp-files', 'list_files', 'List files', '{"type":"object","properties":{"path":{"type":"string"}}}', true, true, '2026-01-04 08:00:00', '2026-01-04 08:00:00');

INSERT INTO skill_publications VALUES
    ('skill-review', 'platform-review', 102, 'personal-review', 'Review Skill', 'Review a delivery', 1, 'PUBLISHED', '2026-01-05 08:00:00', '2026-01-05 08:00:00');

INSERT INTO skill_publication_versions VALUES
    ('skill-review-v1', 'skill-review', 1, 'PUBLISHED', '/srv/nhs/skills/review/v1', repeat('a', 64), 2, 2048, 102, '2026-01-05 08:00:00', '2026-01-05 09:00:00');

INSERT INTO knowledge_base_metadata VALUES
    (201, 'rag-dataset-policy', 'Policy Knowledge', 'Internal policy documents', 'legacy_member', 'team', '["policy","internal"]', 'migrate metadata', '{"api_key":"nested-knowledge-secret","parser":"general"}', 'active', 'legacy_member', '2026-01-06 08:00:00', '2026-01-06 08:00:00');

INSERT INTO meta_db_connection_configs VALUES
    (301, 'Analytics PostgreSQL', 'postgresql', 'analytics.example.invalid', 5432, 'legacy_reader', 'legacy-db-password', 'analytics', 'read-only analytics', 101, '2026-01-07 08:00:00', '2026-01-07 08:00:00');

INSERT INTO meta_datasets VALUES
    (401, 'sales', 'Sales Dataset', 'Sales facts', '["sales"]', 'postgresql', 1, true, '{"region":"${user.region}"}', 'rag-sales', '2026-01-07 08:00:00', '2026-01-07 08:00:00');

INSERT INTO meta_tables VALUES
    (501, 401, 'public.orders', '订单', 'Order facts', '["orders"]', 1, '2026-01-07 08:00:00', '2026-01-07 08:00:00'),
    (502, 401, 'public.customers', '客户', 'Customer dimension', '["customers"]', 1, '2026-01-07 08:00:00', '2026-01-07 08:00:00');

INSERT INTO meta_columns VALUES
    (601, 501, 'id', '订单ID', 'bigint', 'primary key', NULL, NULL, '[1,2]', NULL, 1, '2026-01-07 08:00:00'),
    (602, 501, 'customer_id', '客户ID', 'bigint', 'customer reference', NULL, NULL, '[1001]', 'public.customers.id', 0, '2026-01-07 08:00:00'),
    (603, 501, 'amount', '金额', 'numeric', 'order amount', NULL, '["sales"]', '[88.8]', NULL, 0, '2026-01-07 08:00:00'),
    (604, 502, 'id', '客户ID', 'bigint', 'primary key', NULL, NULL, '[1001]', NULL, 1, '2026-01-07 08:00:00');

INSERT INTO meta_metrics VALUES
    (701, 401, 'revenue', '收入', 'Total revenue', 'SUM(public.orders.amount)', 'CNY', '2026-01-07 08:00:00', '2026-01-07 08:00:00');

INSERT INTO meta_relationships VALUES
    (801, 501, 502, 'public.orders.customer_id = public.customers.id', 'LEFT', 'order customer');

INSERT INTO ai_agents VALUES
    ('agent-dev', 'developer', 'Development Agent', 'Implements development tasks', NULL, '["coding","review"]', 'GENERAL', false, 1, true, 'LOCAL', '{"legacy":"value"}', 'legacy_member', '2026-01-08 08:00:00', '2026-01-08 08:00:00');

INSERT INTO ai_agent_versions VALUES
    ('agent-dev-v1', 'agent-dev', 1, 'qwen-plus', 0.2, NULL, NULL, 'You are a delivery agent.', '["weather_lookup","list_files"]', true, '["skill-review"]', '{"cards":[]}', 'PUBLISHED', 'production version', '2026-01-08 09:00:00');

INSERT INTO ai_agent_resource_permissions VALUES
    (901, 102, NULL, 'agent', 'agent-dev', true, '2026-01-09 08:00:00', '2026-01-09 08:00:00'),
    (902, 102, NULL, 'dataset', '401', false, '2026-01-09 08:00:00', '2026-01-09 08:00:00');

INSERT INTO portal_saved_reports VALUES
    ('report-sales', 'Sales summary', 'Daily sales', 'SELECT id, amount FROM public.orders', 401, 'postgresql', 'show sales', 'SELECT id, amount FROM public.orders', '{}', 102, 'private', 'active', '2026-01-10 08:00:00', '2026-01-10 08:00:00');

INSERT INTO ai_agent_scheduled_tasks VALUES
    (1001, 'Daily delivery check', 102, 'agent-dev', 'legacy-schedule-conversation', '0 0 9 * * ?', 'Review yesterday delivery.', 'web', 1, '{"retry":2}', 5, 'legacy-run-5', '2026-01-10 09:00:00', '2026-01-11 09:00:00', '2026-01-09 08:00:00', '2026-01-10 09:00:00');

INSERT INTO ai_agent_execution_history VALUES
    (1101, 'agent-dev', 'trace-001', 'conversation-001', '102', 'legacy_member', 'Implement module A', 'Module A implemented', 'internal reasoning must remain archive-only', 100, 50, 150, 1200, 'success', '1', 'qwen-plus', 'model-chat', 'up', '2026-01-11 08:00:00'),
    (1102, 'agent-dev', 'trace-002', 'conversation-001', '102', 'legacy_member', 'Add tests', 'Tests added', NULL, 80, 40, 120, 900, 'success', '1', 'qwen-plus', 'model-chat', NULL, '2026-01-11 08:10:00');

INSERT INTO ai_agent_execution_traces VALUES
    (1201, 'trace-001', 1, 'tool_call', 'developer', 'weather_lookup', '{"city":"Shanghai","api_key":"nested-tool-secret"}', '{"temperature":30,"access_token":"nested-result-token"}', 300, 'success', NULL, 'qwen-plus', 10, 5, 15, '{"password":"nested-meta-secret","safe":"kept"}', '2026-01-11 08:00:30');

INSERT INTO ai_agent_access_logs VALUES
    (1301, 'trace-001', 'legacy_member', 'chat', '/api/v1/chat', 'POST', 200, 1250, '127.0.0.1', '{"prompt":"secret business prompt","token":"request-token"}', '{"answer":"private response"}', NULL, '2026-01-11 08:00:00');
