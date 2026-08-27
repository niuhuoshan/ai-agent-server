-- agent platform schema V77: durable ChatBI result lineage, evidence and presentation state

BEGIN;

CREATE TABLE IF NOT EXISTS agent_chatbi_result_context (
    query_id               BIGINT PRIMARY KEY,
    owner_id               BIGINT NOT NULL,
    conversation_id        BIGINT NOT NULL,
    parent_query_id        BIGINT,
    analysis_context_json  JSONB NOT NULL DEFAULT '{}'::jsonb,
    chart_config_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    pivot_config_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    revision_no            INTEGER NOT NULL DEFAULT 1,
    created_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP,
    CONSTRAINT ck_agent_chatbi_result_context_revision CHECK (revision_no > 0),
    CONSTRAINT ck_agent_chatbi_result_context_parent CHECK (parent_query_id IS NULL OR parent_query_id <> query_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_chatbi_result_context_stack
    ON agent_chatbi_result_context (owner_id, conversation_id, created_at DESC, query_id DESC);

CREATE INDEX IF NOT EXISTS idx_agent_chatbi_result_context_parent
    ON agent_chatbi_result_context (parent_query_id)
    WHERE parent_query_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS agent_chatbi_evidence (
    id                       BIGINT PRIMARY KEY,
    query_id                 BIGINT NOT NULL UNIQUE,
    owner_id                 BIGINT NOT NULL,
    conversation_id          BIGINT NOT NULL,
    trace_id                 VARCHAR(64) NOT NULL,
    dataset_id               BIGINT NOT NULL,
    evidence_type            VARCHAR(32) NOT NULL DEFAULT 'internal_data',
    producer                 VARCHAR(64) NOT NULL DEFAULT 'chatbi_query',
    payload_digest           CHAR(64) NOT NULL,
    result_hash              CHAR(64) NOT NULL,
    source_ref               VARCHAR(512) NOT NULL,
    result_status            VARCHAR(32) NOT NULL,
    freshness                VARCHAR(24) NOT NULL DEFAULT 'dynamic',
    observed_at              TIMESTAMP NOT NULL,
    source_as_of             TIMESTAMP,
    expires_at               TIMESTAMP,
    permission_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    detail_json              JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_chatbi_evidence_type CHECK (
        evidence_type IN ('internal_data', 'internal_knowledge', 'public_web', 'runtime_state',
                          'user_file', 'conversation_memory', 'external_tool')
    ),
    CONSTRAINT ck_agent_chatbi_evidence_status CHECK (
        result_status IN ('success_non_empty', 'success_empty')
    ),
    CONSTRAINT ck_agent_chatbi_evidence_freshness CHECK (
        freshness IN ('unknown', 'static', 'dynamic', 'realtime', 'reuse_previous')
    ),
    CONSTRAINT ck_agent_chatbi_evidence_payload_digest CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_chatbi_evidence_result_hash CHECK (result_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX IF NOT EXISTS idx_agent_chatbi_evidence_scope
    ON agent_chatbi_evidence (owner_id, conversation_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_agent_chatbi_evidence_trace
    ON agent_chatbi_evidence (trace_id, owner_id);

-- Existing successful ChatBI snapshots become first-class results after upgrade.
INSERT INTO agent_chatbi_result_context (
    query_id, owner_id, conversation_id, analysis_context_json,
    chart_config_json, pivot_config_json, revision_no, created_at
)
SELECT q.id, q.created_by, q.conversation_id, '{}'::jsonb,
       '{}'::jsonb, '{}'::jsonb, 1, COALESCE(q.finished_at, q.created_at, CURRENT_TIMESTAMP)
FROM agent_data_query q
INNER JOIN agent_data_query_result r ON r.query_id = q.id AND r.created_by = q.created_by
WHERE q.trace_id LIKE 'chatbi:%'
  AND q.status = 'succeeded'
  AND q.created_by IS NOT NULL
  AND q.conversation_id IS NOT NULL
ON CONFLICT (query_id) DO NOTHING;

INSERT INTO agent_chatbi_evidence (
    id, query_id, owner_id, conversation_id, trace_id, dataset_id,
    evidence_type, producer, payload_digest, result_hash, source_ref,
    result_status, freshness, observed_at, permission_snapshot_json,
    detail_json, created_at
)
SELECT q.id, q.id, q.created_by, q.conversation_id, q.trace_id, q.dataset_id,
       'internal_data', 'chatbi_query', r.content_hash, r.content_hash,
       'dataset://' || q.dataset_id || '/query/' || q.id,
       CASE WHEN COALESCE(q.row_count, 0) > 0 THEN 'success_non_empty' ELSE 'success_empty' END,
       'dynamic', COALESCE(q.finished_at, q.created_at, CURRENT_TIMESTAMP),
       COALESCE(q.permission_summary_json, '{}'::jsonb),
       jsonb_build_object(
           'rowCount', COALESCE(q.row_count, 0),
           'resultBytes', COALESCE(q.result_bytes, 0),
           'truncated', COALESCE(q.result_truncated, FALSE),
           'sqlHash', q.sql_hash
       ),
       COALESCE(q.finished_at, q.created_at, CURRENT_TIMESTAMP)
FROM agent_data_query q
INNER JOIN agent_data_query_result r ON r.query_id = q.id AND r.created_by = q.created_by
WHERE q.trace_id LIKE 'chatbi:%'
  AND q.status = 'succeeded'
  AND q.created_by IS NOT NULL
  AND q.conversation_id IS NOT NULL
ON CONFLICT (query_id) DO NOTHING;

COMMENT ON TABLE agent_chatbi_result_context IS 'ChatBI 连续分析结果栈、父子血缘和用户展示配置';
COMMENT ON COLUMN agent_chatbi_result_context.query_id IS '关联的只读数据查询 ID，也是结果栈中的稳定结果 ID';
COMMENT ON COLUMN agent_chatbi_result_context.owner_id IS '结果所有者用户 ID，结果栈按用户隔离';
COMMENT ON COLUMN agent_chatbi_result_context.conversation_id IS '所属私有 ChatBI 会话 ID';
COMMENT ON COLUMN agent_chatbi_result_context.parent_query_id IS '连续分析或下钻所引用的父结果查询 ID';
COMMENT ON COLUMN agent_chatbi_result_context.analysis_context_json IS '指标、维度、筛选、时间范围和分析方法等可继承语义';
COMMENT ON COLUMN agent_chatbi_result_context.chart_config_json IS '服务端校验后的图表配置';
COMMENT ON COLUMN agent_chatbi_result_context.pivot_config_json IS '服务端校验后的透视表配置';
COMMENT ON COLUMN agent_chatbi_result_context.revision_no IS '展示配置乐观锁版本号';
COMMENT ON COLUMN agent_chatbi_result_context.created_at IS '结果上下文创建时间';
COMMENT ON COLUMN agent_chatbi_result_context.updated_at IS '展示配置最近更新时间';

COMMENT ON TABLE agent_chatbi_evidence IS 'ChatBI 成功查询签发的持久数据证据收据；失败、拒绝和不可用查询不得写入';
COMMENT ON COLUMN agent_chatbi_evidence.id IS '证据收据 ID';
COMMENT ON COLUMN agent_chatbi_evidence.query_id IS '唯一关联的 ChatBI 查询结果 ID';
COMMENT ON COLUMN agent_chatbi_evidence.owner_id IS '证据所有者用户 ID';
COMMENT ON COLUMN agent_chatbi_evidence.conversation_id IS '证据所属私有会话 ID';
COMMENT ON COLUMN agent_chatbi_evidence.trace_id IS '查询全链路追踪标识';
COMMENT ON COLUMN agent_chatbi_evidence.dataset_id IS '证据来源数据集 ID';
COMMENT ON COLUMN agent_chatbi_evidence.evidence_type IS '证据类型，ChatBI 查询固定为 internal_data';
COMMENT ON COLUMN agent_chatbi_evidence.producer IS '证据生产者标识';
COMMENT ON COLUMN agent_chatbi_evidence.payload_digest IS '证据载荷 SHA-256 摘要';
COMMENT ON COLUMN agent_chatbi_evidence.result_hash IS '不可变查询结果快照 SHA-256 哈希';
COMMENT ON COLUMN agent_chatbi_evidence.source_ref IS '不暴露物理连接信息的逻辑来源定位';
COMMENT ON COLUMN agent_chatbi_evidence.result_status IS '成功非空或成功空结果状态';
COMMENT ON COLUMN agent_chatbi_evidence.freshness IS '事实时效等级';
COMMENT ON COLUMN agent_chatbi_evidence.observed_at IS '平台实际观察到结果的时间';
COMMENT ON COLUMN agent_chatbi_evidence.source_as_of IS '来源数据声明的业务时点';
COMMENT ON COLUMN agent_chatbi_evidence.expires_at IS '证据过期时间，为空表示按数据集策略动态复核';
COMMENT ON COLUMN agent_chatbi_evidence.permission_snapshot_json IS '查询执行时的权限决策快照';
COMMENT ON COLUMN agent_chatbi_evidence.detail_json IS '行数、截断、SQL 哈希等非敏感取证明细';
COMMENT ON COLUMN agent_chatbi_evidence.created_at IS '证据收据签发时间';

COMMIT;
