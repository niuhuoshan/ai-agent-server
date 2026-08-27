-- Metadata Profile：可恢复画像任务、表级画像事实、关联建议与选择性智能导入。

BEGIN;

CREATE TABLE IF NOT EXISTS agent_data_profile_job (
    id                          BIGINT PRIMARY KEY,
    dataset_id                  BIGINT NOT NULL,
    data_source_id              BIGINT NOT NULL,
    mode                        VARCHAR(16) NOT NULL,
    status                      VARCHAR(16) NOT NULL DEFAULT 'queued',
    requested_table_ids_json    JSONB NOT NULL DEFAULT '[]'::jsonb,
    dataset_revision            INTEGER NOT NULL,
    data_source_revision        INTEGER NOT NULL,
    total_tables                INTEGER NOT NULL DEFAULT 0,
    completed_tables            INTEGER NOT NULL DEFAULT 0,
    failed_tables               INTEGER NOT NULL DEFAULT 0,
    progress_percent            NUMERIC(5, 2) NOT NULL DEFAULT 0,
    current_table_id            BIGINT,
    cancel_requested            BOOLEAN NOT NULL DEFAULT FALSE,
    resume_of_job_id            BIGINT,
    worker_id                   VARCHAR(255),
    lease_until                 TIMESTAMP,
    attempt_no                  INTEGER NOT NULL DEFAULT 0,
    max_attempts                INTEGER NOT NULL DEFAULT 5,
    revision_no                 INTEGER NOT NULL DEFAULT 1,
    error_message               VARCHAR(1000),
    requested_by                BIGINT NOT NULL,
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at                  TIMESTAMP,
    finished_at                 TIMESTAMP,
    updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_data_profile_job_mode
        CHECK (mode IN ('full', 'incremental')),
    CONSTRAINT ck_agent_data_profile_job_status
        CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
    CONSTRAINT ck_agent_data_profile_job_requested_tables
        CHECK (jsonb_typeof(requested_table_ids_json) = 'array'),
    CONSTRAINT ck_agent_data_profile_job_revisions
        CHECK (dataset_revision > 0 AND data_source_revision > 0 AND revision_no > 0),
    CONSTRAINT ck_agent_data_profile_job_counts
        CHECK (
            total_tables >= 0 AND completed_tables >= 0 AND failed_tables >= 0
            AND completed_tables + failed_tables <= total_tables
        ),
    CONSTRAINT ck_agent_data_profile_job_progress
        CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_agent_data_profile_job_attempts
        CHECK (attempt_no >= 0 AND max_attempts BETWEEN 1 AND 20)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_data_profile_job_active_dataset
    ON agent_data_profile_job (dataset_id)
    WHERE status IN ('queued', 'running');

CREATE INDEX IF NOT EXISTS idx_agent_data_profile_job_dataset_created
    ON agent_data_profile_job (dataset_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_agent_data_profile_job_claim
    ON agent_data_profile_job (status, lease_until, created_at, id)
    WHERE status IN ('queued', 'running');

CREATE TABLE IF NOT EXISTS agent_data_profile_job_table (
    id                  BIGINT PRIMARY KEY,
    job_id              BIGINT NOT NULL,
    dataset_id          BIGINT NOT NULL,
    table_id            BIGINT NOT NULL,
    sequence_no         INTEGER NOT NULL,
    source_hash         CHAR(64) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'pending',
    attempt_no          INTEGER NOT NULL DEFAULT 0,
    profile_id          BIGINT,
    error_message       VARCHAR(1000),
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_data_profile_job_table UNIQUE (job_id, table_id),
    CONSTRAINT uk_agent_data_profile_job_sequence UNIQUE (job_id, sequence_no),
    CONSTRAINT ck_agent_data_profile_job_table_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_agent_data_profile_job_table_status
        CHECK (status IN ('pending', 'running', 'succeeded', 'failed')),
    CONSTRAINT ck_agent_data_profile_job_table_attempt CHECK (attempt_no >= 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_data_profile_job_table_progress
    ON agent_data_profile_job_table (job_id, status, sequence_no, id);

CREATE TABLE IF NOT EXISTS agent_data_table_profile (
    id                          BIGINT PRIMARY KEY,
    dataset_id                  BIGINT NOT NULL,
    table_id                    BIGINT NOT NULL,
    job_id                      BIGINT NOT NULL,
    source_hash                 CHAR(64) NOT NULL,
    table_type                  VARCHAR(24) NOT NULL,
    term                        VARCHAR(255) NOT NULL,
    description                 TEXT NOT NULL,
    ddl_text                    TEXT,
    row_count_estimate          BIGINT,
    column_count                INTEGER NOT NULL,
    columns_profile_json        JSONB NOT NULL DEFAULT '[]'::jsonb,
    sample_data_json            JSONB NOT NULL DEFAULT '[]'::jsonb,
    sample_row_count            INTEGER NOT NULL DEFAULT 0,
    sample_redacted             BOOLEAN NOT NULL DEFAULT FALSE,
    confidence_score            NUMERIC(5, 2) NOT NULL,
    confidence_reason           VARCHAR(1000),
    tags_json                   JSONB NOT NULL DEFAULT '[]'::jsonb,
    temporary_classification    VARCHAR(24) NOT NULL DEFAULT 'business',
    ignored                     BOOLEAN NOT NULL DEFAULT FALSE,
    ignore_decision             VARCHAR(24) NOT NULL DEFAULT 'auto_include',
    profile_json                JSONB NOT NULL DEFAULT '{}'::jsonb,
    revision_no                 INTEGER NOT NULL DEFAULT 1,
    created_by                  BIGINT NOT NULL,
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                  BIGINT,
    updated_at                  TIMESTAMP,
    CONSTRAINT uk_agent_data_table_profile_job UNIQUE (job_id, table_id),
    CONSTRAINT ck_agent_data_table_profile_row_count
        CHECK (row_count_estimate IS NULL OR row_count_estimate >= 0),
    CONSTRAINT ck_agent_data_table_profile_column_count CHECK (column_count >= 0),
    CONSTRAINT ck_agent_data_table_profile_columns_json
        CHECK (jsonb_typeof(columns_profile_json) = 'array'),
    CONSTRAINT ck_agent_data_table_profile_samples_json
        CHECK (jsonb_typeof(sample_data_json) = 'array' AND sample_row_count BETWEEN 0 AND 3),
    CONSTRAINT ck_agent_data_table_profile_confidence CHECK (confidence_score BETWEEN 0 AND 100),
    CONSTRAINT ck_agent_data_table_profile_tags CHECK (jsonb_typeof(tags_json) = 'array'),
    CONSTRAINT ck_agent_data_table_profile_temporary
        CHECK (temporary_classification IN ('business', 'temporary', 'backup', 'staging', 'system')),
    CONSTRAINT ck_agent_data_table_profile_ignore_decision
        CHECK (ignore_decision IN ('auto_include', 'auto_ignore', 'manual_include', 'manual_ignore')),
    CONSTRAINT ck_agent_data_table_profile_profile_json CHECK (jsonb_typeof(profile_json) = 'object'),
    CONSTRAINT ck_agent_data_table_profile_revision CHECK (revision_no > 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_data_table_profile_latest
    ON agent_data_table_profile (dataset_id, table_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_agent_data_table_profile_filter
    ON agent_data_table_profile (dataset_id, ignored, temporary_classification, confidence_score DESC);

CREATE INDEX IF NOT EXISTS idx_agent_data_table_profile_tags
    ON agent_data_table_profile USING GIN (tags_json);

CREATE TABLE IF NOT EXISTS agent_data_profile_relation_recommendation (
    id                      BIGINT PRIMARY KEY,
    dataset_id              BIGINT NOT NULL,
    profile_job_id          BIGINT NOT NULL,
    source_table_id         BIGINT NOT NULL,
    source_column_id        BIGINT NOT NULL,
    target_table_id         BIGINT NOT NULL,
    target_column_id        BIGINT NOT NULL,
    confidence_score        NUMERIC(5, 2) NOT NULL,
    join_type               VARCHAR(16) NOT NULL DEFAULT 'left',
    join_condition          TEXT NOT NULL,
    reason                  VARCHAR(1000) NOT NULL,
    status                  VARCHAR(16) NOT NULL DEFAULT 'pending',
    applied_relation_id     BIGINT,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP,
    CONSTRAINT uk_agent_data_profile_relation_recommendation
        UNIQUE (profile_job_id, source_column_id, target_table_id, target_column_id),
    CONSTRAINT ck_agent_data_profile_relation_confidence CHECK (confidence_score BETWEEN 0 AND 100),
    CONSTRAINT ck_agent_data_profile_relation_join CHECK (join_type IN ('inner', 'left')),
    CONSTRAINT ck_agent_data_profile_relation_status
        CHECK (status IN ('pending', 'applied', 'dismissed')),
    CONSTRAINT ck_agent_data_profile_relation_tables CHECK (source_table_id <> target_table_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_data_profile_relation_source
    ON agent_data_profile_relation_recommendation
        (dataset_id, source_table_id, status, confidence_score DESC, id);

CREATE TABLE IF NOT EXISTS agent_data_smart_import_preview (
    id                  BIGINT PRIMARY KEY,
    dataset_id          BIGINT NOT NULL,
    profile_job_id      BIGINT NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'draft',
    dataset_revision    INTEGER NOT NULL,
    revision_no         INTEGER NOT NULL DEFAULT 1,
    expires_at          TIMESTAMP NOT NULL,
    created_by          BIGINT NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_by          BIGINT,
    applied_at          TIMESTAMP,
    CONSTRAINT ck_agent_data_smart_import_preview_status
        CHECK (status IN ('draft', 'applied', 'expired')),
    CONSTRAINT ck_agent_data_smart_import_preview_revision
        CHECK (dataset_revision > 0 AND revision_no > 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_data_smart_import_preview_dataset
    ON agent_data_smart_import_preview (dataset_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS agent_data_smart_import_item (
    id                      BIGINT PRIMARY KEY,
    preview_id              BIGINT NOT NULL,
    item_type               VARCHAR(24) NOT NULL,
    resource_id             BIGINT NOT NULL,
    content_hash            CHAR(64) NOT NULL,
    proposed_json           JSONB NOT NULL,
    status                  VARCHAR(16) NOT NULL DEFAULT 'available',
    applied_resource_id     BIGINT,
    error_message           VARCHAR(1000),
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP,
    CONSTRAINT uk_agent_data_smart_import_item
        UNIQUE (preview_id, item_type, resource_id),
    CONSTRAINT ck_agent_data_smart_import_item_type
        CHECK (item_type IN ('table', 'relationship')),
    CONSTRAINT ck_agent_data_smart_import_item_json CHECK (jsonb_typeof(proposed_json) = 'object'),
    CONSTRAINT ck_agent_data_smart_import_item_status
        CHECK (status IN ('available', 'applied', 'skipped'))
);

CREATE INDEX IF NOT EXISTS idx_agent_data_smart_import_item_preview
    ON agent_data_smart_import_item (preview_id, status, item_type, id);

ALTER TABLE agent_data_metadata_change
    DROP CONSTRAINT IF EXISTS ck_agent_data_metadata_change_resource;

ALTER TABLE agent_data_metadata_change
    ADD CONSTRAINT ck_agent_data_metadata_change_resource
        CHECK (resource_type IN (
            'metric', 'relationship', 'row_policy', 'table', 'column',
            'profile_job', 'table_profile', 'smart_import'
        ));

COMMENT ON COLUMN agent_data_metadata_change.resource_type IS
    '资源类型：指标、关系、行策略、表、字段、画像任务、表画像或智能导入';

COMMENT ON TABLE agent_data_profile_job IS '数据集元数据画像主任务';
COMMENT ON COLUMN agent_data_profile_job.id IS '画像任务ID';
COMMENT ON COLUMN agent_data_profile_job.dataset_id IS '数据集ID';
COMMENT ON COLUMN agent_data_profile_job.data_source_id IS '数据源ID，用于冻结并复核来源配置';
COMMENT ON COLUMN agent_data_profile_job.mode IS '执行模式：全量或增量';
COMMENT ON COLUMN agent_data_profile_job.status IS '任务状态：排队、运行、成功、失败或取消';
COMMENT ON COLUMN agent_data_profile_job.requested_table_ids_json IS '任务创建时实际冻结并排队的表ID列表';
COMMENT ON COLUMN agent_data_profile_job.dataset_revision IS '任务创建时冻结的数据集版本';
COMMENT ON COLUMN agent_data_profile_job.data_source_revision IS '任务创建时冻结的数据源版本';
COMMENT ON COLUMN agent_data_profile_job.total_tables IS '本任务需要执行的表总数';
COMMENT ON COLUMN agent_data_profile_job.completed_tables IS '执行成功的表数量';
COMMENT ON COLUMN agent_data_profile_job.failed_tables IS '执行失败的表数量';
COMMENT ON COLUMN agent_data_profile_job.progress_percent IS '按表级事实归并的完成百分比';
COMMENT ON COLUMN agent_data_profile_job.current_table_id IS '当前执行的数据表ID';
COMMENT ON COLUMN agent_data_profile_job.cancel_requested IS '是否已请求在安全检查点取消';
COMMENT ON COLUMN agent_data_profile_job.resume_of_job_id IS '本任务所续跑的原任务ID';
COMMENT ON COLUMN agent_data_profile_job.worker_id IS '当前持有租约的工作节点ID';
COMMENT ON COLUMN agent_data_profile_job.lease_until IS '工作节点租约截止时间';
COMMENT ON COLUMN agent_data_profile_job.attempt_no IS '主任务被工作节点领取的次数';
COMMENT ON COLUMN agent_data_profile_job.max_attempts IS '主任务最大领取次数';
COMMENT ON COLUMN agent_data_profile_job.revision_no IS '任务乐观锁版本号';
COMMENT ON COLUMN agent_data_profile_job.error_message IS '失败或取消原因的脱敏摘要';
COMMENT ON COLUMN agent_data_profile_job.requested_by IS '发起用户ID，也是后台执行审计身份';
COMMENT ON COLUMN agent_data_profile_job.created_at IS '任务创建时间';
COMMENT ON COLUMN agent_data_profile_job.started_at IS '首次开始执行时间';
COMMENT ON COLUMN agent_data_profile_job.finished_at IS '进入终态的时间';
COMMENT ON COLUMN agent_data_profile_job.updated_at IS '任务最后状态更新时间';

COMMENT ON TABLE agent_data_profile_job_table IS '元数据画像任务的表级执行事实';
COMMENT ON COLUMN agent_data_profile_job_table.id IS '表级执行项ID';
COMMENT ON COLUMN agent_data_profile_job_table.job_id IS '画像主任务ID';
COMMENT ON COLUMN agent_data_profile_job_table.dataset_id IS '数据集ID';
COMMENT ON COLUMN agent_data_profile_job_table.table_id IS '数据表ID';
COMMENT ON COLUMN agent_data_profile_job_table.sequence_no IS '任务内稳定执行顺序';
COMMENT ON COLUMN agent_data_profile_job_table.source_hash IS '任务创建时表及字段结构SHA-256';
COMMENT ON COLUMN agent_data_profile_job_table.status IS '表级状态：待执行、运行、成功或失败';
COMMENT ON COLUMN agent_data_profile_job_table.attempt_no IS '该表画像尝试次数';
COMMENT ON COLUMN agent_data_profile_job_table.profile_id IS '成功生成的表画像ID';
COMMENT ON COLUMN agent_data_profile_job_table.error_message IS '表级失败的脱敏摘要';
COMMENT ON COLUMN agent_data_profile_job_table.started_at IS '本次开始执行时间';
COMMENT ON COLUMN agent_data_profile_job_table.finished_at IS '本次执行结束时间';
COMMENT ON COLUMN agent_data_profile_job_table.updated_at IS '表级状态最后更新时间';

COMMENT ON TABLE agent_data_table_profile IS '数据表画像的不可变版本事实';
COMMENT ON COLUMN agent_data_table_profile.id IS '表画像ID';
COMMENT ON COLUMN agent_data_table_profile.dataset_id IS '数据集ID';
COMMENT ON COLUMN agent_data_table_profile.table_id IS '数据表ID';
COMMENT ON COLUMN agent_data_table_profile.job_id IS '生成本画像的任务ID';
COMMENT ON COLUMN agent_data_table_profile.source_hash IS '生成画像时表及字段结构SHA-256';
COMMENT ON COLUMN agent_data_table_profile.table_type IS '物理对象类型';
COMMENT ON COLUMN agent_data_table_profile.term IS '模型识别的表业务术语';
COMMENT ON COLUMN agent_data_table_profile.description IS '模型生成的表业务用途描述';
COMMENT ON COLUMN agent_data_table_profile.ddl_text IS '由受治理元数据生成的有界DDL快照';
COMMENT ON COLUMN agent_data_table_profile.row_count_estimate IS '可选的估算行数，不执行无界全表计数';
COMMENT ON COLUMN agent_data_table_profile.column_count IS '画像字段数量';
COMMENT ON COLUMN agent_data_table_profile.columns_profile_json IS '字段画像、样本统计和敏感标记';
COMMENT ON COLUMN agent_data_table_profile.sample_data_json IS '最多3行且已脱敏、截断的样例数据';
COMMENT ON COLUMN agent_data_table_profile.sample_row_count IS '实际保存的样例行数';
COMMENT ON COLUMN agent_data_table_profile.sample_redacted IS '样例中是否发生敏感值遮蔽';
COMMENT ON COLUMN agent_data_table_profile.confidence_score IS '画像业务相关度与完整度评分，0到100';
COMMENT ON COLUMN agent_data_table_profile.confidence_reason IS '评分与降分原因';
COMMENT ON COLUMN agent_data_table_profile.tags_json IS '画像生成的可筛选分类标签数组';
COMMENT ON COLUMN agent_data_table_profile.temporary_classification IS '表分类：业务、临时、备份、暂存或系统';
COMMENT ON COLUMN agent_data_table_profile.ignored IS '智能导入和关联推荐是否忽略该表';
COMMENT ON COLUMN agent_data_table_profile.ignore_decision IS '自动或人工包含/忽略决定';
COMMENT ON COLUMN agent_data_table_profile.profile_json IS '画像算法、界限和其他结构化事实';
COMMENT ON COLUMN agent_data_table_profile.revision_no IS '画像人工决定乐观锁版本号';
COMMENT ON COLUMN agent_data_table_profile.created_by IS '画像任务发起用户ID';
COMMENT ON COLUMN agent_data_table_profile.created_at IS '画像生成时间';
COMMENT ON COLUMN agent_data_table_profile.updated_by IS '最后修改忽略决定的用户ID';
COMMENT ON COLUMN agent_data_table_profile.updated_at IS '最后修改忽略决定的时间';

COMMENT ON TABLE agent_data_profile_relation_recommendation IS '基于表画像推断的候选表关系';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.id IS '关联建议ID';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.dataset_id IS '数据集ID';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.profile_job_id IS '生成建议的画像任务ID';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.source_table_id IS '来源表ID';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.source_column_id IS '来源字段ID';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.target_table_id IS '目标表ID';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.target_column_id IS '目标字段ID';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.confidence_score IS '关系建议置信度，0到100';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.join_type IS '建议连接类型';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.join_condition IS '使用受治理标识符生成的等值连接条件';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.reason IS '关系推断依据';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.status IS '建议状态：待处理、已应用或已忽略';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.applied_relation_id IS '应用后生成的数据关系ID';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.created_at IS '建议生成时间';
COMMENT ON COLUMN agent_data_profile_relation_recommendation.updated_at IS '建议最后更新时间';

COMMENT ON TABLE agent_data_smart_import_preview IS '基于画像生成的可选择智能导入预览';
COMMENT ON COLUMN agent_data_smart_import_preview.id IS '导入预览ID';
COMMENT ON COLUMN agent_data_smart_import_preview.dataset_id IS '数据集ID';
COMMENT ON COLUMN agent_data_smart_import_preview.profile_job_id IS '预览采用的画像任务ID';
COMMENT ON COLUMN agent_data_smart_import_preview.status IS '预览状态：草稿、已应用或已过期';
COMMENT ON COLUMN agent_data_smart_import_preview.dataset_revision IS '生成预览时冻结的数据集版本';
COMMENT ON COLUMN agent_data_smart_import_preview.revision_no IS '预览乐观锁版本号';
COMMENT ON COLUMN agent_data_smart_import_preview.expires_at IS '预览过期时间';
COMMENT ON COLUMN agent_data_smart_import_preview.created_by IS '预览创建用户ID';
COMMENT ON COLUMN agent_data_smart_import_preview.created_at IS '预览创建时间';
COMMENT ON COLUMN agent_data_smart_import_preview.applied_by IS '执行选择性导入的用户ID';
COMMENT ON COLUMN agent_data_smart_import_preview.applied_at IS '选择性导入完成时间';

COMMENT ON TABLE agent_data_smart_import_item IS '智能导入预览中的原子候选项';
COMMENT ON COLUMN agent_data_smart_import_item.id IS '预览项ID';
COMMENT ON COLUMN agent_data_smart_import_item.preview_id IS '导入预览ID';
COMMENT ON COLUMN agent_data_smart_import_item.item_type IS '预览项类型：表增强或关系创建';
COMMENT ON COLUMN agent_data_smart_import_item.resource_id IS '来源画像或关系建议ID';
COMMENT ON COLUMN agent_data_smart_import_item.content_hash IS '建议内容SHA-256，用于防篡改';
COMMENT ON COLUMN agent_data_smart_import_item.proposed_json IS '待应用的结构化变更建议';
COMMENT ON COLUMN agent_data_smart_import_item.status IS '预览项状态：可用、已应用或已跳过';
COMMENT ON COLUMN agent_data_smart_import_item.applied_resource_id IS '应用后对应的平台资源ID';
COMMENT ON COLUMN agent_data_smart_import_item.error_message IS '预览项失败摘要，原子应用失败时事务回滚';
COMMENT ON COLUMN agent_data_smart_import_item.created_at IS '预览项创建时间';
COMMENT ON COLUMN agent_data_smart_import_item.updated_at IS '预览项最后更新时间';

COMMIT;
