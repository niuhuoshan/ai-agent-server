-- ChatBI 跨数据集联邦查询运行、数据源步骤和最终结果血缘。

BEGIN;

CREATE TABLE IF NOT EXISTS agent_chatbi_federated_run (
    id                  BIGINT PRIMARY KEY,
    run_key             VARCHAR(64) NOT NULL,
    owner_id            BIGINT NOT NULL,
    conversation_id     BIGINT,
    primary_dataset_id  BIGINT NOT NULL,
    result_query_id     BIGINT,
    request_question    TEXT NOT NULL,
    dataset_ids_json    JSONB NOT NULL,
    plan_json           JSONB,
    join_sql            TEXT,
    status              VARCHAR(24) NOT NULL DEFAULT 'planning',
    source_count        INTEGER NOT NULL,
    row_count           INTEGER,
    result_bytes        INTEGER,
    result_truncated    BOOLEAN NOT NULL DEFAULT FALSE,
    error_summary       TEXT,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_chatbi_federated_run_key UNIQUE (run_key),
    CONSTRAINT uk_agent_chatbi_federated_result UNIQUE (result_query_id),
    CONSTRAINT ck_agent_chatbi_federated_run_status CHECK (
        status IN ('planning', 'running', 'clarification_required', 'succeeded', 'failed', 'cancelled')
    ),
    CONSTRAINT ck_agent_chatbi_federated_source_count CHECK (source_count BETWEEN 2 AND 5),
    CONSTRAINT ck_agent_chatbi_federated_result_size CHECK (
        (row_count IS NULL OR row_count BETWEEN 0 AND 1000)
        AND (result_bytes IS NULL OR result_bytes BETWEEN 1 AND 5242880)
    )
);

CREATE TABLE IF NOT EXISTS agent_chatbi_federated_source (
    id                  BIGINT PRIMARY KEY,
    run_id              BIGINT NOT NULL,
    sequence_no         INTEGER NOT NULL,
    dataset_id          BIGINT NOT NULL,
    temp_table          VARCHAR(64) NOT NULL,
    trace_id            VARCHAR(64),
    planned_sql         TEXT NOT NULL,
    effective_sql       TEXT,
    query_id            BIGINT,
    status              VARCHAR(24) NOT NULL DEFAULT 'pending',
    row_count           INTEGER,
    result_truncated    BOOLEAN NOT NULL DEFAULT FALSE,
    repair_count        INTEGER NOT NULL DEFAULT 0,
    error_summary       TEXT,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_chatbi_federated_source_seq UNIQUE (run_id, sequence_no),
    CONSTRAINT uk_agent_chatbi_federated_source_dataset UNIQUE (run_id, dataset_id),
    CONSTRAINT uk_agent_chatbi_federated_source_temp UNIQUE (run_id, temp_table),
    CONSTRAINT ck_agent_chatbi_federated_source_status CHECK (
        status IN ('pending', 'running', 'succeeded', 'failed', 'skipped')
    ),
    CONSTRAINT ck_agent_chatbi_federated_source_seq CHECK (sequence_no BETWEEN 1 AND 5),
    CONSTRAINT ck_agent_chatbi_federated_source_rows CHECK (row_count IS NULL OR row_count BETWEEN 0 AND 10000),
    CONSTRAINT ck_agent_chatbi_federated_source_repairs CHECK (repair_count BETWEEN 0 AND 20)
);

