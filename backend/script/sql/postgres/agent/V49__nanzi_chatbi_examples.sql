-- Nhs ChatBI/Few-shot examples. The local index is optional; the SQL row is authoritative.
BEGIN;

CREATE TABLE IF NOT EXISTS agent_chatbi_example (
    id                 BIGINT PRIMARY KEY,
    trace_id           VARCHAR(128) NOT NULL,
    agent_id           VARCHAR(128),
    dataset_id         BIGINT,
    user_query         TEXT NOT NULL,
    refined_query      TEXT,
    context_summary    TEXT,
    sql_text           TEXT NOT NULL,
    sql_metadata_json  JSONB NOT NULL DEFAULT '{}'::jsonb,
    category           VARCHAR(32) NOT NULL DEFAULT 'general',
    enhance_status     VARCHAR(20) NOT NULL DEFAULT 'not_requested',
    ai_answer          TEXT,
    feedback_type      VARCHAR(16) NOT NULL DEFAULT 'up',
    review_status      VARCHAR(20) NOT NULL DEFAULT 'pending',
    error_message      TEXT,
    use_count          INTEGER NOT NULL DEFAULT 0,
    local_sync_status  VARCHAR(20) NOT NULL DEFAULT 'pending',
    local_sync_error   TEXT,
    local_synced_at    TIMESTAMP,
    created_by         BIGINT NOT NULL,
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP,
    del_flag           CHAR(1) NOT NULL DEFAULT '0',
    CONSTRAINT uk_agent_chatbi_example_trace UNIQUE (trace_id),
    CONSTRAINT ck_agent_chatbi_example_review_status
        CHECK (review_status IN ('pending', 'approved', 'rejected', 'deprecated')),
    CONSTRAINT ck_agent_chatbi_example_enhance_status
        CHECK (enhance_status IN ('not_requested', 'pending', 'running', 'succeeded', 'failed')),
    CONSTRAINT ck_agent_chatbi_example_local_sync_status
        CHECK (local_sync_status IN ('pending', 'syncing', 'synced', 'failed')),
    CONSTRAINT ck_agent_chatbi_example_del_flag CHECK (del_flag IN ('0', '1')),
    CONSTRAINT ck_agent_chatbi_example_use_count CHECK (use_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_chatbi_example_review
    ON agent_chatbi_example (review_status, created_at DESC, id DESC)
    WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_chatbi_example_dataset
    ON agent_chatbi_example (dataset_id, review_status, local_sync_status)
    WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_chatbi_example_owner
    ON agent_chatbi_example (created_by, created_at DESC)
    WHERE del_flag = '0';

COMMENT ON TABLE agent_chatbi_example IS 'ChatBI/Few-shot 本地经验案例，SQL 行是权威事实；可选向量索引不得替代本表';
COMMENT ON COLUMN agent_chatbi_example.trace_id IS '来源对话回合或反馈 Trace 标识';
COMMENT ON COLUMN agent_chatbi_example.review_status IS '审核状态：pending待审、approved通过、rejected拒绝、deprecated废弃';
COMMENT ON COLUMN agent_chatbi_example.local_sync_status IS '本地运行时案例索引同步状态';
COMMENT ON COLUMN agent_chatbi_example.sql_metadata_json IS '只读 SQL 的结构化元数据，不保存凭证';

COMMIT;
