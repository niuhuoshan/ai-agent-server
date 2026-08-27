-- agent platform schema V50: knowledge retrieval metrics (query text is never persisted)

BEGIN;

CREATE TABLE IF NOT EXISTS agent_knowledge_retrieval_event (
    id                    BIGINT PRIMARY KEY,
    user_id               BIGINT NOT NULL,
    conversation_id       BIGINT,
    query_hash            CHAR(64) NOT NULL,
    query_length          INTEGER NOT NULL,
    knowledge_base_ids    JSONB NOT NULL DEFAULT '[]'::jsonb,
    status                VARCHAR(16) NOT NULL,
    citation_count        INTEGER NOT NULL DEFAULT 0,
    citation_document_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    latency_ms            INTEGER NOT NULL DEFAULT 0,
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_knowledge_retrieval_status
        CHECK (status IN ('ok', 'empty', 'failed')),
    CONSTRAINT ck_agent_knowledge_retrieval_query_length
        CHECK (query_length >= 0 AND query_length <= 4000),
    CONSTRAINT ck_agent_knowledge_retrieval_citations
        CHECK (citation_count >= 0),
    CONSTRAINT ck_agent_knowledge_retrieval_base_ids
        CHECK (jsonb_typeof(knowledge_base_ids) = 'array'),
    CONSTRAINT ck_agent_knowledge_retrieval_document_ids
        CHECK (jsonb_typeof(citation_document_ids) = 'array')
);

CREATE INDEX IF NOT EXISTS idx_agent_knowledge_retrieval_user_time
    ON agent_knowledge_retrieval_event (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_knowledge_retrieval_time
    ON agent_knowledge_retrieval_event (created_at DESC);

COMMENT ON TABLE agent_knowledge_retrieval_event IS '知识检索运营事实表；不保存原始问题，仅保存哈希、引用和结果状态';
COMMENT ON COLUMN agent_knowledge_retrieval_event.query_hash IS '用户问题 SHA-256，避免指标泄露原始问题';
COMMENT ON COLUMN agent_knowledge_retrieval_event.knowledge_base_ids IS '本次检索使用的知识库 ID JSON 数组';
COMMENT ON COLUMN agent_knowledge_retrieval_event.citation_document_ids IS '返回引用的文档 ID JSON 数组';

COMMIT;
