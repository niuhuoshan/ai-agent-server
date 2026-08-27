-- A committed approval decision is as immutable as its pending action snapshot.

BEGIN;

CREATE OR REPLACE FUNCTION agent_guard_approval_request_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    transition_key TEXT;
    decision_fields_changed BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'approval requests are durable facts and cannot be deleted';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.task_id IS DISTINCT FROM OLD.task_id
       OR NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.step_id IS DISTINCT FROM OLD.step_id
       OR NEW.tool_id IS DISTINCT FROM OLD.tool_id
       OR NEW.risk_level IS DISTINCT FROM OLD.risk_level
       OR NEW.action_summary IS DISTINCT FROM OLD.action_summary
       OR NEW.input_summary IS DISTINCT FROM OLD.input_summary
       OR NEW.impact_scope IS DISTINCT FROM OLD.impact_scope
       OR NEW.credential_ref IS DISTINCT FROM OLD.credential_ref
       OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
       OR NEW.request_event_id IS DISTINCT FROM OLD.request_event_id
       OR NEW.reply_id IS DISTINCT FROM OLD.reply_id
       OR NEW.pending_actions_json IS DISTINCT FROM OLD.pending_actions_json
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'approval request identity and pending action are immutable';
    END IF;

    decision_fields_changed :=
        NEW.reviewer_id IS DISTINCT FROM OLD.reviewer_id
        OR NEW.review_comment IS DISTINCT FROM OLD.review_comment
        OR NEW.decision_token_hash IS DISTINCT FROM OLD.decision_token_hash
        OR NEW.decision_metadata_json IS DISTINCT FROM OLD.decision_metadata_json
        OR NEW.decision_key_hash IS DISTINCT FROM OLD.decision_key_hash
        OR NEW.decided_at IS DISTINCT FROM OLD.decided_at;

    IF NEW.status = OLD.status THEN
        IF decision_fields_changed THEN
            RAISE EXCEPTION 'approval decision fields require one valid status transition';
        END IF;
        RETURN NEW;
    END IF;

    transition_key := OLD.status || '->' || NEW.status;
    IF transition_key NOT IN (
        'pending->approved', 'pending->rejected', 'pending->revoked', 'pending->expired',
        'approved->revoked'
    ) THEN
        RAISE EXCEPTION 'invalid approval status transition: %', transition_key;
    END IF;

    IF OLD.status <> 'pending' AND decision_fields_changed THEN
        RAISE EXCEPTION 'committed approval decision fields are immutable';
    END IF;

    IF NEW.status IN ('approved', 'rejected')
       AND (NEW.reviewer_id IS NULL
            OR NEW.decision_key_hash IS NULL
            OR NEW.decision_metadata_json IS NULL) THEN
        RAISE EXCEPTION 'approval decision requires reviewer, idempotency hash and metadata';
    END IF;

    IF NEW.status IN ('approved', 'rejected', 'revoked', 'expired')
       AND NEW.decided_at IS NULL THEN
        RAISE EXCEPTION 'terminal approval status requires decided_at';
    END IF;
    RETURN NEW;
END;
$$;

COMMIT;
