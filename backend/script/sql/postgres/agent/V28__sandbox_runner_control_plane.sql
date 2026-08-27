-- agent platform schema V28: isolated sandbox runner control plane

BEGIN;

CREATE TABLE IF NOT EXISTS agent_sandbox_runner (
    id                  BIGINT PRIMARY KEY,
    runner_key          VARCHAR(64) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    secret_hash         CHAR(64) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    capabilities_json   JSONB NOT NULL DEFAULT '[]'::jsonb,
    max_concurrency     INTEGER NOT NULL DEFAULT 1,
    active_job_count    INTEGER NOT NULL DEFAULT 0,
    runner_version      VARCHAR(64),
    last_heartbeat_at   TIMESTAMP,
    heartbeat_expires_at TIMESTAMP,
    registered_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    secret_rotated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_sandbox_runner_key UNIQUE (runner_key),
    CONSTRAINT ck_agent_sandbox_runner_key
        CHECK (runner_key ~ '^[a-z][a-z0-9._-]{2,63}$'),
    CONSTRAINT ck_agent_sandbox_runner_hash
        CHECK (secret_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_sandbox_runner_status
        CHECK (status IN ('active', 'draining', 'disabled', 'stale')),
    CONSTRAINT ck_agent_sandbox_runner_capacity
        CHECK (max_concurrency BETWEEN 1 AND 128
            AND active_job_count BETWEEN 0 AND max_concurrency),
    CONSTRAINT ck_agent_sandbox_runner_capabilities
        CHECK (jsonb_typeof(capabilities_json) = 'array')
);

CREATE INDEX IF NOT EXISTS idx_agent_sandbox_runner_available
    ON agent_sandbox_runner (status, heartbeat_expires_at, id);

CREATE TABLE IF NOT EXISTS agent_sandbox_job (
    id                  BIGINT PRIMARY KEY,
    task_id             BIGINT NOT NULL,
    run_id              BIGINT NOT NULL,
    step_id             BIGINT,
    tool_id             BIGINT NOT NULL,
    external_reply_id   VARCHAR(128),
    tool_call_id        VARCHAR(128),
    tool_name           VARCHAR(160),
    trace_id            CHAR(64) NOT NULL,
    request_hash        CHAR(64) NOT NULL,
    template_key        VARCHAR(64) NOT NULL,
    argv_json           JSONB NOT NULL,
    workspace_path      VARCHAR(512) NOT NULL,
    workspace_access    VARCHAR(16) NOT NULL DEFAULT 'read_write',
    network_policy      VARCHAR(16) NOT NULL DEFAULT 'none',
    allowed_hosts_json  JSONB NOT NULL DEFAULT '[]'::jsonb,
    timeout_seconds     INTEGER NOT NULL DEFAULT 300,
    memory_mb           INTEGER NOT NULL DEFAULT 512,
    cpu_millis          INTEGER NOT NULL DEFAULT 1000,
    pids_limit          INTEGER NOT NULL DEFAULT 128,
    max_output_bytes    INTEGER NOT NULL DEFAULT 1048576,
    status              VARCHAR(16) NOT NULL DEFAULT 'queued',
    priority            INTEGER NOT NULL DEFAULT 0,
    assigned_runner_id  BIGINT,
    job_token_hash      CHAR(64),
    lease_until         TIMESTAMP,
    attempt_no          INTEGER NOT NULL DEFAULT 0,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    token_consumed_at   TIMESTAMP,
    resume_dispatched_at TIMESTAMP,
    exit_code           INTEGER,
    stdout_text         TEXT,
    stderr_text         TEXT,
    output_manifest_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    resource_usage_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    failure_code        VARCHAR(64),
    failure_message     VARCHAR(1000),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_sandbox_job_trace UNIQUE (trace_id),
    CONSTRAINT uk_agent_sandbox_job_external_call
        UNIQUE (run_id, external_reply_id, tool_call_id),
    CONSTRAINT ck_agent_sandbox_job_hashes CHECK (
        trace_id ~ '^[0-9a-f]{64}$'
        AND request_hash ~ '^[0-9a-f]{64}$'
        AND (job_token_hash IS NULL OR job_token_hash ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_agent_sandbox_job_template
        CHECK (template_key ~ '^[a-z][a-z0-9._-]{1,63}$'),
    CONSTRAINT ck_agent_sandbox_job_json CHECK (
        jsonb_typeof(argv_json) = 'array'
        AND jsonb_array_length(argv_json) BETWEEN 1 AND 128
        AND jsonb_typeof(allowed_hosts_json) = 'array'
        AND jsonb_typeof(output_manifest_json) = 'array'
        AND jsonb_typeof(resource_usage_json) = 'object'
    ),
    CONSTRAINT ck_agent_sandbox_job_workspace_access
        CHECK (workspace_access IN ('read_only', 'read_write')),
    CONSTRAINT ck_agent_sandbox_job_network
        CHECK (network_policy IN ('none', 'allowlist')),
    CONSTRAINT ck_agent_sandbox_job_status
        CHECK (status IN ('queued', 'leased', 'running', 'succeeded', 'failed',
            'cancelled', 'expired')),
    CONSTRAINT ck_agent_sandbox_job_limits CHECK (
        timeout_seconds BETWEEN 1 AND 3600
        AND memory_mb BETWEEN 64 AND 32768
        AND cpu_millis BETWEEN 100 AND 16000
        AND pids_limit BETWEEN 16 AND 2048
        AND max_output_bytes BETWEEN 1024 AND 10485760
        AND attempt_no BETWEEN 0 AND 10
        AND priority BETWEEN -100 AND 100
    ),
    CONSTRAINT ck_agent_sandbox_job_lease CHECK (
        (status = 'queued' AND assigned_runner_id IS NULL AND job_token_hash IS NULL
            AND lease_until IS NULL)
        OR status <> 'queued'
    )
);

CREATE INDEX IF NOT EXISTS idx_agent_sandbox_job_claim
    ON agent_sandbox_job (priority DESC, created_at, id)
    WHERE status IN ('queued', 'leased', 'running');
CREATE INDEX IF NOT EXISTS idx_agent_sandbox_job_runner
    ON agent_sandbox_job (assigned_runner_id, status, lease_until);
CREATE INDEX IF NOT EXISTS idx_agent_sandbox_job_run
    ON agent_sandbox_job (run_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_agent_sandbox_job_external_batch
    ON agent_sandbox_job (run_id, external_reply_id, status, resume_dispatched_at)
    WHERE external_reply_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS agent_sandbox_nonce (
    id                  BIGINT PRIMARY KEY,
    runner_id           BIGINT NOT NULL,
    nonce_hash          CHAR(64) NOT NULL,
    request_timestamp   TIMESTAMP NOT NULL,
    expires_at          TIMESTAMP NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_sandbox_nonce UNIQUE (runner_id, nonce_hash),
    CONSTRAINT ck_agent_sandbox_nonce_hash
        CHECK (nonce_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_sandbox_nonce_expiry CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS idx_agent_sandbox_nonce_expiry
    ON agent_sandbox_nonce (expires_at);

COMMENT ON TABLE agent_sandbox_runner IS '独立沙箱执行器注册、能力与心跳状态，只保存密钥哈希';
COMMENT ON TABLE agent_sandbox_job IS '沙箱执行作业租约与有界结果事实，不包含可执行镜像地址或明文凭据';
COMMENT ON TABLE agent_sandbox_nonce IS 'Runner内部API随机数防重放表，runner_id=0表示注册请求';
COMMENT ON COLUMN agent_sandbox_job.argv_json IS '结构化参数数组，禁止平台或Runner拼接为shell命令';
COMMENT ON COLUMN agent_sandbox_job.job_token_hash IS '每次领取生成的一次性作业令牌哈希';
COMMENT ON COLUMN agent_sandbox_job.workspace_path IS '任务工作区内的规范化相对路径';

COMMIT;
