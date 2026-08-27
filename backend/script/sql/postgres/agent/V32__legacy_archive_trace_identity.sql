-- A Nhs high-level execution history and its step trace legitimately share trace_id.
-- Archive identity is source_execution_id; trace_id remains an indexed correlation field.

BEGIN;

DROP INDEX IF EXISTS uk_agent_legacy_execution_trace;

CREATE INDEX IF NOT EXISTS idx_agent_legacy_execution_trace
    ON agent_legacy_execution_archive (source_system, source_trace_id)
    WHERE source_trace_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_legacy_execution_identity
    ON agent_legacy_execution_archive (
        migration_run_id,
        source_system,
        source_execution_id
    )
    WHERE source_execution_id IS NOT NULL;

COMMIT;
