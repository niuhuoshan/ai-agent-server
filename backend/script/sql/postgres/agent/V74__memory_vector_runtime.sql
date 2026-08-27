-- Nhs-compatible memory embedding, pgvector retrieval and maintenance configuration.

CREATE TABLE IF NOT EXISTS agent_memory_runtime_config (
    id                         SMALLINT PRIMARY KEY DEFAULT 1,
    enabled                    BOOLEAN NOT NULL DEFAULT TRUE,
    summary_enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    embedding_model_id         BIGINT,
    embedding_dimension        INTEGER,
    search_knn_top_k           INTEGER NOT NULL DEFAULT 5,
    vector_weight              NUMERIC(5,4) NOT NULL DEFAULT 0.7000,
    consolidation_threshold    NUMERIC(5,4) NOT NULL DEFAULT 0.8200,
    base_half_life_days        NUMERIC(8,2) NOT NULL DEFAULT 7.00,
    summary_ttl_days           INTEGER NOT NULL DEFAULT 30,
    revision_no                BIGINT NOT NULL DEFAULT 1,
    updated_by                 BIGINT,
    updated_at                 TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_memory_runtime_singleton CHECK (id = 1),
    CONSTRAINT ck_agent_memory_runtime_embedding CHECK (
        (embedding_model_id IS NULL AND embedding_dimension IS NULL)
        OR (embedding_model_id IS NOT NULL AND embedding_dimension BETWEEN 1 AND 8192)
    ),
    CONSTRAINT ck_agent_memory_runtime_top_k CHECK (search_knn_top_k BETWEEN 1 AND 200),
    CONSTRAINT ck_agent_memory_runtime_weight CHECK (vector_weight BETWEEN 0 AND 1),
    CONSTRAINT ck_agent_memory_runtime_threshold CHECK (consolidation_threshold BETWEEN 0 AND 1),
    CONSTRAINT ck_agent_memory_runtime_half_life CHECK (base_half_life_days > 0),
    CONSTRAINT ck_agent_memory_runtime_ttl CHECK (summary_ttl_days BETWEEN 1 AND 3650),
    CONSTRAINT ck_agent_memory_runtime_revision CHECK (revision_no > 0)
);

INSERT INTO agent_memory_runtime_config (id)
VALUES (1)
ON CONFLICT (id) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_agent_memory_embedding_owner
    ON agent_memory (scope_type, scope_id, embedding_model_id, embedding_dimension, updated_at DESC)
    WHERE del_flag = '0' AND review_status = 'approved' AND embedding IS NOT NULL;

COMMENT ON TABLE agent_memory_runtime_config IS '记忆向量检索与整理运行配置';
COMMENT ON COLUMN agent_memory_runtime_config.id IS '单例配置标识，固定为1';
COMMENT ON COLUMN agent_memory_runtime_config.enabled IS '是否启用记忆服务';
COMMENT ON COLUMN agent_memory_runtime_config.summary_enabled IS '是否启用会话与每日摘要';
COMMENT ON COLUMN agent_memory_runtime_config.embedding_model_id IS 'Embedding模型ID';
COMMENT ON COLUMN agent_memory_runtime_config.embedding_dimension IS 'Embedding向量维度';
COMMENT ON COLUMN agent_memory_runtime_config.search_knn_top_k IS '默认向量召回数量';
COMMENT ON COLUMN agent_memory_runtime_config.vector_weight IS '混合检索中的向量分数权重';
COMMENT ON COLUMN agent_memory_runtime_config.consolidation_threshold IS '相似记忆合并阈值';
COMMENT ON COLUMN agent_memory_runtime_config.base_half_life_days IS '记忆时间衰减基础半衰期天数';
COMMENT ON COLUMN agent_memory_runtime_config.summary_ttl_days IS '会话摘要默认保留天数';
COMMENT ON COLUMN agent_memory_runtime_config.revision_no IS '配置乐观锁版本号';
COMMENT ON COLUMN agent_memory_runtime_config.updated_by IS '最近修改人';
COMMENT ON COLUMN agent_memory_runtime_config.updated_at IS '最近修改时间';
COMMENT ON INDEX idx_agent_memory_embedding_owner IS '按记忆所有者与模型定位有效向量';
