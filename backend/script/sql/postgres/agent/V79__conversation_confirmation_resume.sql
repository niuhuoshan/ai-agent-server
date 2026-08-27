-- Allow private conversation turns to pause on a business confirmation and resume
-- the original AgentScope execution without creating a synthetic user turn.

BEGIN;

ALTER TABLE agent_conversation_turn
    DROP CONSTRAINT IF EXISTS ck_agent_conversation_turn_status;
ALTER TABLE agent_conversation_turn
    ADD CONSTRAINT ck_agent_conversation_turn_status CHECK (
        status IN ('running', 'stopping', 'waiting_confirmation', 'succeeded', 'failed', 'cancelled')
    );
ALTER TABLE agent_conversation_turn
    ADD COLUMN IF NOT EXISTS response_draft TEXT;

DROP INDEX IF EXISTS uk_agent_conversation_turn_active;
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_conversation_turn_active
    ON agent_conversation_turn (conversation_id)
    WHERE status IN ('running', 'stopping', 'waiting_confirmation');

ALTER TABLE agent_runtime_confirmation
    ADD COLUMN IF NOT EXISTS conversation_turn_id BIGINT,
    ADD COLUMN IF NOT EXISTS pending_actions_json JSONB;

CREATE INDEX IF NOT EXISTS idx_agent_runtime_confirmation_turn
    ON agent_runtime_confirmation (conversation_turn_id, owner_id, status)
    WHERE conversation_turn_id IS NOT NULL;

COMMENT ON COLUMN agent_conversation_turn.status IS '会话回合状态：running/stopping/waiting_confirmation/succeeded/failed/cancelled';
COMMENT ON COLUMN agent_conversation_turn.response_draft IS '会话回合等待确认前已生成的受限文本草稿';
COMMENT ON COLUMN agent_runtime_confirmation.conversation_turn_id IS '私有会话中被暂停的原始回合ID';
COMMENT ON COLUMN agent_runtime_confirmation.pending_actions_json IS '私有会话确认恢复使用的不可变工具动作快照';

COMMIT;
