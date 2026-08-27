-- MCP/API connector visibility is either enterprise-wide or private to one human owner.
ALTER TABLE agent_connector
    ADD COLUMN IF NOT EXISTS scope_type VARCHAR(16),
    ADD COLUMN IF NOT EXISTS owner_id BIGINT;

UPDATE agent_connector
SET scope_type = 'global', owner_id = NULL
WHERE scope_type IS NULL;

ALTER TABLE agent_connector
    ALTER COLUMN scope_type SET DEFAULT 'global',
    ALTER COLUMN scope_type SET NOT NULL;

-- Keep this migration replayable for local rehearsal and disaster recovery.
ALTER TABLE agent_connector
    DROP CONSTRAINT IF EXISTS ck_agent_connector_scope,
    DROP CONSTRAINT IF EXISTS ck_agent_connector_scope_owner;

ALTER TABLE agent_connector
    ADD CONSTRAINT ck_agent_connector_scope
        CHECK (scope_type IN ('global', 'personal')),
    ADD CONSTRAINT ck_agent_connector_scope_owner
        CHECK (
            (scope_type = 'global' AND owner_id IS NULL)
            OR (scope_type = 'personal' AND owner_id IS NOT NULL)
        );

CREATE INDEX IF NOT EXISTS idx_agent_connector_scope_owner_status
    ON agent_connector (scope_type, owner_id, status)
    WHERE del_flag = '0';

DROP INDEX IF EXISTS uk_agent_connector_key_active;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_connector_global_key_active
    ON agent_connector (connector_key)
    WHERE del_flag = '0' AND scope_type = 'global';

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_connector_personal_owner_key_active
    ON agent_connector (owner_id, connector_key)
    WHERE del_flag = '0' AND scope_type = 'personal';

COMMENT ON COLUMN agent_connector.scope_type IS '可见范围：global 企业共享，personal 个人私有';
COMMENT ON COLUMN agent_connector.owner_id IS '个人连接器所有者用户ID；企业共享连接器为空';
