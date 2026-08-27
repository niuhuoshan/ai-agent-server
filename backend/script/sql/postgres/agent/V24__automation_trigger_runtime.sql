-- agent platform schema V24: secure automation triggers and isolated machine grants

BEGIN;

CREATE TABLE IF NOT EXISTS iam_service_account_grant (
    id                  BIGINT PRIMARY KEY,
    service_account_id  BIGINT NOT NULL,
    resource_type       VARCHAR(32) NOT NULL,
    resource_id         BIGINT,
    resource_key        VARCHAR(255),
    action              VARCHAR(32) NOT NULL,
    effect              VARCHAR(24) NOT NULL DEFAULT 'allow',
    reason              TEXT NOT NULL,
    expires_at          TIMESTAMP,
    revoked_at          TIMESTAMP,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_iam_service_account_grant_target
        CHECK (resource_id IS NOT NULL OR NULLIF(BTRIM(resource_key), '') IS NOT NULL),
    CONSTRAINT ck_iam_service_account_grant_effect CHECK (effect IN ('allow', 'deny')),
    CONSTRAINT ck_iam_service_account_grant_expiry
        CHECK (expires_at IS NULL OR expires_at > created_at),
    CONSTRAINT ck_iam_service_account_grant_revoked
        CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_service_account_grant_active
    ON iam_service_account_grant (
        service_account_id,
        resource_type,
        COALESCE(resource_id, 0),
        COALESCE(resource_key, ''),
        action
    ) WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_iam_service_account_grant_resolve
    ON iam_service_account_grant (service_account_id, resource_type, action)
    WHERE revoked_at IS NULL;

ALTER TABLE agent_automation_trigger
    ADD COLUMN IF NOT EXISTS task_id BIGINT,
    ADD COLUMN IF NOT EXISTS task_revision_no BIGINT,
    ADD COLUMN IF NOT EXISTS revision_no BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS misfire_policy VARCHAR(16) NOT NULL DEFAULT 'fire_once',
    ADD COLUMN IF NOT EXISTS max_catchup_count INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS max_attempts INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS input_template TEXT;

UPDATE agent_automation_trigger trigger
SET task_id = version.task_id,
    task_revision_no = COALESCE(version.version_no, 1)
FROM agent_task_version version
WHERE trigger.task_version_id = version.id
  AND (trigger.task_id IS NULL OR trigger.task_revision_no IS NULL);

ALTER TABLE agent_automation_trigger
    ALTER COLUMN task_id SET NOT NULL,
    ALTER COLUMN task_revision_no SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_agent_automation_revision'
    ) THEN
        ALTER TABLE agent_automation_trigger
            ADD CONSTRAINT ck_agent_automation_revision
            CHECK (revision_no > 0 AND task_revision_no > 0);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_agent_automation_misfire'
    ) THEN
        ALTER TABLE agent_automation_trigger
            ADD CONSTRAINT ck_agent_automation_misfire
            CHECK (misfire_policy IN ('skip', 'fire_once', 'catch_up'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_agent_automation_limits'
    ) THEN
        ALTER TABLE agent_automation_trigger
            ADD CONSTRAINT ck_agent_automation_limits
            CHECK (max_catchup_count BETWEEN 1 AND 10 AND max_attempts BETWEEN 1 AND 10);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_automation_trigger_key
    ON agent_automation_trigger (trigger_key);

CREATE INDEX IF NOT EXISTS idx_agent_automation_due
    ON agent_automation_trigger (next_run_at, id)
    WHERE trigger_type = 'cron' AND status = 'active' AND del_flag = '0';

CREATE TABLE IF NOT EXISTS agent_automation_fire (
    id                  BIGINT PRIMARY KEY,
    trigger_id          BIGINT NOT NULL,
    trigger_revision_no BIGINT NOT NULL,
    service_account_id  BIGINT NOT NULL,
    source_type         VARCHAR(16) NOT NULL,
    fire_key            VARCHAR(96) NOT NULL,
    payload_hash        CHAR(64) NOT NULL,
    payload_json        JSONB NOT NULL,
    scheduled_at        TIMESTAMP,
    status              VARCHAR(16) NOT NULL DEFAULT 'queued',
    job_id              BIGINT,
    run_id              BIGINT,
    attempt_no          INTEGER NOT NULL DEFAULT 0,
    last_error          VARCHAR(2000),
    accepted_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dispatched_at       TIMESTAMP,
    completed_at        TIMESTAMP,
    CONSTRAINT uk_agent_automation_fire UNIQUE (trigger_id, fire_key),
    CONSTRAINT ck_agent_automation_fire_source
        CHECK (source_type IN ('manual', 'cron', 'webhook')),
    CONSTRAINT ck_agent_automation_fire_status
        CHECK (status IN ('queued', 'running', 'retry', 'dispatched', 'dead', 'cancelled')),
    CONSTRAINT ck_agent_automation_fire_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_automation_fire_attempt CHECK (attempt_no >= 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_automation_fire_trigger
    ON agent_automation_fire (trigger_id, accepted_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_automation_fire_status
    ON agent_automation_fire (status, accepted_at);

CREATE TABLE IF NOT EXISTS agent_webhook_nonce (
    id                  BIGINT PRIMARY KEY,
    credential_id       BIGINT NOT NULL,
    nonce_hash          CHAR(64) NOT NULL,
    request_timestamp   TIMESTAMP NOT NULL,
    expires_at          TIMESTAMP NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_webhook_nonce UNIQUE (credential_id, nonce_hash),
    CONSTRAINT ck_agent_webhook_nonce_hash CHECK (nonce_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_webhook_nonce_expiry CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS idx_agent_webhook_nonce_expiry
    ON agent_webhook_nonce (expires_at);

ALTER TABLE agent_job_queue
    ADD COLUMN IF NOT EXISTS fire_id BIGINT,
    ADD COLUMN IF NOT EXISTS lease_token VARCHAR(64),
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_agent_job_queue_claim
    ON agent_job_queue (priority DESC, available_at, id)
    WHERE status IN ('queued', 'running');

COMMENT ON TABLE iam_service_account_grant IS '服务账号独立资源授权表，不继承人员权限';
COMMENT ON TABLE agent_automation_fire IS '自动化触发受理、幂等和运行关联事实表';
COMMENT ON TABLE agent_webhook_nonce IS 'Webhook签名随机数防重放表，仅保存哈希';
COMMENT ON COLUMN agent_automation_trigger.task_revision_no IS '触发器绑定时任务版本号';
COMMENT ON COLUMN agent_automation_trigger.revision_no IS '触发器配置乐观锁版本';
COMMENT ON COLUMN agent_job_queue.lease_token IS '每次队列抢占生成的不可复用租约令牌';

COMMIT;
