-- agent platform schema V97: durable ChatBI task-plan SSE events

BEGIN;

CREATE SEQUENCE IF NOT EXISTS agent_chatbi_task_plan_event_cursor_seq;

CREATE TABLE IF NOT EXISTS agent_chatbi_task_plan_event (
    id              BIGINT       PRIMARY KEY,
    plan_id         BIGINT       NOT NULL REFERENCES agent_chatbi_task_plan(id) ON DELETE CASCADE,
    owner_id        BIGINT       NOT NULL,
    cursor          BIGINT       NOT NULL DEFAULT nextval('agent_chatbi_task_plan_event_cursor_seq'),
    event_type      VARCHAR(64)  NOT NULL,
    payload_json    JSONB        NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_agent_chatbi_task_plan_event_cursor UNIQUE (cursor),
    CONSTRAINT ck_agent_chatbi_task_plan_event_owner CHECK (owner_id > 0),
    CONSTRAINT ck_agent_chatbi_task_plan_event_payload CHECK (jsonb_typeof(payload_json) = 'object')
);

CREATE INDEX IF NOT EXISTS idx_agent_chatbi_task_plan_event_owner_cursor
    ON agent_chatbi_task_plan_event (owner_id, plan_id, cursor);

COMMENT ON TABLE agent_chatbi_task_plan_event IS 'ChatBI任务计划持久SSE事件，用于断线和刷新后的游标重放';
COMMENT ON COLUMN agent_chatbi_task_plan_event.cursor IS '全局单调事件游标，客户端按游标请求增量重放';
COMMENT ON COLUMN agent_chatbi_task_plan_event.payload_json IS '脱敏后的兼容 ChatBI 事件 JSON';

COMMIT;
