-- Keep conversation messages, execution events and traces durable while allowing
-- V1 history APIs to hide a trace or an entire conversation from the user's list.

BEGIN;

CREATE TABLE IF NOT EXISTS agent_conversation_history_tombstone (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    trace_id        VARCHAR(64),
    deleted_by      BIGINT NOT NULL,
    deleted_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason          VARCHAR(128) NOT NULL DEFAULT 'user_request',
    CONSTRAINT ck_agent_history_tombstone_trace
        CHECK (trace_id IS NULL OR trace_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_history_tombstone_scope
    ON agent_conversation_history_tombstone (
        user_id, conversation_id, COALESCE(trace_id, '')
    );
CREATE INDEX IF NOT EXISTS idx_agent_history_tombstone_trace
    ON agent_conversation_history_tombstone (user_id, trace_id)
    WHERE trace_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_history_tombstone_conversation
    ON agent_conversation_history_tombstone (user_id, conversation_id);

COMMENT ON TABLE agent_conversation_history_tombstone IS
    'V1 历史隐藏事实；不物理删除私有消息、执行事件和 Trace';

COMMIT;
