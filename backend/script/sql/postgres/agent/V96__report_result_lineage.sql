-- agent platform schema V96: persist immutable result lineage for saved report runs

BEGIN;

ALTER TABLE agent_report_run
    ADD COLUMN IF NOT EXISTS result_hash CHAR(64);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_agent_report_run_result_hash'
    ) THEN
        ALTER TABLE agent_report_run
            ADD CONSTRAINT ck_agent_report_run_result_hash
            CHECK (result_hash IS NULL OR result_hash ~ '^[0-9a-f]{64}$');
    END IF;
END $$;

COMMENT ON COLUMN agent_report_run.result_hash IS 'Immutable bounded query result SHA-256';

COMMIT;
