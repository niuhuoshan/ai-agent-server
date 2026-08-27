-- Durable pause/resume and server-owned approval recovery state.

BEGIN;

ALTER TABLE agent_approval_request
    ADD COLUMN IF NOT EXISTS request_event_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS reply_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS pending_actions_json JSONB,
    ADD COLUMN IF NOT EXISTS decision_metadata_json JSONB,
    ADD COLUMN IF NOT EXISTS decision_key_hash CHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_approval_run_reply
    ON agent_approval_request (run_id, reply_id)
    WHERE reply_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_approval_request_event
    ON agent_approval_request (request_event_id)
    WHERE request_event_id IS NOT NULL;

COMMENT ON COLUMN agent_approval_request.request_event_id IS '触发审批的运行事件标识';
COMMENT ON COLUMN agent_approval_request.reply_id IS 'AgentScope待确认回复标识';
COMMENT ON COLUMN agent_approval_request.pending_actions_json IS '服务端冻结的待执行动作快照列表';
COMMENT ON COLUMN agent_approval_request.decision_metadata_json IS '审批决策审计元数据';
COMMENT ON COLUMN agent_approval_request.decision_key_hash IS '审批幂等键哈希';

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
        'queued->preparing', 'queued->running', 'queued->failed',
        'queued->cancelled', 'queued->expired',
        'preparing->running', 'preparing->paused', 'preparing->failed',
        'preparing->cancelled',
        'running->waiting_approval', 'running->waiting_input', 'running->blocked',
        'running->verifying', 'running->succeeded', 'running->paused',
        'running->failed', 'running->cancelled', 'running->expired',
        'waiting_approval->running', 'waiting_approval->failed',
        'waiting_approval->cancelled', 'waiting_approval->expired',
        'waiting_input->running', 'waiting_input->failed',
        'waiting_input->cancelled', 'waiting_input->expired',
        'blocked->queued', 'blocked->running', 'blocked->failed',
        'blocked->cancelled', 'blocked->expired',
        'paused->queued', 'paused->running', 'paused->failed',
        'paused->cancelled', 'paused->expired',
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

CREATE OR REPLACE FUNCTION agent_guard_approval_request_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    transition_key TEXT;
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
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    transition_key := OLD.status || '->' || NEW.status;
    IF transition_key NOT IN (
        'pending->approved', 'pending->rejected', 'pending->revoked', 'pending->expired',
        'approved->revoked'
    ) THEN
        RAISE EXCEPTION 'invalid approval status transition: %', transition_key;
    END IF;
    IF NEW.status IN ('approved', 'rejected', 'revoked', 'expired')
       AND NEW.decided_at IS NULL THEN
        RAISE EXCEPTION 'terminal approval status requires decided_at';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_agent_approval_request_integrity ON agent_approval_request;
CREATE TRIGGER trg_agent_approval_request_integrity
BEFORE UPDATE OR DELETE ON agent_approval_request
FOR EACH ROW EXECUTE FUNCTION agent_guard_approval_request_mutation();

COMMIT;
