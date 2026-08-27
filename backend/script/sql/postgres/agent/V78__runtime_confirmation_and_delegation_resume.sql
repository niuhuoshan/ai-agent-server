-- Durable resume facts for business confirmations and child-Agent delegation.

BEGIN;

ALTER TABLE agent_runtime_confirmation
    ALTER COLUMN confirmation_key TYPE VARCHAR(128),
    ADD COLUMN IF NOT EXISTS task_id BIGINT,
    ADD COLUMN IF NOT EXISTS run_id BIGINT,
    ADD COLUMN IF NOT EXISTS step_id BIGINT,
    ADD COLUMN IF NOT EXISTS approval_id BIGINT,
    ADD COLUMN IF NOT EXISTS request_event_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS reply_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS tool_call_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS tool_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS reviewer_id BIGINT,
    ADD COLUMN IF NOT EXISTS decision_metadata_json JSONB,
    ADD COLUMN IF NOT EXISTS decision_key_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS decided_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS consumed_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_runtime_confirmation_approval
    ON agent_runtime_confirmation (approval_id)
    WHERE approval_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_runtime_confirmation_execution
    ON agent_runtime_confirmation (owner_id, execution_id, status, consumed_at);

ALTER TABLE agent_runtime_delegation
    ADD COLUMN IF NOT EXISTS parent_task_id BIGINT,
    ADD COLUMN IF NOT EXISTS parent_run_id BIGINT,
    ADD COLUMN IF NOT EXISTS parent_step_id BIGINT,
    ADD COLUMN IF NOT EXISTS child_task_id BIGINT,
    ADD COLUMN IF NOT EXISTS child_run_id BIGINT,
    ADD COLUMN IF NOT EXISTS child_step_id BIGINT,
    ADD COLUMN IF NOT EXISTS child_trace_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS target_agent_id BIGINT,
    ADD COLUMN IF NOT EXISTS target_agent_version_id BIGINT,
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS finished_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS timeout_at TIMESTAMP;

ALTER TABLE agent_runtime_delegation
    DROP CONSTRAINT IF EXISTS ck_agent_runtime_delegation_status;
ALTER TABLE agent_runtime_delegation
    ADD CONSTRAINT ck_agent_runtime_delegation_status CHECK (
        status IN ('queued', 'running', 'succeeded', 'completed', 'approval_required',
                   'timed_out', 'failed', 'cancelled')
    );

CREATE INDEX IF NOT EXISTS idx_agent_runtime_delegation_parent
    ON agent_runtime_delegation (owner_id, parent_run_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_runtime_delegation_child
    ON agent_runtime_delegation (child_run_id)
    WHERE child_run_id IS NOT NULL;

COMMENT ON COLUMN agent_runtime_confirmation.approval_id IS '关联的 AgentScope 审批请求ID';
COMMENT ON COLUMN agent_runtime_confirmation.decision_key_hash IS '确认决策幂等键哈希';
COMMENT ON COLUMN agent_runtime_confirmation.consumed_at IS '确认结果被原 AgentScope 恢复消费的时间';
COMMENT ON COLUMN agent_runtime_delegation.child_run_id IS '持久化子 Agent 运行ID';
COMMENT ON COLUMN agent_runtime_delegation.status IS '委派状态：queued/running/succeeded/approval_required/timed_out/failed/cancelled';

COMMIT;
