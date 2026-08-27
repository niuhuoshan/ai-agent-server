-- agent platform schema V88: pause private conversation turns for Agent questions

BEGIN;

ALTER TABLE agent_conversation_turn
    DROP CONSTRAINT IF EXISTS ck_agent_conversation_turn_status;
ALTER TABLE agent_conversation_turn
    ADD CONSTRAINT ck_agent_conversation_turn_status CHECK (
        status IN (
            'running', 'stopping', 'waiting_confirmation', 'waiting_user_question',
            'succeeded', 'failed', 'cancelled'
        )
    );

DROP INDEX IF EXISTS uk_agent_conversation_turn_active;
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_conversation_turn_active
    ON agent_conversation_turn (conversation_id)
    WHERE status IN ('running', 'stopping', 'waiting_confirmation', 'waiting_user_question');

COMMENT ON COLUMN agent_conversation_turn.status IS '会话回合状态：running/stopping/waiting_confirmation/waiting_user_question/succeeded/failed/cancelled';

COMMIT;
