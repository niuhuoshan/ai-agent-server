-- Governed user, project and task memory with review and reproducible runtime snapshots.

ALTER TABLE agent_memory
    ADD COLUMN IF NOT EXISTS memory_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS content_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS revision_no BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS reviewed_by BIGINT,
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS review_comment VARCHAR(2000),
    ADD COLUMN IF NOT EXISTS del_flag CHAR(1) NOT NULL DEFAULT '0';

UPDATE agent_memory
SET memory_key = 'legacy-' || id
WHERE memory_key IS NULL;

UPDATE agent_memory
SET reviewed_at = COALESCE(reviewed_at, updated_at, created_at, CURRENT_TIMESTAMP)
WHERE review_status IN ('approved', 'rejected', 'expired') AND reviewed_at IS NULL;

UPDATE agent_memory
SET reviewed_by = NULL, reviewed_at = NULL, review_comment = NULL
WHERE review_status = 'pending';

ALTER TABLE agent_memory ALTER COLUMN memory_key SET NOT NULL;

ALTER TABLE agent_memory DROP CONSTRAINT IF EXISTS ck_agent_memory_revision;
ALTER TABLE agent_memory ADD CONSTRAINT ck_agent_memory_revision CHECK (revision_no > 0);
ALTER TABLE agent_memory DROP CONSTRAINT IF EXISTS ck_agent_memory_del_flag;
ALTER TABLE agent_memory ADD CONSTRAINT ck_agent_memory_del_flag CHECK (del_flag IN ('0', '1'));
ALTER TABLE agent_memory DROP CONSTRAINT IF EXISTS ck_agent_memory_review_actor;
ALTER TABLE agent_memory ADD CONSTRAINT ck_agent_memory_review_actor CHECK (
    (review_status = 'pending' AND reviewed_at IS NULL)
    OR (review_status IN ('approved', 'rejected', 'expired') AND reviewed_at IS NOT NULL)
);

ALTER TABLE agent_memory
    ADD COLUMN IF NOT EXISTS search_vector TSVECTOR
        GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_memory_scope_key_active
    ON agent_memory (scope_type, scope_id, memory_key)
    WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_memory_scope_review
    ON agent_memory (scope_type, scope_id, review_status, updated_at DESC, id DESC)
    WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_memory_expiry
    ON agent_memory (expires_at)
    WHERE del_flag = '0' AND review_status = 'approved' AND expires_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_memory_lexical
    ON agent_memory USING GIN (search_vector)
    WHERE del_flag = '0' AND review_status = 'approved';

COMMENT ON COLUMN agent_memory.memory_key IS '作用域内稳定记忆标识';
COMMENT ON COLUMN agent_memory.content_hash IS '记忆正文SHA-256';
COMMENT ON COLUMN agent_memory.metadata_json IS '不含密钥的来源和治理元数据';
COMMENT ON COLUMN agent_memory.revision_no IS '乐观并发及运行冻结修订号';
COMMENT ON COLUMN agent_memory.reviewed_by IS '最近审核人';
COMMENT ON COLUMN agent_memory.reviewed_at IS '最近审核时间';
COMMENT ON COLUMN agent_memory.review_comment IS '审核说明';
COMMENT ON COLUMN agent_memory.del_flag IS '逻辑删除标记';
COMMENT ON COLUMN agent_memory.search_vector IS '记忆全文检索向量';
