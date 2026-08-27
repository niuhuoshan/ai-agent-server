-- Knowledge document upload, durable parsing and lexical retrieval control plane.

ALTER TABLE agent_knowledge_base
    ADD COLUMN IF NOT EXISTS revision_no BIGINT NOT NULL DEFAULT 1;

UPDATE agent_knowledge_base SET config_json = '{}'::jsonb WHERE config_json IS NULL;
UPDATE agent_knowledge_base SET extra_json = '{}'::jsonb WHERE extra_json IS NULL;
ALTER TABLE agent_knowledge_base ALTER COLUMN config_json SET DEFAULT '{}'::jsonb;
ALTER TABLE agent_knowledge_base ALTER COLUMN config_json SET NOT NULL;
ALTER TABLE agent_knowledge_base ALTER COLUMN extra_json SET DEFAULT '{}'::jsonb;
ALTER TABLE agent_knowledge_base ALTER COLUMN extra_json SET NOT NULL;

ALTER TABLE agent_knowledge_document
    ADD COLUMN IF NOT EXISTS storage_type VARCHAR(16) NOT NULL DEFAULT 'local',
    ADD COLUMN IF NOT EXISTS storage_ref VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS mime_type VARCHAR(128),
    ADD COLUMN IF NOT EXISTS size_bytes BIGINT,
    ADD COLUMN IF NOT EXISTS revision_no BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS parse_started_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS processed_at TIMESTAMP;

UPDATE agent_knowledge_document SET metadata_json = '{}'::jsonb WHERE metadata_json IS NULL;
ALTER TABLE agent_knowledge_document ALTER COLUMN metadata_json SET DEFAULT '{}'::jsonb;
ALTER TABLE agent_knowledge_document ALTER COLUMN metadata_json SET NOT NULL;

ALTER TABLE agent_knowledge_document DROP CONSTRAINT IF EXISTS ck_agent_knowledge_document_storage;
ALTER TABLE agent_knowledge_document ADD CONSTRAINT ck_agent_knowledge_document_storage
    CHECK (storage_type IN ('local', 'oss', 'external'));
ALTER TABLE agent_knowledge_document DROP CONSTRAINT IF EXISTS ck_agent_knowledge_document_size;
ALTER TABLE agent_knowledge_document ADD CONSTRAINT ck_agent_knowledge_document_size
    CHECK (size_bytes IS NULL OR size_bytes >= 0);
ALTER TABLE agent_knowledge_document DROP CONSTRAINT IF EXISTS ck_agent_knowledge_document_revision;
ALTER TABLE agent_knowledge_document ADD CONSTRAINT ck_agent_knowledge_document_revision
    CHECK (revision_no > 0);

ALTER TABLE agent_knowledge_chunk
    ADD COLUMN IF NOT EXISTS search_vector TSVECTOR
        GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED;

CREATE INDEX IF NOT EXISTS idx_agent_knowledge_chunk_lexical
    ON agent_knowledge_chunk USING GIN (search_vector)
    WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_agent_knowledge_chunk_embedding
    ON agent_knowledge_chunk (knowledge_base_id, embedding_model_id, embedding_dimension)
    WHERE status = 'active' AND embedding IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_knowledge_parse_jobs
    ON agent_job_queue (status, available_at, priority DESC, created_at)
    WHERE job_type = 'knowledge_parse';
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_knowledge_document_content_active
    ON agent_knowledge_document (knowledge_base_id, content_hash)
    WHERE del_flag = '0' AND status <> 'deleted' AND content_hash IS NOT NULL;

COMMENT ON COLUMN agent_knowledge_base.revision_no IS '乐观并发修订号';
COMMENT ON COLUMN agent_knowledge_document.storage_type IS '原始文档存储类型';
COMMENT ON COLUMN agent_knowledge_document.storage_ref IS '原始文档受控存储引用';
COMMENT ON COLUMN agent_knowledge_document.mime_type IS '文档MIME类型';
COMMENT ON COLUMN agent_knowledge_document.size_bytes IS '文档字节数';
COMMENT ON COLUMN agent_knowledge_document.revision_no IS '解析内容修订号';
COMMENT ON COLUMN agent_knowledge_document.parse_started_at IS '最近解析开始时间';
COMMENT ON COLUMN agent_knowledge_document.processed_at IS '最近解析完成时间';
COMMENT ON COLUMN agent_knowledge_chunk.search_vector IS '全文检索向量';
