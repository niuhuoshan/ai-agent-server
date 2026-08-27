-- Immutable task snapshots and fail-closed TaskRun/RunStep state machines.

BEGIN;

CREATE OR REPLACE FUNCTION agent_guard_task_version_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'submitted task versions are immutable';
END;
$$;

DROP TRIGGER IF EXISTS trg_agent_task_version_immutable ON agent_task_version;
CREATE TRIGGER trg_agent_task_version_immutable
BEFORE UPDATE OR DELETE ON agent_task_version
FOR EACH ROW EXECUTE FUNCTION agent_guard_task_version_mutation();

CREATE OR REPLACE FUNCTION agent_guard_task_run_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    transition_key TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'task runs are durable facts and cannot be deleted';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.task_id IS DISTINCT FROM OLD.task_id
       OR NEW.task_version_id IS DISTINCT FROM OLD.task_version_id
       OR NEW.workflow_version_id IS DISTINCT FROM OLD.workflow_version_id
       OR NEW.trace_id IS DISTINCT FROM OLD.trace_id
       OR NEW.attempt_no IS DISTINCT FROM OLD.attempt_no
       OR NEW.parent_run_id IS DISTINCT FROM OLD.parent_run_id
       OR NEW.authorization_snapshot_json IS DISTINCT FROM OLD.authorization_snapshot_json
       OR NEW.runtime_snapshot_json IS DISTINCT FROM OLD.runtime_snapshot_json
       OR NEW.budget_snapshot_json IS DISTINCT FROM OLD.budget_snapshot_json
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'task run identity and frozen snapshots are immutable';
    END IF;

    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;

    transition_key := OLD.status || '->' || NEW.status;
    IF transition_key NOT IN (
        'queued->preparing', 'queued->running', 'queued->cancelled', 'queued->expired',
        'preparing->running', 'preparing->failed', 'preparing->cancelled',
        'running->waiting_approval', 'running->waiting_input', 'running->blocked',
        'running->verifying', 'running->succeeded', 'running->paused',
        'running->failed', 'running->cancelled', 'running->expired',
        'waiting_approval->running', 'waiting_approval->failed',
        'waiting_approval->cancelled', 'waiting_approval->expired',
        'waiting_input->running', 'waiting_input->failed',
        'waiting_input->cancelled', 'waiting_input->expired',
        'blocked->queued', 'blocked->running', 'blocked->failed',
        'blocked->cancelled', 'blocked->expired',
        'paused->queued', 'paused->running', 'paused->cancelled', 'paused->expired',
        'verifying->succeeded', 'verifying->failed', 'verifying->cancelled'
    ) THEN
        RAISE EXCEPTION 'invalid task run status transition: %', transition_key;
    END IF;

    IF NEW.status IN ('succeeded', 'failed', 'cancelled', 'expired')
       AND NEW.finished_at IS NULL THEN
        RAISE EXCEPTION 'terminal task run status requires finished_at';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_agent_task_run_integrity ON agent_task_run;
CREATE TRIGGER trg_agent_task_run_integrity
BEFORE UPDATE OR DELETE ON agent_task_run
FOR EACH ROW EXECUTE FUNCTION agent_guard_task_run_mutation();

CREATE OR REPLACE FUNCTION agent_guard_run_step_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    transition_key TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'run steps are durable facts and cannot be deleted';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.step_key IS DISTINCT FROM OLD.step_key
       OR NEW.parent_step_id IS DISTINCT FROM OLD.parent_step_id
       OR NEW.step_type IS DISTINCT FROM OLD.step_type
       OR NEW.sequence_no IS DISTINCT FROM OLD.sequence_no
       OR NEW.agent_version_id IS DISTINCT FROM OLD.agent_version_id
       OR NEW.tool_id IS DISTINCT FROM OLD.tool_id
       OR NEW.input_json IS DISTINCT FROM OLD.input_json
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'run step identity and frozen input are immutable';
    END IF;

    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    transition_key := OLD.status || '->' || NEW.status;
    IF transition_key NOT IN (
        'pending->running', 'pending->skipped', 'pending->cancelled',
        'running->waiting', 'running->succeeded', 'running->failed', 'running->cancelled',
        'waiting->running', 'waiting->succeeded', 'waiting->failed', 'waiting->cancelled'
    ) THEN
        RAISE EXCEPTION 'invalid run step status transition: %', transition_key;
    END IF;
    IF NEW.status IN ('succeeded', 'failed', 'cancelled') AND NEW.finished_at IS NULL THEN
        RAISE EXCEPTION 'terminal run step status requires finished_at';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_agent_run_step_integrity ON agent_run_step;
CREATE TRIGGER trg_agent_run_step_integrity
BEFORE UPDATE OR DELETE ON agent_run_step
FOR EACH ROW EXECUTE FUNCTION agent_guard_run_step_mutation();

COMMIT;
