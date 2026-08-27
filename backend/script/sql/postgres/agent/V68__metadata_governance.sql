-- Metadata governance: versioned relations and durable, content-addressed change records.

BEGIN;

ALTER TABLE agent_data_relation
    ADD COLUMN IF NOT EXISTS revision_no INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS updated_by BIGINT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

ALTER TABLE agent_data_relation
    DROP CONSTRAINT IF EXISTS ck_agent_data_relation_revision,
    DROP CONSTRAINT IF EXISTS ck_agent_data_relation_status;

ALTER TABLE agent_data_relation
    ADD CONSTRAINT ck_agent_data_relation_revision CHECK (revision_no > 0),
    ADD CONSTRAINT ck_agent_data_relation_status CHECK (status IN ('active', 'inactive'));

ALTER TABLE agent_data_metric
    DROP CONSTRAINT IF EXISTS ck_agent_data_metric_status;

ALTER TABLE agent_data_metric
    ADD CONSTRAINT ck_agent_data_metric_status CHECK (status IN ('active', 'inactive'));

CREATE TABLE IF NOT EXISTS agent_data_metadata_change (
    id              BIGINT PRIMARY KEY,
    dataset_id      BIGINT NOT NULL,
    resource_type   VARCHAR(32) NOT NULL,
    resource_id     BIGINT,
    action          VARCHAR(32) NOT NULL,
    before_json     JSONB,
    after_json      JSONB,
    before_hash     CHAR(64),
    after_hash      CHAR(64),
    actor_id        BIGINT NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_data_metadata_change_resource
        CHECK (resource_type IN ('metric', 'relationship', 'row_policy')),
    CONSTRAINT ck_agent_data_metadata_change_action
        CHECK (action IN ('create', 'update', 'archive'))
);

CREATE INDEX IF NOT EXISTS idx_agent_data_metadata_change_dataset
    ON agent_data_metadata_change (dataset_id, created_at DESC, id DESC);

COMMENT ON COLUMN agent_data_relation.revision_no IS '关系定义乐观锁版本号';
COMMENT ON COLUMN agent_data_relation.updated_by IS '最后更新用户ID';
COMMENT ON COLUMN agent_data_relation.updated_at IS '最后更新时间';

COMMENT ON TABLE agent_data_metadata_change IS '数据集元数据治理变更记录';
COMMENT ON COLUMN agent_data_metadata_change.id IS '变更记录ID';
COMMENT ON COLUMN agent_data_metadata_change.dataset_id IS '数据集ID';
COMMENT ON COLUMN agent_data_metadata_change.resource_type IS '资源类型：指标、关系或行策略';
COMMENT ON COLUMN agent_data_metadata_change.resource_id IS '被变更资源ID，行策略使用数据集ID';
COMMENT ON COLUMN agent_data_metadata_change.action IS '变更动作：创建、更新或归档';
COMMENT ON COLUMN agent_data_metadata_change.before_json IS '变更前结构化快照';
COMMENT ON COLUMN agent_data_metadata_change.after_json IS '变更后结构化快照';
COMMENT ON COLUMN agent_data_metadata_change.before_hash IS '变更前规范JSON的SHA-256';
COMMENT ON COLUMN agent_data_metadata_change.after_hash IS '变更后规范JSON的SHA-256';
COMMENT ON COLUMN agent_data_metadata_change.actor_id IS '操作用户ID';
COMMENT ON COLUMN agent_data_metadata_change.created_at IS '变更时间';

COMMIT;
