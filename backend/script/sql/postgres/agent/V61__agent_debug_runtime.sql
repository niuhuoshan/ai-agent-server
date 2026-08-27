-- agent platform schema V61: durable private Agent debug runs

BEGIN;

CREATE TABLE IF NOT EXISTS agent_debug_run (
    id                    BIGINT PRIMARY KEY,
    owner_id              BIGINT NOT NULL,
    idempotency_key       VARCHAR(128) NOT NULL,
    agent_id              BIGINT NOT NULL,
    agent_version_id      BIGINT NOT NULL,
    task_id               BIGINT NOT NULL,
    run_id                BIGINT NOT NULL,
    parent_debug_run_id   BIGINT,
    input_text            TEXT NOT NULL,
    input_sha256          CHAR(64) NOT NULL,
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_debug_run_owner_key UNIQUE (owner_id, idempotency_key),
    CONSTRAINT uk_agent_debug_run_runtime UNIQUE (run_id),
    CONSTRAINT ck_agent_debug_run_input CHECK (char_length(input_text) BETWEEN 1 AND 100000)
);

CREATE INDEX IF NOT EXISTS idx_agent_debug_run_owner_time
    ON agent_debug_run (owner_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_agent_debug_run_task
    ON agent_debug_run (task_id, run_id);

CREATE INDEX IF NOT EXISTS idx_agent_debug_run_parent
    ON agent_debug_run (parent_debug_run_id, created_at, id);

COMMENT ON TABLE agent_debug_run IS '用户私有的Agent调试运行与正式任务运行映射表';
COMMENT ON COLUMN agent_debug_run.id IS '调试运行主键ID';
COMMENT ON COLUMN agent_debug_run.owner_id IS '调试运行所属用户ID';
COMMENT ON COLUMN agent_debug_run.idempotency_key IS '用户范围内唯一的调试请求幂等键';
COMMENT ON COLUMN agent_debug_run.agent_id IS '被调试的Agent定义ID';
COMMENT ON COLUMN agent_debug_run.agent_version_id IS '被调试的已发布Agent版本ID';
COMMENT ON COLUMN agent_debug_run.task_id IS '承载权限快照和运行状态的正式任务ID';
COMMENT ON COLUMN agent_debug_run.run_id IS '承载事件、步骤和检查点的正式运行ID';
COMMENT ON COLUMN agent_debug_run.parent_debug_run_id IS '重试来源调试运行ID';
COMMENT ON COLUMN agent_debug_run.input_text IS '仅调试运行所属用户可见的真实输入正文';
COMMENT ON COLUMN agent_debug_run.input_sha256 IS '调试输入正文SHA256摘要';
COMMENT ON COLUMN agent_debug_run.created_at IS '调试运行创建时间';

COMMIT;
