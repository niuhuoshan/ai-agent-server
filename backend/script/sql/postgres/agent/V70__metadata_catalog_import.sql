-- Durable DDL/YAML metadata import previews and selective atomic apply.

BEGIN;

CREATE TABLE IF NOT EXISTS agent_data_catalog_import_preview (
    id                  BIGINT PRIMARY KEY,
    dataset_id          BIGINT NOT NULL,
    source_type         VARCHAR(16) NOT NULL,
    source_hash         CHAR(64) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'draft',
    dataset_revision    INTEGER NOT NULL,
    revision_no         INTEGER NOT NULL DEFAULT 1,
    table_count         INTEGER NOT NULL DEFAULT 0,
    column_count        INTEGER NOT NULL DEFAULT 0,
    diagnostics_json    JSONB NOT NULL DEFAULT '[]'::jsonb,
    expires_at          TIMESTAMP NOT NULL,
    created_by          BIGINT NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_by          BIGINT,
    applied_at          TIMESTAMP,
    CONSTRAINT ck_agent_data_catalog_import_preview_source
        CHECK (source_type IN ('ddl', 'yaml')),
    CONSTRAINT ck_agent_data_catalog_import_preview_status
        CHECK (status IN ('draft', 'applied', 'expired')),
    CONSTRAINT ck_agent_data_catalog_import_preview_revisions
        CHECK (dataset_revision > 0 AND revision_no > 0),
    CONSTRAINT ck_agent_data_catalog_import_preview_counts
        CHECK (table_count >= 0 AND column_count >= 0),
    CONSTRAINT ck_agent_data_catalog_import_preview_diagnostics
        CHECK (jsonb_typeof(diagnostics_json) = 'array')
);

CREATE INDEX IF NOT EXISTS idx_agent_data_catalog_import_preview_dataset
    ON agent_data_catalog_import_preview (dataset_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_agent_data_catalog_import_preview_expiry
    ON agent_data_catalog_import_preview (status, expires_at)
    WHERE status = 'draft';

CREATE TABLE IF NOT EXISTS agent_data_catalog_import_item (
    id                      BIGINT PRIMARY KEY,
    preview_id              BIGINT NOT NULL,
    item_type               VARCHAR(24) NOT NULL,
    resource_key            VARCHAR(1000) NOT NULL,
    action                  VARCHAR(16) NOT NULL,
    current_hash            CHAR(64),
    content_hash            CHAR(64) NOT NULL,
    proposed_json           JSONB NOT NULL,
    status                  VARCHAR(16) NOT NULL DEFAULT 'available',
    applied_resource_id     BIGINT,
    error_message           VARCHAR(1000),
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP,
    CONSTRAINT uk_agent_data_catalog_import_item
        UNIQUE (preview_id, item_type, resource_key),
    CONSTRAINT ck_agent_data_catalog_import_item_type
        CHECK (item_type IN ('table', 'metric', 'relationship')),
    CONSTRAINT ck_agent_data_catalog_import_item_action
        CHECK (action IN ('create', 'update')),
    CONSTRAINT ck_agent_data_catalog_import_item_json
        CHECK (jsonb_typeof(proposed_json) = 'object'),
    CONSTRAINT ck_agent_data_catalog_import_item_status
        CHECK (status IN ('available', 'applied', 'skipped'))
);

CREATE INDEX IF NOT EXISTS idx_agent_data_catalog_import_item_preview
    ON agent_data_catalog_import_item (preview_id, status, item_type, id);

ALTER TABLE agent_data_metadata_change
    DROP CONSTRAINT IF EXISTS ck_agent_data_metadata_change_resource;

ALTER TABLE agent_data_metadata_change
    ADD CONSTRAINT ck_agent_data_metadata_change_resource
        CHECK (resource_type IN (
            'metric', 'relationship', 'row_policy', 'table', 'column',
            'profile_job', 'table_profile', 'smart_import', 'metadata_import'
        ));

COMMENT ON TABLE agent_data_catalog_import_preview IS 'DDL或YAML元数据导入的持久化预览';
COMMENT ON COLUMN agent_data_catalog_import_preview.id IS '导入预览ID';
COMMENT ON COLUMN agent_data_catalog_import_preview.dataset_id IS '目标数据集ID';
COMMENT ON COLUMN agent_data_catalog_import_preview.source_type IS '来源格式：DDL或YAML';
COMMENT ON COLUMN agent_data_catalog_import_preview.source_hash IS '原始输入内容的SHA-256，不保存原文';
COMMENT ON COLUMN agent_data_catalog_import_preview.status IS '预览状态：草稿、已应用或已过期';
COMMENT ON COLUMN agent_data_catalog_import_preview.dataset_revision IS '创建预览时的数据集版本号';
COMMENT ON COLUMN agent_data_catalog_import_preview.revision_no IS '预览乐观锁版本号';
COMMENT ON COLUMN agent_data_catalog_import_preview.table_count IS '解析得到的数据表数量';
COMMENT ON COLUMN agent_data_catalog_import_preview.column_count IS '解析得到的数据列总数';
COMMENT ON COLUMN agent_data_catalog_import_preview.diagnostics_json IS '不含原始输入的解析诊断列表';
COMMENT ON COLUMN agent_data_catalog_import_preview.expires_at IS '预览过期时间';
COMMENT ON COLUMN agent_data_catalog_import_preview.created_by IS '创建预览的用户ID';
COMMENT ON COLUMN agent_data_catalog_import_preview.created_at IS '预览创建时间';
COMMENT ON COLUMN agent_data_catalog_import_preview.applied_by IS '应用预览的用户ID';
COMMENT ON COLUMN agent_data_catalog_import_preview.applied_at IS '预览应用时间';

COMMENT ON TABLE agent_data_catalog_import_item IS '元数据导入预览中的可选择原子项';
COMMENT ON COLUMN agent_data_catalog_import_item.id IS '导入项ID';
COMMENT ON COLUMN agent_data_catalog_import_item.preview_id IS '所属导入预览ID';
COMMENT ON COLUMN agent_data_catalog_import_item.item_type IS '导入项类型：表、指标或关系';
COMMENT ON COLUMN agent_data_catalog_import_item.resource_key IS '数据集内稳定资源标识';
COMMENT ON COLUMN agent_data_catalog_import_item.action IS '建议动作：创建或更新';
COMMENT ON COLUMN agent_data_catalog_import_item.current_hash IS '创建预览时目标资源快照的SHA-256';
COMMENT ON COLUMN agent_data_catalog_import_item.content_hash IS '建议内容规范JSON的SHA-256';
COMMENT ON COLUMN agent_data_catalog_import_item.proposed_json IS '经过校验的结构化导入建议，不含输入原文';
COMMENT ON COLUMN agent_data_catalog_import_item.status IS '导入项状态：可应用、已应用或已跳过';
COMMENT ON COLUMN agent_data_catalog_import_item.applied_resource_id IS '实际创建或更新的平台资源ID';
COMMENT ON COLUMN agent_data_catalog_import_item.error_message IS '处理失败的脱敏摘要';
COMMENT ON COLUMN agent_data_catalog_import_item.created_at IS '导入项创建时间';
COMMENT ON COLUMN agent_data_catalog_import_item.updated_at IS '导入项最后更新时间';

COMMENT ON COLUMN agent_data_metadata_change.resource_type IS
    '资源类型：指标、关系、行策略、表、字段、画像任务、表画像、智能导入或元数据导入';

COMMIT;
