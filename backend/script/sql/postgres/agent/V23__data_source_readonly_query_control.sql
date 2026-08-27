-- data source catalog, metadata synchronization and read-only query controls

BEGIN;

ALTER TABLE agent_data_source
    ADD COLUMN IF NOT EXISTS revision_no INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS connection_timeout_ms INTEGER NOT NULL DEFAULT 5000,
    ADD COLUMN IF NOT EXISTS statement_timeout_ms INTEGER NOT NULL DEFAULT 15000,
    ADD COLUMN IF NOT EXISTS max_rows INTEGER NOT NULL DEFAULT 500,
    ADD COLUMN IF NOT EXISTS max_result_bytes INTEGER NOT NULL DEFAULT 2097152,
    ADD COLUMN IF NOT EXISTS last_test_status VARCHAR(16),
    ADD COLUMN IF NOT EXISTS last_test_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_test_error VARCHAR(512),
    ADD COLUMN IF NOT EXISTS last_metadata_sync_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_metadata_sync_error VARCHAR(512);

ALTER TABLE agent_data_source
    DROP CONSTRAINT IF EXISTS ck_agent_data_source_revision,
    DROP CONSTRAINT IF EXISTS ck_agent_data_source_connection_timeout,
    DROP CONSTRAINT IF EXISTS ck_agent_data_source_statement_timeout,
    DROP CONSTRAINT IF EXISTS ck_agent_data_source_max_rows,
    DROP CONSTRAINT IF EXISTS ck_agent_data_source_max_result_bytes,
    DROP CONSTRAINT IF EXISTS ck_agent_data_source_last_test_status;

ALTER TABLE agent_data_source
    ADD CONSTRAINT ck_agent_data_source_revision CHECK (revision_no > 0),
    ADD CONSTRAINT ck_agent_data_source_connection_timeout
        CHECK (connection_timeout_ms BETWEEN 1000 AND 30000),
    ADD CONSTRAINT ck_agent_data_source_statement_timeout
        CHECK (statement_timeout_ms BETWEEN 1000 AND 120000),
    ADD CONSTRAINT ck_agent_data_source_max_rows CHECK (max_rows BETWEEN 1 AND 5000),
    ADD CONSTRAINT ck_agent_data_source_max_result_bytes
        CHECK (max_result_bytes BETWEEN 1024 AND 10485760),
    ADD CONSTRAINT ck_agent_data_source_last_test_status
        CHECK (last_test_status IS NULL OR last_test_status IN ('success', 'failed'));

ALTER TABLE agent_data_dataset
    ADD COLUMN IF NOT EXISTS schema_names_json JSONB NOT NULL DEFAULT '["public"]'::jsonb,
    ADD COLUMN IF NOT EXISTS revision_no INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS last_sync_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_sync_error VARCHAR(512);

ALTER TABLE agent_data_dataset
    DROP CONSTRAINT IF EXISTS ck_agent_data_dataset_revision,
    DROP CONSTRAINT IF EXISTS ck_agent_data_dataset_schema_names;

ALTER TABLE agent_data_dataset
    ADD CONSTRAINT ck_agent_data_dataset_revision CHECK (revision_no > 0),
    ADD CONSTRAINT ck_agent_data_dataset_schema_names
        CHECK (jsonb_typeof(schema_names_json) = 'array' AND jsonb_array_length(schema_names_json) BETWEEN 1 AND 16);

ALTER TABLE agent_data_table
    ADD COLUMN IF NOT EXISTS physical_schema VARCHAR(255) NOT NULL DEFAULT 'public',
    ADD COLUMN IF NOT EXISTS metadata_present BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE agent_data_column
    ADD COLUMN IF NOT EXISTS metadata_present BOOLEAN NOT NULL DEFAULT TRUE;

DROP INDEX IF EXISTS uk_agent_data_table_physical_active;
CREATE UNIQUE INDEX uk_agent_data_table_physical_active
    ON agent_data_table (dataset_id, physical_schema, physical_name)
    WHERE del_flag = '0';

ALTER TABLE agent_data_query
    ADD COLUMN IF NOT EXISTS data_source_revision INTEGER,
    ADD COLUMN IF NOT EXISTS dataset_revision INTEGER,
    ADD COLUMN IF NOT EXISTS result_bytes BIGINT,
    ADD COLUMN IF NOT EXISTS result_truncated BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_agent_data_query_creator_created
    ON agent_data_query (created_by, created_at DESC);

COMMIT;
