-- agent platform schema V39: auditable conversation feedback and bounded resource scopes

BEGIN;

CREATE TABLE IF NOT EXISTS agent_chat_feedback (
    id                  BIGINT PRIMARY KEY,
    conversation_id     BIGINT NOT NULL,
    message_id          BIGINT,
    turn_id             BIGINT,
    user_id             BIGINT NOT NULL,
    rating              VARCHAR(16) NOT NULL,
    reason              VARCHAR(64),
    comment             VARCHAR(2000),
    trace_id            VARCHAR(64),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_chat_feedback_message_user UNIQUE (conversation_id, message_id, user_id),
    CONSTRAINT ck_agent_chat_feedback_rating CHECK (rating IN ('up', 'down')),
    CONSTRAINT ck_agent_chat_feedback_comment CHECK (comment IS NULL OR length(comment) <= 2000)
);

CREATE INDEX IF NOT EXISTS idx_agent_chat_feedback_owner
    ON agent_chat_feedback (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_chat_feedback_conversation
    ON agent_chat_feedback (conversation_id, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_chat_resource_scope (
    conversation_id     BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    scope_json          JSONB NOT NULL DEFAULT '{}'::jsonb,
    revision             INTEGER NOT NULL DEFAULT 1,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_chat_resource_scope_revision CHECK (revision > 0),
    CONSTRAINT ck_agent_chat_resource_scope_size CHECK (pg_column_size(scope_json) <= 65536)
);

CREATE INDEX IF NOT EXISTS idx_agent_chat_resource_scope_owner
    ON agent_chat_resource_scope (user_id, updated_at DESC);

COMMENT ON TABLE agent_chat_feedback IS '会话消息的用户反馈，关联私有会话并保留审计事实';
COMMENT ON COLUMN agent_chat_feedback.id IS '反馈记录主键';
COMMENT ON COLUMN agent_chat_feedback.conversation_id IS '所属私有会话ID';
COMMENT ON COLUMN agent_chat_feedback.message_id IS '被评价的助手消息ID';
COMMENT ON COLUMN agent_chat_feedback.turn_id IS '消息所属会话回合ID';
COMMENT ON COLUMN agent_chat_feedback.user_id IS '提交反馈的用户ID，必须是会话所有者';
COMMENT ON COLUMN agent_chat_feedback.rating IS '用户评价：up=有帮助，down=无帮助';
COMMENT ON COLUMN agent_chat_feedback.reason IS '结构化反馈原因，最长64字符';
COMMENT ON COLUMN agent_chat_feedback.comment IS '用户补充说明，最长2000字符';
COMMENT ON COLUMN agent_chat_feedback.trace_id IS '关联的运行追踪ID';
COMMENT ON COLUMN agent_chat_feedback.created_at IS '反馈首次创建时间';
COMMENT ON COLUMN agent_chat_feedback.updated_at IS '反馈最后修改时间';
COMMENT ON TABLE agent_chat_resource_scope IS '会话级资源范围快照，只能收窄当前用户已获授权的资源';
COMMENT ON COLUMN agent_chat_resource_scope.conversation_id IS '私有会话ID，同时作为主键';
COMMENT ON COLUMN agent_chat_resource_scope.user_id IS '会话所有者用户ID';
COMMENT ON COLUMN agent_chat_resource_scope.scope_json IS '按资源类型保存的授权资源ID列表JSON';
COMMENT ON COLUMN agent_chat_resource_scope.revision IS '乐观锁版本号，每次更新递增';
COMMENT ON COLUMN agent_chat_resource_scope.created_at IS '资源范围首次创建时间';
COMMENT ON COLUMN agent_chat_resource_scope.updated_at IS '资源范围最后修改时间';

COMMIT;
