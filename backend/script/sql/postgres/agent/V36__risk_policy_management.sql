-- agent platform schema V36: structured risk policy management

BEGIN;

CREATE TABLE IF NOT EXISTS agent_risk_policy (
    id                  BIGINT PRIMARY KEY,
    policy_key          VARCHAR(128) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    resource_type       VARCHAR(64) NOT NULL,
    action              VARCHAR(64) NOT NULL,
    risk_level          VARCHAR(2) NOT NULL,
    disposition         VARCHAR(32) NOT NULL,
    approval_role       VARCHAR(64),
    notify_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    priority            INTEGER NOT NULL DEFAULT 100,
    description         VARCHAR(500),
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    create_by           BIGINT NOT NULL,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    CONSTRAINT ck_agent_risk_policy_level CHECK (risk_level IN ('R0', 'R1', 'R2', 'R3')),
    CONSTRAINT ck_agent_risk_policy_disposition
        CHECK (disposition IN ('allow', 'approval_required', 'deny')),
    CONSTRAINT ck_agent_risk_policy_status CHECK (status IN ('active', 'disabled')),
    CONSTRAINT ck_agent_risk_policy_priority CHECK (priority BETWEEN 0 AND 9999),
    CONSTRAINT ck_agent_risk_policy_approval
        CHECK (disposition <> 'approval_required' OR approval_role IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_risk_policy_key
    ON agent_risk_policy (policy_key)
    WHERE del_flag = '0';

CREATE INDEX IF NOT EXISTS idx_agent_risk_policy_match
    ON agent_risk_policy (resource_type, action, status, priority DESC)
    WHERE del_flag = '0';

CREATE INDEX IF NOT EXISTS idx_agent_risk_policy_level
    ON agent_risk_policy (risk_level, status, priority DESC)
    WHERE del_flag = '0';

COMMENT ON TABLE agent_risk_policy IS '结构化风险策略目录，不使用业务外键，按优先级匹配资源动作';

COMMIT;
