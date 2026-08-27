-- agent platform schema V42: durable owner-scoped active conversation state

BEGIN;

CREATE TABLE IF NOT EXISTS agent_chat_user_state (
    user_id                 BIGINT PRIMARY KEY,
    active_conversation_id  BIGINT NOT NULL,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_chat_user_state_conversation CHECK (active_conversation_id > 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_chat_user_state_conversation
    ON agent_chat_user_state (active_conversation_id);

COMMENT ON TABLE agent_chat_user_state IS '用户聊天界面持久状态，当前用于跨端恢复本人最近激活的私有会话';
COMMENT ON COLUMN agent_chat_user_state.user_id IS '人员用户ID，同时作为每个用户唯一状态记录的主键';
COMMENT ON COLUMN agent_chat_user_state.active_conversation_id IS '当前激活的私有会话ID，读取和写入时均复核会话所有权及删除状态';
COMMENT ON COLUMN agent_chat_user_state.created_at IS '用户聊天状态首次创建时间';
COMMENT ON COLUMN agent_chat_user_state.updated_at IS '用户聊天状态最后修改时间';

COMMIT;
