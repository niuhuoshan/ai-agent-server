-- Immutable artifact versions and append-only, idempotent acceptance decisions.

BEGIN;

ALTER TABLE agent_acceptance_record
    ADD COLUMN IF NOT EXISTS idempotency_key_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS request_hash CHAR(64);

COMMENT ON COLUMN agent_acceptance_record.idempotency_key_hash IS '验收提交幂等键哈希';
COMMENT ON COLUMN agent_acceptance_record.request_hash IS '验收决策规范化请求哈希';

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_artifact_version
    ON agent_artifact (task_id, run_id, artifact_type, name, version_no)
    WHERE task_id IS NOT NULL AND run_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_acceptance_idempotency
    ON agent_acceptance_record (idempotency_key_hash)
    WHERE idempotency_key_hash IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_acceptance_terminal_run
    ON agent_acceptance_record (task_id, run_id)
    WHERE result <> 'pending';

CREATE OR REPLACE FUNCTION agent_guard_artifact_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    transition_key TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'artifact versions are durable facts and cannot be deleted';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.project_id IS DISTINCT FROM OLD.project_id
       OR NEW.task_id IS DISTINCT FROM OLD.task_id
       OR NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.step_id IS DISTINCT FROM OLD.step_id
       OR NEW.artifact_type IS DISTINCT FROM OLD.artifact_type
       OR NEW.name IS DISTINCT FROM OLD.name
       OR NEW.version_no IS DISTINCT FROM OLD.version_no
       OR NEW.storage_type IS DISTINCT FROM OLD.storage_type
       OR NEW.storage_ref IS DISTINCT FROM OLD.storage_ref
       OR NEW.mime_type IS DISTINCT FROM OLD.mime_type
       OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
       OR NEW.content_hash IS DISTINCT FROM OLD.content_hash
       OR NEW.sensitive_level IS DISTINCT FROM OLD.sensitive_level
       OR NEW.visibility IS DISTINCT FROM OLD.visibility
       OR NEW.metadata_json IS DISTINCT FROM OLD.metadata_json
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'artifact version content is immutable';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    transition_key := OLD.status || '->' || NEW.status;
    IF transition_key NOT IN (
        'created->available', 'created->quarantined', 'created->deleted',
        'available->quarantined', 'available->deleted',
        'quarantined->available', 'quarantined->deleted'
    ) THEN
        RAISE EXCEPTION 'invalid artifact status transition: %', transition_key;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_agent_artifact_integrity ON agent_artifact;
CREATE TRIGGER trg_agent_artifact_integrity
BEFORE UPDATE OR DELETE ON agent_artifact
FOR EACH ROW EXECUTE FUNCTION agent_guard_artifact_mutation();

CREATE OR REPLACE FUNCTION agent_guard_acceptance_record_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.result <> 'pending'
           AND (NEW.reviewer_id IS NULL
                OR NEW.idempotency_key_hash IS NULL
                OR NEW.idempotency_key_hash !~ '^[0-9a-f]{64}$'
                OR NEW.request_hash IS NULL
                OR NEW.request_hash !~ '^[0-9a-f]{64}$') THEN
            RAISE EXCEPTION 'terminal acceptance decisions require reviewer and integrity hashes';
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

DROP TRIGGER IF EXISTS trg_agent_acceptance_record_integrity ON agent_acceptance_record;
CREATE TRIGGER trg_agent_acceptance_record_integrity
BEFORE INSERT OR UPDATE OR DELETE ON agent_acceptance_record
FOR EACH ROW EXECUTE FUNCTION agent_guard_acceptance_record_mutation();

COMMIT;