CREATE INDEX IF NOT EXISTS idx_agent_chatbi_federated_owner
    ON agent_chatbi_federated_run (owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_chatbi_federated_conversation
    ON agent_chatbi_federated_run (conversation_id, created_at DESC)
    WHERE conversation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_chatbi_federated_source_dataset
    ON agent_chatbi_federated_source (dataset_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_chatbi_federated_source_query
    ON agent_chatbi_federated_source (query_id)
    WHERE query_id IS NOT NULL;

COMMENT ON TABLE agent_chatbi_federated_run IS 'ChatBI跨数据集联邦查询运行事实';
COMMENT ON COLUMN agent_chatbi_federated_run.id IS '联邦运行主键';
COMMENT ON COLUMN agent_chatbi_federated_run.run_key IS '对外稳定联邦运行标识';
COMMENT ON COLUMN agent_chatbi_federated_run.owner_id IS '发起用户ID';
COMMENT ON COLUMN agent_chatbi_federated_run.conversation_id IS '所属私人会话ID';
COMMENT ON COLUMN agent_chatbi_federated_run.primary_dataset_id IS '用于历史和结果栈归属的主数据集ID';
COMMENT ON COLUMN agent_chatbi_federated_run.result_query_id IS '最终内存关联结果对应的查询快照ID';
COMMENT ON COLUMN agent_chatbi_federated_run.request_question IS '用户原始联邦分析问题';
COMMENT ON COLUMN agent_chatbi_federated_run.dataset_ids_json IS '用户选定且执行前完成权限复核的数据集ID列表';
COMMENT ON COLUMN agent_chatbi_federated_run.plan_json IS '模型生成并经服务端校验的联邦执行计划';
COMMENT ON COLUMN agent_chatbi_federated_run.join_sql IS '在受限内存数据库执行的最终只读关联SQL';
COMMENT ON COLUMN agent_chatbi_federated_run.status IS '联邦运行状态';
COMMENT ON COLUMN agent_chatbi_federated_run.source_count IS '联邦数据集数量';
COMMENT ON COLUMN agent_chatbi_federated_run.row_count IS '最终关联结果行数';
COMMENT ON COLUMN agent_chatbi_federated_run.result_bytes IS '最终关联结果序列化字节数';
COMMENT ON COLUMN agent_chatbi_federated_run.result_truncated IS '最终关联结果是否被上限截断';
COMMENT ON COLUMN agent_chatbi_federated_run.error_summary IS '脱敏失败摘要';
COMMENT ON COLUMN agent_chatbi_federated_run.started_at IS '开始执行时间';
COMMENT ON COLUMN agent_chatbi_federated_run.finished_at IS '终态时间';
COMMENT ON COLUMN agent_chatbi_federated_run.created_at IS '创建时间';

COMMENT ON TABLE agent_chatbi_federated_source IS 'ChatBI联邦查询中每个数据集的受治理子查询步骤';
COMMENT ON COLUMN agent_chatbi_federated_source.id IS '联邦数据源步骤主键';
COMMENT ON COLUMN agent_chatbi_federated_source.run_id IS '所属联邦运行主键';
COMMENT ON COLUMN agent_chatbi_federated_source.sequence_no IS '数据源执行顺序';
COMMENT ON COLUMN agent_chatbi_federated_source.dataset_id IS '子查询所属数据集ID';
COMMENT ON COLUMN agent_chatbi_federated_source.temp_table IS '内存数据库临时表名';
COMMENT ON COLUMN agent_chatbi_federated_source.trace_id IS '子查询治理链路标识';
COMMENT ON COLUMN agent_chatbi_federated_source.planned_sql IS '模型规划的只读子查询SQL';
COMMENT ON COLUMN agent_chatbi_federated_source.effective_sql IS '经过自动修复后实际执行的SQL';
COMMENT ON COLUMN agent_chatbi_federated_source.query_id IS '受治理子查询事实ID';
COMMENT ON COLUMN agent_chatbi_federated_source.status IS '子查询步骤状态';
COMMENT ON COLUMN agent_chatbi_federated_source.row_count IS '子查询结果行数';
COMMENT ON COLUMN agent_chatbi_federated_source.result_truncated IS '子查询结果是否被截断';
COMMENT ON COLUMN agent_chatbi_federated_source.repair_count IS 'SQL自动修复次数';
COMMENT ON COLUMN agent_chatbi_federated_source.error_summary IS '脱敏失败摘要';
COMMENT ON COLUMN agent_chatbi_federated_source.started_at IS '开始执行时间';
COMMENT ON COLUMN agent_chatbi_federated_source.finished_at IS '终态时间';
COMMENT ON COLUMN agent_chatbi_federated_source.created_at IS '创建时间';

COMMIT;
