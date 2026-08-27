-- Durable idempotency fact for Nhs external execution result submissions.
CREATE TABLE IF NOT EXISTS agent_external_execution_resume (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    reply_id        VARCHAR(128) NOT NULL,
    task_id         BIGINT,
    run_id          BIGINT NOT NULL,
    step_id         BIGINT NOT NULL,
    trace_id        VARCHAR(128) NOT NULL,
    results_hash    CHAR(64) NOT NULL,
    results_json    JSONB NOT NULL,
    status          VARCHAR(16) NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    dispatched_at   TIMESTAMP,
    CONSTRAINT ck_external_resume_status
        CHECK (status IN ('pending', 'dispatched')),
    CONSTRAINT uk_external_resume_user_reply UNIQUE (user_id, reply_id)
);

CREATE INDEX IF NOT EXISTS idx_external_resume_run
    ON agent_external_execution_resume (run_id, step_id, status);

COMMENT ON TABLE agent_external_execution_resume IS '外部工具结果恢复幂等事实';
COMMENT ON COLUMN agent_external_execution_resume.reply_id IS 'AgentScope外部执行回复标识';
COMMENT ON COLUMN agent_external_execution_resume.results_hash IS '服务端规范化结果摘要SHA-256';
COMMENT ON COLUMN agent_external_execution_resume.results_json IS '已通过服务端校验的结果快照';
