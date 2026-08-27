-- Agent 创建向导的持久幂等约束；元数据保存在既有 extra_json 扩展字段中。

BEGIN;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_definition_owner_onboarding_key
    ON agent_definition (owner_id, (extra_json ->> 'onboardingKey'))
    WHERE del_flag = '0' AND extra_json ? 'onboardingKey';

COMMENT ON INDEX uk_agent_definition_owner_onboarding_key IS
    '同一用户的 Agent 创建向导幂等键唯一，防止网络重试生成重复 Agent';

COMMIT;
