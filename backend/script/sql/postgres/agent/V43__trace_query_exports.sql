-- agent platform schema V43: bind governed query snapshots to durable runtime traces

BEGIN;

ALTER TABLE agent_data_query
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_agent_data_query_trace_owner
    ON agent_data_query (trace_id, created_by, created_at DESC)
    WHERE trace_id IS NOT NULL AND status = 'succeeded';

COMMENT ON COLUMN agent_data_query.trace_id IS '触发当前数据查询的运行链路标识，用于本人执行日志追溯与结果导出';

COMMIT;
