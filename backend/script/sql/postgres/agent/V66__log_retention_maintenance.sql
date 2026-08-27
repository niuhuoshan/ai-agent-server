-- PostgreSQL-native audit and execution log retention control plane.

BEGIN;

CREATE TABLE IF NOT EXISTS agent_log_retention_policy (
    id                  SMALLINT PRIMARY KEY,
    retention_days      INTEGER NOT NULL DEFAULT 90,
    revision_no         INTEGER NOT NULL DEFAULT 1,
    updated_by          BIGINT,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    change_reason       VARCHAR(500) NOT NULL DEFAULT 'initial policy',
    CONSTRAINT ck_agent_log_retention_singleton CHECK (id = 1),
    CONSTRAINT ck_agent_log_retention_days CHECK (retention_days BETWEEN 1 AND 3650),
    CONSTRAINT ck_agent_log_retention_revision CHECK (revision_no > 0)
);

INSERT INTO agent_log_retention_policy (
    id, retention_days, revision_no, updated_at, change_reason
) VALUES (
    1, 90, 1, CURRENT_TIMESTAMP, 'initial policy'
)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS agent_log_maintenance_run (
    id                          BIGINT PRIMARY KEY,
    trigger_type                VARCHAR(16) NOT NULL,
    status                      VARCHAR(16) NOT NULL,
    retention_days              INTEGER NOT NULL,
    policy_revision             INTEGER NOT NULL,
    cutoff_at                   TIMESTAMP NOT NULL,
    confirmation_token_hash     CHAR(64),
    confirmation_expires_at     TIMESTAMP,
    requested_by                BIGINT,
    confirmed_at                TIMESTAMP,
    started_at                  TIMESTAMP,
    finished_at                 TIMESTAMP,
    summary_json                JSONB,
    error_code                  VARCHAR(64),
    error_message               VARCHAR(1000),
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_log_maintenance_trigger CHECK (trigger_type IN ('manual', 'scheduled')),
    CONSTRAINT ck_agent_log_maintenance_status CHECK (
        status IN ('previewed', 'running', 'succeeded', 'partial', 'failed', 'expired', 'skipped')
    ),
    CONSTRAINT ck_agent_log_maintenance_days CHECK (retention_days BETWEEN 1 AND 3650),
    CONSTRAINT ck_agent_log_maintenance_revision CHECK (policy_revision > 0),
    CONSTRAINT ck_agent_log_maintenance_confirmation CHECK (
        (trigger_type = 'manual' AND confirmation_token_hash IS NOT NULL AND confirmation_expires_at IS NOT NULL)
        OR (trigger_type = 'scheduled' AND confirmation_token_hash IS NULL AND confirmation_expires_at IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_agent_log_maintenance_created
    ON agent_log_maintenance_run (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_log_maintenance_status
    ON agent_log_maintenance_run (status, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_log_maintenance_single_running
    ON agent_log_maintenance_run ((1)) WHERE status = 'running';

COMMENT ON TABLE agent_log_retention_policy IS '审计日志与执行事件保留策略表';
COMMENT ON COLUMN agent_log_retention_policy.id IS '单例主键，固定为1';
COMMENT ON COLUMN agent_log_retention_policy.retention_days IS '在线日志保留天数，范围1到3650天';
COMMENT ON COLUMN agent_log_retention_policy.revision_no IS '乐观锁版本号';
COMMENT ON COLUMN agent_log_retention_policy.updated_by IS '最后更新管理员用户ID';
COMMENT ON COLUMN agent_log_retention_policy.updated_at IS '最后更新时间';
COMMENT ON COLUMN agent_log_retention_policy.change_reason IS '策略变更原因';

COMMENT ON TABLE agent_log_maintenance_run IS '日志清理预览、确认与定时维护运行记录表';
COMMENT ON COLUMN agent_log_maintenance_run.id IS '运行记录雪花ID';
COMMENT ON COLUMN agent_log_maintenance_run.trigger_type IS '触发类型：manual手动、scheduled定时';
COMMENT ON COLUMN agent_log_maintenance_run.status IS '运行状态：预览、执行中、成功、部分完成、失败、过期或跳过';
COMMENT ON COLUMN agent_log_maintenance_run.retention_days IS '本次运行冻结的保留天数';
COMMENT ON COLUMN agent_log_maintenance_run.policy_revision IS '本次运行冻结的策略版本';
COMMENT ON COLUMN agent_log_maintenance_run.cutoff_at IS '早于该时间的数据属于过期数据';
COMMENT ON COLUMN agent_log_maintenance_run.confirmation_token_hash IS '手动清理确认令牌SHA-256摘要';
COMMENT ON COLUMN agent_log_maintenance_run.confirmation_expires_at IS '手动确认令牌失效时间';
COMMENT ON COLUMN agent_log_maintenance_run.requested_by IS '发起预览或清理的管理员用户ID';
COMMENT ON COLUMN agent_log_maintenance_run.confirmed_at IS '管理员明确确认时间';
COMMENT ON COLUMN agent_log_maintenance_run.started_at IS '实际维护开始时间';
COMMENT ON COLUMN agent_log_maintenance_run.finished_at IS '实际维护结束时间';
COMMENT ON COLUMN agent_log_maintenance_run.summary_json IS '创建分区、删除分区和微批清理结果摘要';
COMMENT ON COLUMN agent_log_maintenance_run.error_code IS '稳定错误码';
COMMENT ON COLUMN agent_log_maintenance_run.error_message IS '脱敏后的错误说明';
COMMENT ON COLUMN agent_log_maintenance_run.created_at IS '记录创建时间';
COMMENT ON COLUMN agent_log_maintenance_run.updated_at IS '记录最后更新时间';

COMMIT;
