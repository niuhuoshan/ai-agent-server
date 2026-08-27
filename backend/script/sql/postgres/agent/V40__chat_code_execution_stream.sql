-- agent platform schema V40: private chat code execution with durable streamed output

BEGIN;

ALTER TABLE agent_sandbox_job
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(16) NOT NULL DEFAULT 'task_tool',
    ADD COLUMN IF NOT EXISTS owner_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS conversation_id BIGINT,
    ADD COLUMN IF NOT EXISTS script_language VARCHAR(16),
    ADD COLUMN IF NOT EXISTS script_text TEXT,
    ADD COLUMN IF NOT EXISTS output_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS output_bytes INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS output_truncated BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agent_sandbox_job
    ALTER COLUMN task_id DROP NOT NULL,
    ALTER COLUMN run_id DROP NOT NULL,
    ALTER COLUMN tool_id DROP NOT NULL;

ALTER TABLE agent_sandbox_job
    DROP CONSTRAINT IF EXISTS ck_agent_sandbox_job_source,
    DROP CONSTRAINT IF EXISTS ck_agent_sandbox_job_script,
    DROP CONSTRAINT IF EXISTS ck_agent_sandbox_job_output_progress;

ALTER TABLE agent_sandbox_job
    ADD CONSTRAINT ck_agent_sandbox_job_source CHECK (
        (source_type = 'task_tool'
            AND task_id IS NOT NULL AND run_id IS NOT NULL AND tool_id IS NOT NULL
            AND owner_user_id IS NULL AND conversation_id IS NULL
            AND script_language IS NULL AND script_text IS NULL)
        OR
        (source_type = 'chat_code'
            AND task_id IS NULL AND run_id IS NULL AND step_id IS NULL AND tool_id IS NULL
            AND external_reply_id IS NULL AND tool_call_id IS NULL AND tool_name IS NULL
            AND owner_user_id IS NOT NULL AND conversation_id IS NOT NULL
            AND script_language IS NOT NULL AND script_text IS NOT NULL)
    ),
    ADD CONSTRAINT ck_agent_sandbox_job_script CHECK (
        (source_type = 'task_tool')
        OR (script_language IN ('python', 'sh', 'bash')
            AND octet_length(script_text) BETWEEN 1 AND 1048576)
    ),
    ADD CONSTRAINT ck_agent_sandbox_job_output_progress CHECK (
        output_sequence >= 0
        AND output_bytes BETWEEN 0 AND max_output_bytes
    );

CREATE INDEX IF NOT EXISTS idx_agent_sandbox_job_chat_owner
    ON agent_sandbox_job (owner_user_id, conversation_id, created_at DESC, id DESC)
    WHERE source_type = 'chat_code';

CREATE TABLE IF NOT EXISTS agent_sandbox_job_output (
    id                  BIGINT PRIMARY KEY,
    job_id              BIGINT NOT NULL,
    attempt_no          INTEGER NOT NULL,
    sequence_no         BIGINT NOT NULL,
    runner_sequence_no  BIGINT NOT NULL,
    stream              VARCHAR(8) NOT NULL,
    content             TEXT NOT NULL,
    content_bytes       INTEGER NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_sandbox_output_sequence UNIQUE (job_id, sequence_no),
    CONSTRAINT uk_agent_sandbox_output_runner_sequence
        UNIQUE (job_id, attempt_no, runner_sequence_no),
    CONSTRAINT ck_agent_sandbox_output_attempt CHECK (attempt_no BETWEEN 1 AND 10),
    CONSTRAINT ck_agent_sandbox_output_sequence CHECK (
        sequence_no > 0 AND runner_sequence_no >= 0
    ),
    CONSTRAINT ck_agent_sandbox_output_stream CHECK (stream IN ('stdout', 'stderr')),
    CONSTRAINT ck_agent_sandbox_output_content CHECK (
        content_bytes = octet_length(content)
        AND content_bytes BETWEEN 1 AND 16384
    )
);

CREATE INDEX IF NOT EXISTS idx_agent_sandbox_output_stream
    ON agent_sandbox_job_output (job_id, sequence_no);

COMMENT ON COLUMN agent_sandbox_job.source_type IS '作业来源：task_tool=任务工具调用，chat_code=私有会话代码执行';
COMMENT ON COLUMN agent_sandbox_job.owner_user_id IS '会话代码执行所有者用户ID，任务工具作业为空';
COMMENT ON COLUMN agent_sandbox_job.conversation_id IS '会话代码执行所属私有会话ID，任务工具作业为空';
COMMENT ON COLUMN agent_sandbox_job.script_language IS '经过规范化的脚本语言：python、sh或bash';
COMMENT ON COLUMN agent_sandbox_job.script_text IS '待在隔离容器内物化并执行的脚本文本，最大1MB';
COMMENT ON COLUMN agent_sandbox_job.output_sequence IS '作业内已分配的持久输出序号，用于SSE断点恢复';
COMMENT ON COLUMN agent_sandbox_job.output_bytes IS '作业所有尝试累计保存的标准输出和错误输出字节数';
COMMENT ON COLUMN agent_sandbox_job.output_truncated IS '输出是否因作业字节上限被截断';

COMMENT ON TABLE agent_sandbox_job_output IS '沙箱作业增量输出事实，按作业有序且按Runner尝试幂等';
COMMENT ON COLUMN agent_sandbox_job_output.id IS '增量输出记录主键';
COMMENT ON COLUMN agent_sandbox_job_output.job_id IS '所属沙箱作业ID';
COMMENT ON COLUMN agent_sandbox_job_output.attempt_no IS '产生输出的作业租约尝试次数';
COMMENT ON COLUMN agent_sandbox_job_output.sequence_no IS '平台分配的作业内全局连续输出序号';
COMMENT ON COLUMN agent_sandbox_job_output.runner_sequence_no IS 'Runner在本次租约内分配的幂等输出序号';
COMMENT ON COLUMN agent_sandbox_job_output.stream IS '输出流：stdout=标准输出，stderr=标准错误';
COMMENT ON COLUMN agent_sandbox_job_output.content IS '经过长度限制和敏感信息脱敏的UTF-8输出片段';
COMMENT ON COLUMN agent_sandbox_job_output.content_bytes IS '输出片段的UTF-8字节数';
COMMENT ON COLUMN agent_sandbox_job_output.created_at IS '输出片段持久化时间';

COMMIT;
