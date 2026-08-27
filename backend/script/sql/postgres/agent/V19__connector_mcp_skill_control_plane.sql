-- Connector, MCP discovery and Skill control-plane integrity.
-- Logical references remain application-managed; no cross-table foreign keys are added.

BEGIN;

ALTER TABLE agent_connector
    ADD COLUMN IF NOT EXISTS revision_no BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS last_discovery_id BIGINT;

ALTER TABLE agent_tool
    ADD COLUMN IF NOT EXISTS discovery_id BIGINT,
    ADD COLUMN IF NOT EXISTS remote_schema_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS is_available BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE agent_skill
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS revision_no BIGINT NOT NULL DEFAULT 1;

CREATE TABLE IF NOT EXISTS agent_mcp_discovery (
    id                  BIGINT PRIMARY KEY,
    connector_id        BIGINT NOT NULL,
    connector_revision  BIGINT NOT NULL,
    status              VARCHAR(16) NOT NULL,
    protocol_version    VARCHAR(32),
    server_info_json    JSONB,
    tool_count          INTEGER NOT NULL DEFAULT 0,
    content_hash        CHAR(64),
    error_summary       VARCHAR(1000),
    started_by          BIGINT NOT NULL,
    started_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP,
    CONSTRAINT ck_agent_mcp_discovery_status
        CHECK (status IN ('running', 'succeeded', 'failed')),
    CONSTRAINT ck_agent_mcp_discovery_tool_count
        CHECK (tool_count >= 0 AND tool_count <= 500)
);

CREATE INDEX IF NOT EXISTS idx_agent_mcp_discovery_connector_started
    ON agent_mcp_discovery (connector_id, started_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_mcp_discovery_running
    ON agent_mcp_discovery (connector_id) WHERE status = 'running';
CREATE INDEX IF NOT EXISTS idx_agent_tool_discovery
    ON agent_tool (discovery_id) WHERE discovery_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_tool_connector_available
    ON agent_tool (connector_id, is_available, status);
CREATE INDEX IF NOT EXISTS idx_agent_skill_owner_scope
    ON agent_skill (owner_id, scope_type, scope_id, status) WHERE del_flag = '0';

COMMENT ON TABLE agent_mcp_discovery IS 'MCP服务工具发现快照';
COMMENT ON COLUMN agent_connector.revision_no IS '连接器配置乐观并发修订号';
COMMENT ON COLUMN agent_connector.last_discovery_id IS '最近一次成功MCP发现记录ID';
COMMENT ON COLUMN agent_tool.discovery_id IS '生成该工具版本的MCP发现记录ID';
COMMENT ON COLUMN agent_tool.remote_schema_hash IS '远端工具名称、描述和Schema规范化哈希';
COMMENT ON COLUMN agent_tool.is_available IS '远端工具当前是否仍可用';
COMMENT ON COLUMN agent_skill.revision_no IS '技能元数据乐观并发修订号';

COMMIT;
