-- Durable global cursors allow database-backed SSE replay across API restarts and instances.

BEGIN;

CREATE SEQUENCE IF NOT EXISTS agent_execution_event_cursor_seq;

SELECT setval(
    'agent_execution_event_cursor_seq',
    GREATEST(COALESCE((SELECT MAX(cursor) FROM agent_execution_event), 0) + 1, 1),
    false
);

ALTER TABLE agent_execution_event
    ALTER COLUMN cursor SET DEFAULT nextval('agent_execution_event_cursor_seq');

ALTER TABLE agent_execution_event
    ADD COLUMN IF NOT EXISTS occurred_at TIMESTAMP;

UPDATE agent_execution_event
SET occurred_at = created_at
WHERE occurred_at IS NULL;

ALTER TABLE agent_execution_event
    ALTER COLUMN occurred_at SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_execution_event_cursor
    ON agent_execution_event (cursor);

CREATE INDEX IF NOT EXISTS idx_agent_execution_event_conversation_cursor
    ON agent_execution_event (conversation_id, cursor)
    WHERE conversation_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_execution_event_run_cursor
    ON agent_execution_event (run_id, cursor)
    WHERE run_id IS NOT NULL;

COMMENT ON COLUMN agent_execution_event.occurred_at IS '运行时事件实际发生时间';

COMMIT;
