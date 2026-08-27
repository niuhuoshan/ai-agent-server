-- Typed task ownership and acceptance reviewers for machine-created work.

BEGIN;

ALTER TABLE agent_task
    ADD COLUMN IF NOT EXISTS owner_principal_type VARCHAR(24) NOT NULL DEFAULT 'human';

ALTER TABLE agent_acceptance_record
    ADD COLUMN IF NOT EXISTS reviewer_principal_type VARCHAR(24) NOT NULL DEFAULT 'human';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_agent_task_owner_principal_type'
    ) THEN
        ALTER TABLE agent_task
            ADD CONSTRAINT ck_agent_task_owner_principal_type
            CHECK (owner_principal_type IN ('human', 'service_account'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_agent_acceptance_reviewer_principal_type'
    ) THEN
        ALTER TABLE agent_acceptance_record
            ADD CONSTRAINT ck_agent_acceptance_reviewer_principal_type
            CHECK (reviewer_principal_type IN ('human', 'service_account'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_agent_task_typed_owner_status
    ON agent_task (owner_principal_type, owner_id, status)
    WHERE del_flag = '0';

CREATE INDEX IF NOT EXISTS idx_agent_acceptance_typed_reviewer
    ON agent_acceptance_record (reviewer_principal_type, reviewer_id, created_at DESC);

COMMENT ON COLUMN agent_task.owner_principal_type IS '任务负责人主体类型：human/service_account';
COMMENT ON COLUMN agent_acceptance_record.reviewer_principal_type IS '验收主体类型：human/service_account';

CREATE OR REPLACE FUNCTION agent_guard_acceptance_record_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.result <> 'pending'
           AND (NEW.reviewer_id IS NULL
                OR NEW.reviewer_principal_type NOT IN ('human', 'service_account')
                OR NEW.idempotency_key_hash IS NULL
                OR NEW.idempotency_key_hash !~ '^[0-9a-f]{64}$'
                OR NEW.request_hash IS NULL
                OR NEW.request_hash !~ '^[0-9a-f]{64}$') THEN
            RAISE EXCEPTION 'terminal acceptance decisions require typed reviewer and integrity hashes';
        END IF;
        IF NEW.result <> 'pending'
           AND (NEW.artifact_ids_json IS NULL
                OR jsonb_typeof(NEW.artifact_ids_json) <> 'array'
                OR jsonb_array_length(NEW.artifact_ids_json) = 0) THEN
            RAISE EXCEPTION 'terminal acceptance decisions require artifact versions';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'acceptance records are append-only facts and cannot be changed';
END;
$$;

COMMIT;
