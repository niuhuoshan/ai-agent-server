-- Agent execution history lookup for owner and platform-administrator views.

BEGIN;

CREATE INDEX IF NOT EXISTS idx_agent_conversation_turn_agent_history
    ON agent_conversation_turn (agent_id, started_at DESC, id DESC);

COMMIT;
