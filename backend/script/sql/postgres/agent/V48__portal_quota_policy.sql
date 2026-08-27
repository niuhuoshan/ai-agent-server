-- agent platform schema V48: Nhs portal monthly Token quota policies

BEGIN;

CREATE TABLE IF NOT EXISTS agent_quota_policy (
    id               BIGINT PRIMARY KEY,
    scope_type       VARCHAR(16) NOT NULL,
    scope_id         BIGINT,
    period           VARCHAR(16) NOT NULL DEFAULT 'monthly',
    limit_tokens     BIGINT,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    action_on_exceed VARCHAR(16) NOT NULL DEFAULT 'block',
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_quota_policy_scope
        CHECK ((scope_type = 'system' AND scope_id IS NULL)
            OR (scope_type IN ('user', 'role') AND scope_id IS NOT NULL AND scope_id > 0)),
    CONSTRAINT ck_agent_quota_policy_period CHECK (period = 'monthly'),
    CONSTRAINT ck_agent_quota_policy_limit CHECK (limit_tokens IS NULL OR limit_tokens >= 0),
    CONSTRAINT ck_agent_quota_policy_action CHECK (action_on_exceed = 'block')
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_quota_policy_scope
    ON agent_quota_policy (scope_type, period, COALESCE(scope_id, 0));
CREATE INDEX IF NOT EXISTS idx_agent_quota_policy_user
    ON agent_quota_policy (scope_type, scope_id)
    WHERE scope_type IN ('user', 'role');

COMMENT ON TABLE agent_quota_policy IS '门户 Token 月度额度策略表';
COMMENT ON COLUMN agent_quota_policy.id IS '主键ID';
COMMENT ON COLUMN agent_quota_policy.scope_type IS '策略范围：system系统、user用户、role角色';
COMMENT ON COLUMN agent_quota_policy.scope_id IS '用户ID或角色ID；系统策略为空';
COMMENT ON COLUMN agent_quota_policy.period IS '额度周期：monthly月度';
COMMENT ON COLUMN agent_quota_policy.limit_tokens IS '月 Token 上限；为空表示不限额';
COMMENT ON COLUMN agent_quota_policy.enabled IS '是否启用策略';
COMMENT ON COLUMN agent_quota_policy.action_on_exceed IS '超额动作：block阻断';
COMMENT ON COLUMN agent_quota_policy.created_at IS '创建时间';
COMMENT ON COLUMN agent_quota_policy.updated_at IS '最后更新时间';

COMMIT;
