-- Model API keys are entered in the management UI and stored with the model record.
-- Management APIs expose only whether a key is configured; runtime snapshots keep a db:model reference.

BEGIN;

UPDATE agent_model
SET credential_ref = NULL
WHERE credential_ref LIKE 'env:%'
   OR credential_ref LIKE 'v1s.%';

COMMENT ON COLUMN agent_model.credential_ref IS
    'Provider API key stored from the model management UI; never returned by management APIs';

COMMIT;
