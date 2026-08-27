-- agent platform schema V87: bounded, redacted execution timeline snapshots

BEGIN;

CREATE TABLE IF NOT EXISTS agent_execution_timeline_snapshot (
    id              BIGINT PRIMARY KEY,
    trace_id        VARCHAR(128) NOT NULL,
    conversation_id BIGINT,
    task_id         BIGINT,
    run_id          BIGINT,
    timeline_json   JSONB NOT NULL,
    content_hash    CHAR(64) NOT NULL,
    generated_at    TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_agent_execution_timeline_trace UNIQUE (trace_id),
    CONSTRAINT ck_agent_execution_timeline_trace CHECK (
        trace_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
    ),
    CONSTRAINT ck_agent_execution_timeline_json CHECK (
        jsonb_typeof(timeline_json) = 'array'
    ),
    CONSTRAINT ck_agent_execution_timeline_hash CHECK (
        content_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX IF NOT EXISTS idx_agent_execution_timeline_conversation
    ON agent_execution_timeline_snapshot (conversation_id, generated_at DESC)
    WHERE conversation_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_execution_timeline_task_run
    ON agent_execution_timeline_snapshot (task_id, run_id, generated_at DESC)
    WHERE task_id IS NOT NULL;

COMMENT ON TABLE agent_execution_timeline_snapshot IS '执行时间线脱敏语义快照，供聊天、任务、调试和Embed历史回放复用';
COMMENT ON COLUMN agent_execution_timeline_snapshot.id IS '时间线快照主键';
COMMENT ON COLUMN agent_execution_timeline_snapshot.trace_id IS '运行Trace标识，单Trace只保留最新快照';
COMMENT ON COLUMN agent_execution_timeline_snapshot.conversation_id IS '关联私有会话主键';
COMMENT ON COLUMN agent_execution_timeline_snapshot.task_id IS '关联共享任务主键';
COMMENT ON COLUMN agent_execution_timeline_snapshot.run_id IS '关联任务运行主键';
COMMENT ON COLUMN agent_execution_timeline_snapshot.timeline_json IS '已聚合且已脱敏的语义时间线条目数组';
COMMENT ON COLUMN agent_execution_timeline_snapshot.content_hash IS '时间线规范JSON SHA-256';
COMMENT ON COLUMN agent_execution_timeline_snapshot.generated_at IS '快照生成时间';
COMMENT ON COLUMN agent_execution_timeline_snapshot.created_at IS '首次写入时间';
COMMENT ON COLUMN agent_execution_timeline_snapshot.updated_at IS '最近更新时间';

COMMIT;
