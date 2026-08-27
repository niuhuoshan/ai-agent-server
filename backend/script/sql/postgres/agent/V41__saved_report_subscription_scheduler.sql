-- agent platform schema V41: durable scheduling and delivery for saved report subscriptions

BEGIN;

ALTER TABLE agent_report_subscription
    DROP COLUMN IF EXISTS trigger_id,
    ADD COLUMN IF NOT EXISTS schedule_type VARCHAR(16),
    ADD COLUMN IF NOT EXISTS cron_expr VARCHAR(128),
    ADD COLUMN IF NOT EXISTS interval_minutes INTEGER,
    ADD COLUMN IF NOT EXISTS max_attempts INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS revision_no BIGINT NOT NULL DEFAULT 1;

-- Legacy rows were driven by task automation triggers. They cannot be translated safely,
-- so keep them visible but paused until their report-native schedule is configured.
UPDATE agent_report_subscription
SET schedule_type = COALESCE(schedule_type, 'cron'),
    cron_expr = COALESCE(cron_expr, '0 0 9 * * *'),
    status = 'paused',
    next_run_at = NULL
WHERE schedule_type IS NULL;

ALTER TABLE agent_report_subscription
    ALTER COLUMN schedule_type SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_agent_report_subscription_schedule'
    ) THEN
        ALTER TABLE agent_report_subscription
            ADD CONSTRAINT ck_agent_report_subscription_schedule CHECK (
                (schedule_type = 'cron' AND cron_expr IS NOT NULL AND interval_minutes IS NULL)
                OR
                (schedule_type = 'interval' AND cron_expr IS NULL
                    AND interval_minutes BETWEEN 1 AND 525600)
            );
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_agent_report_subscription_status'
    ) THEN
        ALTER TABLE agent_report_subscription
            ADD CONSTRAINT ck_agent_report_subscription_status
            CHECK (status IN ('active', 'paused'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_agent_report_subscription_retry'
    ) THEN
        ALTER TABLE agent_report_subscription
            ADD CONSTRAINT ck_agent_report_subscription_retry
            CHECK (max_attempts BETWEEN 1 AND 10 AND revision_no > 0);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_agent_report_subscription_due
    ON agent_report_subscription (next_run_at, id)
    WHERE status = 'active' AND del_flag = '0' AND next_run_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS agent_report_delivery_job (
    id                  BIGINT PRIMARY KEY,
    subscription_id     BIGINT NOT NULL,
    report_id           BIGINT NOT NULL,
    recipient_id        BIGINT,
    scheduled_at        TIMESTAMP NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'queued',
    attempt_no          INTEGER NOT NULL DEFAULT 0,
    max_attempts        INTEGER NOT NULL DEFAULT 3,
    available_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token         VARCHAR(64),
    lease_until         TIMESTAMP,
    worker_id           VARCHAR(128),
    report_run_id       BIGINT,
    last_error          TEXT,
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,
    CONSTRAINT uk_agent_report_delivery_schedule UNIQUE (subscription_id, scheduled_at),
    CONSTRAINT ck_agent_report_delivery_status
        CHECK (status IN ('queued', 'running', 'retry', 'succeeded', 'dead', 'cancelled')),
    CONSTRAINT ck_agent_report_delivery_attempt
        CHECK (attempt_no >= 0 AND max_attempts BETWEEN 1 AND 10)
);

CREATE INDEX IF NOT EXISTS idx_agent_report_delivery_claim
    ON agent_report_delivery_job (available_at, id)
    WHERE status IN ('queued', 'retry', 'running');

CREATE INDEX IF NOT EXISTS idx_agent_report_delivery_subscription
    ON agent_report_delivery_job (subscription_id, created_at DESC, id DESC);

COMMENT ON TABLE agent_report_delivery_job IS 'Saved report delivery queue with leases and bounded retry';
COMMENT ON COLUMN agent_report_subscription.schedule_type IS 'Report-native schedule: cron or interval';
COMMENT ON COLUMN agent_report_subscription.next_run_at IS 'Next report delivery time persisted as UTC LocalDateTime';

COMMIT;
