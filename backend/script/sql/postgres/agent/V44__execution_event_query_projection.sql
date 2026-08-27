-- agent platform schema V44: bounded safe projections for runtime trace queries

BEGIN;

ALTER TABLE agent_execution_event
    ADD COLUMN IF NOT EXISTS query_projection_json JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN agent_execution_event.query_projection_json IS
    'AgentScope生成的字段白名单查询投影；原始payload仍按sensitive_level边界读取';

COMMIT;
