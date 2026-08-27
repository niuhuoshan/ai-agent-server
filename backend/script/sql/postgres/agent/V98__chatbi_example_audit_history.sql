-- Durable ChatBI example revisions and review audit facts.
-- The example row remains the current source of truth; this table is append-only history.
BEGIN;

CREATE SEQUENCE IF NOT EXISTS agent_chatbi_example_revision_seq;

CREATE TABLE IF NOT EXISTS agent_chatbi_example_revision (
    id                 BIGINT PRIMARY KEY,
    revision_no        BIGINT NOT NULL DEFAULT nextval('agent_chatbi_example_revision_seq'),
    example_id         BIGINT NOT NULL,
    action             VARCHAR(32) NOT NULL,
    review_status      VARCHAR(20) NOT NULL,
    user_query         TEXT NOT NULL,
    refined_query      TEXT,
    context_summary    TEXT,
    sql_text           TEXT NOT NULL,
    sql_metadata_json  JSONB NOT NULL DEFAULT '{}'::jsonb,
    category           VARCHAR(32) NOT NULL,
    enhance_status     VARCHAR(20) NOT NULL,
    local_sync_status  VARCHAR(20) NOT NULL,
    actor_type         VARCHAR(20) NOT NULL,
    actor_id           BIGINT,
    reason             VARCHAR(512),
    content_hash       CHAR(64),
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_chatbi_example_revision_action
        CHECK (action IN ('created', 'updated', 'enhanced', 'reviewed', 'synced', 'deleted')),
    CONSTRAINT ck_agent_chatbi_example_revision_actor
        CHECK (actor_type IN ('user', 'service_account', 'application', 'agent', 'system'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_chatbi_example_revision_no
    ON agent_chatbi_example_revision (example_id, revision_no);
CREATE INDEX IF NOT EXISTS idx_agent_chatbi_example_revision_example
    ON agent_chatbi_example_revision (example_id, created_at DESC, revision_no DESC);

COMMENT ON TABLE agent_chatbi_example_revision IS 'ChatBI 案例追加式内容版本历史；不替代当前案例行';
COMMENT ON COLUMN agent_chatbi_example_revision.content_hash IS '当前案例内容指纹，不包含用户凭证';

COMMIT;
