-- Durable, idempotent scenario-template uninstall operations.
BEGIN;

CREATE TABLE IF NOT EXISTS agent_scenario_uninstall_run (
    id                  BIGINT PRIMARY KEY,
    instance_id         BIGINT NOT NULL,
    template_key        VARCHAR(128) NOT NULL,
    idempotency_key     VARCHAR(128) NOT NULL,
    status              VARCHAR(24) NOT NULL,
    reason              VARCHAR(1000) NOT NULL,
    previous_status     VARCHAR(24) NOT NULL,
    agent_status        VARCHAR(24) NOT NULL,
    warning             VARCHAR(1000),
    created_by          BIGINT NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP NOT NULL,
    CONSTRAINT uk_agent_scenario_uninstall_idempotency UNIQUE (instance_id, idempotency_key),
    CONSTRAINT ck_agent_scenario_uninstall_status CHECK (status IN ('succeeded', 'failed')),
    CONSTRAINT ck_agent_scenario_uninstall_agent_status CHECK (agent_status IN ('disabled', 'archived', 'not_found'))
);

CREATE INDEX IF NOT EXISTS idx_agent_scenario_uninstall_instance
    ON agent_scenario_uninstall_run (instance_id, created_at DESC);

COMMENT ON TABLE agent_scenario_uninstall_run IS '场景模板实例卸载与关联 Agent 停用的幂等运行事实';
COMMENT ON COLUMN agent_scenario_uninstall_run.id IS '卸载运行主键';
COMMENT ON COLUMN agent_scenario_uninstall_run.instance_id IS '场景模板实例主键';
COMMENT ON COLUMN agent_scenario_uninstall_run.template_key IS '场景模板稳定标识';
COMMENT ON COLUMN agent_scenario_uninstall_run.idempotency_key IS '调用方提供或服务端派生的幂等键';
COMMENT ON COLUMN agent_scenario_uninstall_run.status IS '卸载运行状态：succeeded 或 failed';
COMMENT ON COLUMN agent_scenario_uninstall_run.reason IS '操作者填写的卸载原因';
COMMENT ON COLUMN agent_scenario_uninstall_run.previous_status IS '卸载前场景实例状态';
COMMENT ON COLUMN agent_scenario_uninstall_run.agent_status IS '卸载后关联 Agent 状态或不存在标记';
COMMENT ON COLUMN agent_scenario_uninstall_run.warning IS '关联资源不完整时的非阻断警告';
COMMENT ON COLUMN agent_scenario_uninstall_run.created_by IS '发起卸载的主体主键';
COMMENT ON COLUMN agent_scenario_uninstall_run.created_at IS '卸载运行创建时间';
COMMENT ON COLUMN agent_scenario_uninstall_run.completed_at IS '卸载运行完成时间';

COMMIT;
