-- AgentScope durable session state used for pause, restart and approval recovery.

BEGIN;

CREATE SCHEMA IF NOT EXISTS agentscope;

CREATE TABLE IF NOT EXISTS agentscope.agentscope_sessions (
    session_id VARCHAR(255) NOT NULL,
    state_key  VARCHAR(255) NOT NULL,
    item_index INTEGER NOT NULL DEFAULT 0,
    state_data TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
);

COMMENT ON TABLE agentscope.agentscope_sessions IS 'AgentScope智能体会话状态表';
COMMENT ON COLUMN agentscope.agentscope_sessions.session_id IS 'AgentScope会话标识';
COMMENT ON COLUMN agentscope.agentscope_sessions.state_key IS '状态项标识';
COMMENT ON COLUMN agentscope.agentscope_sessions.item_index IS '列表状态项序号';
COMMENT ON COLUMN agentscope.agentscope_sessions.state_data IS '状态JSON内容';
COMMENT ON COLUMN agentscope.agentscope_sessions.created_at IS '创建时间';
COMMENT ON COLUMN agentscope.agentscope_sessions.updated_at IS '更新时间';

COMMIT;
