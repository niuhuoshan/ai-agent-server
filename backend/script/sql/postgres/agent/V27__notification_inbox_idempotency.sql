-- agent platform schema V27: durable, idempotent human notification inbox

BEGIN;

ALTER TABLE agent_notification
    ADD COLUMN IF NOT EXISTS event_key VARCHAR(160);

ALTER TABLE agent_notification
    DROP CONSTRAINT IF EXISTS ck_agent_notification_category;

ALTER TABLE agent_notification
    ADD CONSTRAINT ck_agent_notification_category
        CHECK (category IN ('task', 'approval', 'run', 'artifact', 'acceptance', 'system'));

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_notification_user_event
    ON agent_notification (user_id, event_key)
    WHERE event_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_notification_user_category
    ON agent_notification (user_id, category, created_at DESC, id DESC);

COMMIT;
