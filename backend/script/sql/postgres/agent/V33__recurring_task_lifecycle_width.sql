-- L3_recurring_task is 17 characters; the original VARCHAR(16) rejected its own check value.

BEGIN;

ALTER TABLE agent_task
    ALTER COLUMN lifecycle_level TYPE VARCHAR(24);

COMMIT;
