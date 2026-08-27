-- agent platform schema V35: bounded immutable ChatBI result snapshots for controlled export

BEGIN;

CREATE TABLE IF NOT EXISTS agent_data_query_result (
    query_id            BIGINT PRIMARY KEY,
    columns_json        JSONB NOT NULL,
    rows_json           JSONB NOT NULL,
    content_hash        CHAR(64) NOT NULL,
    row_count           INTEGER NOT NULL,
    result_bytes        INTEGER NOT NULL,
    created_by          BIGINT NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_data_query_result_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_data_query_result_rows CHECK (row_count >= 0 AND row_count <= 10000),
    CONSTRAINT ck_agent_data_query_result_bytes CHECK (result_bytes > 0 AND result_bytes <= 10485760)
);

CREATE INDEX IF NOT EXISTS idx_agent_data_query_result_owner
    ON agent_data_query_result (created_by, created_at DESC);

COMMENT ON TABLE agent_data_query_result IS '只读ChatBI有界结果快照，用于哈希校验后的受控CSV导出';

COMMIT;
