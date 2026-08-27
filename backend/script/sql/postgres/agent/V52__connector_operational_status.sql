-- Connector health is recorded separately; operators only control active/disabled lifecycle state.
UPDATE agent_connector
SET status = 'disabled',
    update_time = COALESCE(update_time, CURRENT_TIMESTAMP)
WHERE status = 'testing';

ALTER TABLE agent_connector
    ALTER COLUMN status SET DEFAULT 'disabled';

ALTER TABLE agent_connector
    DROP CONSTRAINT ck_agent_connector_status;

ALTER TABLE agent_connector
    ADD CONSTRAINT ck_agent_connector_status
        CHECK (status IN ('active', 'disabled'));
