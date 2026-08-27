-- Store manually entered model API keys as AES-GCM envelopes instead of env:NAME references.
-- The installation key is supplied through NHS_NOTIFICATION_CONFIG_KEY and is never persisted.

BEGIN;

ALTER TABLE agent_model
    ALTER COLUMN credential_ref TYPE TEXT;

COMMENT ON COLUMN agent_model.credential_ref IS
    'AES-GCM encrypted provider API key (v1s. envelope); never an environment-variable reference';

COMMIT;
