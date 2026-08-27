-- Nhs portal prompt tooling, slash commands and ChatBI business briefs.
-- ChatBI monitors reuse agent_report and agent_report_subscription from V6/V41.

BEGIN;

CREATE TABLE IF NOT EXISTS agent_portal_slash_command (
    id          BIGINT PRIMARY KEY,
    label       VARCHAR(128) NOT NULL,
    command     VARCHAR(2048) NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    created_by  BIGINT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP,
    del_flag    CHAR(1) NOT NULL DEFAULT '0',
    CONSTRAINT ck_agent_portal_slash_command_del_flag CHECK (del_flag IN ('0', '1')),
    CONSTRAINT ck_agent_portal_slash_command_command CHECK (command <> '')
);

CREATE INDEX IF NOT EXISTS idx_agent_portal_slash_command_owner
    ON agent_portal_slash_command (created_by, sort_order, id)
    WHERE del_flag = '0';

CREATE TABLE IF NOT EXISTS agent_chatbi_brief (
    id               VARCHAR(64) PRIMARY KEY,
    owner_id         BIGINT NOT NULL,
    conversation_id  VARCHAR(128) NOT NULL,
    result_id        VARCHAR(128),
    title            VARCHAR(255) NOT NULL,
    brief_payload    JSONB NOT NULL DEFAULT '{}'::jsonb,
    markdown_content TEXT NOT NULL,
    artifact_payload JSONB,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP,
    del_flag         CHAR(1) NOT NULL DEFAULT '0',
    CONSTRAINT ck_agent_chatbi_brief_del_flag CHECK (del_flag IN ('0', '1'))
);

CREATE INDEX IF NOT EXISTS idx_agent_chatbi_brief_owner_time
    ON agent_chatbi_brief (owner_id, created_at DESC)
    WHERE del_flag = '0';

COMMENT ON TABLE agent_portal_slash_command IS 'Nhs 门户快捷指令';
COMMENT ON COLUMN agent_portal_slash_command.created_by IS '创建用户 ID；系统指令使用 0';
COMMENT ON TABLE agent_chatbi_brief IS 'ChatBI 可追溯业务简报';
COMMENT ON COLUMN agent_chatbi_brief.brief_payload IS '结构化简报正文（不含 markdown）';
COMMENT ON COLUMN agent_chatbi_brief.artifact_payload IS '生成文件能力链接元数据';

COMMIT;
