-- Nhs SSO/third-party identity provider synchronization compatibility

BEGIN;

CREATE TABLE IF NOT EXISTS agent_identity_sync_config (
    id                    BIGINT PRIMARY KEY,
    enabled               BOOLEAN NOT NULL DEFAULT FALSE,
    provider_type         VARCHAR(32) NOT NULL DEFAULT 'database',
    data_source_id        BIGINT,
    endpoint_url          VARCHAR(1024),
    credential_ref        VARCHAR(255),
    auth_type             VARCHAR(16) NOT NULL DEFAULT 'none',
    credential_header     VARCHAR(64),
    request_method        VARCHAR(8) NOT NULL DEFAULT 'GET',
    request_headers_json  JSONB NOT NULL DEFAULT '{}'::jsonb,
    request_body_json     JSONB NOT NULL DEFAULT '{}'::jsonb,
    response_items_path   VARCHAR(255),
    table_name            VARCHAR(255),
    username_column       VARCHAR(128) NOT NULL DEFAULT 'user_name',
    display_name_column   VARCHAR(128),
    email_column          VARCHAR(128),
    phone_column          VARCHAR(128),
    remark_column         VARCHAR(128),
    status_column         VARCHAR(128),
    extra_mappings_json   JSONB NOT NULL DEFAULT '[]'::jsonb,
    default_role_key      VARCHAR(100),
    schedule              VARCHAR(16) NOT NULL DEFAULT 'off',
    revision_no           BIGINT NOT NULL DEFAULT 1,
    last_preview_at       TIMESTAMP,
    last_run_at           TIMESTAMP,
    last_run_status       VARCHAR(32),
    last_error            VARCHAR(2000),
    update_by             BIGINT,
    update_time           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_identity_sync_config_singleton CHECK (id = 1),
    CONSTRAINT ck_agent_identity_sync_provider CHECK (provider_type IN ('database', 'http_json')),
    CONSTRAINT ck_agent_identity_sync_auth CHECK (auth_type IN ('none', 'basic', 'bearer', 'header')),
    CONSTRAINT ck_agent_identity_sync_method CHECK (request_method IN ('GET', 'POST')),
    CONSTRAINT ck_agent_identity_sync_schedule CHECK (schedule IN ('off', 'hourly', 'daily', 'weekly')),
    CONSTRAINT ck_agent_identity_sync_revision CHECK (revision_no > 0)
);

CREATE TABLE IF NOT EXISTS agent_identity_sync_run (
    id                  BIGINT PRIMARY KEY,
    retry_of_run_id     BIGINT,
    provider_type       VARCHAR(32) NOT NULL,
    config_revision     BIGINT NOT NULL,
    status              VARCHAR(32) NOT NULL,
    requested_names_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    items_json          JSONB NOT NULL DEFAULT '[]'::jsonb,
    discovered_count    INTEGER NOT NULL DEFAULT 0,
    selected_count      INTEGER NOT NULL DEFAULT 0,
    created_count       INTEGER NOT NULL DEFAULT 0,
    updated_count       INTEGER NOT NULL DEFAULT 0,
    skipped_count       INTEGER NOT NULL DEFAULT 0,
    failed_count        INTEGER NOT NULL DEFAULT 0,
    error_summary       VARCHAR(2000),
    requested_by        BIGINT NOT NULL,
    started_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at         TIMESTAMP,
    CONSTRAINT fk_agent_identity_sync_retry_run FOREIGN KEY (retry_of_run_id)
        REFERENCES agent_identity_sync_run (id),
    CONSTRAINT ck_agent_identity_sync_run_status CHECK (
        status IN ('running', 'succeeded', 'partial', 'failed', 'unavailable')
    ),
    CONSTRAINT ck_agent_identity_sync_run_counts CHECK (
        discovered_count >= 0 AND selected_count >= 0 AND created_count >= 0
        AND updated_count >= 0 AND skipped_count >= 0 AND failed_count >= 0
    ),
    CONSTRAINT ck_agent_identity_sync_run_revision CHECK (config_revision > 0),
    CONSTRAINT ck_agent_identity_sync_requested_by CHECK (requested_by > 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_identity_sync_run_time
    ON agent_identity_sync_run (started_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_agent_identity_sync_run_status
    ON agent_identity_sync_run (status, started_at DESC);

INSERT INTO agent_identity_sync_config (
    id, enabled, provider_type, auth_type, request_method, username_column,
    default_role_key, schedule, revision_no, update_time
) VALUES (
    1, FALSE, 'database', 'none', 'GET', 'user_name', NULL, 'off', 1, CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO NOTHING;

COMMENT ON TABLE agent_identity_sync_config IS '私有化实例第三方身份源与用户同步当前配置';
COMMENT ON COLUMN agent_identity_sync_config.id IS '单例配置ID，固定为1';
COMMENT ON COLUMN agent_identity_sync_config.enabled IS '是否允许执行第三方用户同步';
COMMENT ON COLUMN agent_identity_sync_config.provider_type IS '身份源类型：database或http_json';
COMMENT ON COLUMN agent_identity_sync_config.data_source_id IS 'database身份源引用的数据源ID';
COMMENT ON COLUMN agent_identity_sync_config.endpoint_url IS 'http_json身份源HTTPS接口地址';
COMMENT ON COLUMN agent_identity_sync_config.credential_ref IS '身份源凭证env:NAME引用，不保存明文';
COMMENT ON COLUMN agent_identity_sync_config.auth_type IS 'HTTP身份源认证方式：none、basic、bearer或header';
COMMENT ON COLUMN agent_identity_sync_config.credential_header IS 'header认证时注入凭证密码值的自定义请求头名称';
COMMENT ON COLUMN agent_identity_sync_config.request_method IS 'HTTP身份源请求方法：GET或POST';
COMMENT ON COLUMN agent_identity_sync_config.request_headers_json IS 'HTTP身份源非敏感请求头配置';
COMMENT ON COLUMN agent_identity_sync_config.request_body_json IS 'HTTP身份源请求体配置';
COMMENT ON COLUMN agent_identity_sync_config.response_items_path IS 'HTTP JSON响应中的用户数组点路径';
COMMENT ON COLUMN agent_identity_sync_config.table_name IS 'database身份源用户表名称，可含schema';
COMMENT ON COLUMN agent_identity_sync_config.username_column IS '来源用户名字段，作为本地用户映射键';
COMMENT ON COLUMN agent_identity_sync_config.display_name_column IS '来源姓名或昵称字段';
COMMENT ON COLUMN agent_identity_sync_config.email_column IS '来源邮箱字段';
COMMENT ON COLUMN agent_identity_sync_config.phone_column IS '来源手机号字段';
COMMENT ON COLUMN agent_identity_sync_config.remark_column IS '来源备注字段';
COMMENT ON COLUMN agent_identity_sync_config.status_column IS '来源启停状态字段';
COMMENT ON COLUMN agent_identity_sync_config.extra_mappings_json IS '来源扩展字段映射，仅保存在运行快照中';
COMMENT ON COLUMN agent_identity_sync_config.default_role_key IS '新用户可选绑定的NHS角色标识，留空则仅使用平台个人权限';
COMMENT ON COLUMN agent_identity_sync_config.schedule IS '调度预设：关闭、每小时、每日或每周';
COMMENT ON COLUMN agent_identity_sync_config.revision_no IS '配置乐观并发修订号';
COMMENT ON COLUMN agent_identity_sync_config.last_preview_at IS '最近一次成功预览时间';
COMMENT ON COLUMN agent_identity_sync_config.last_run_at IS '最近一次同步完成时间';
COMMENT ON COLUMN agent_identity_sync_config.last_run_status IS '最近一次同步运行状态';
COMMENT ON COLUMN agent_identity_sync_config.last_error IS '最近一次预览或运行错误摘要';
COMMENT ON COLUMN agent_identity_sync_config.update_by IS '最近修改配置的管理员用户ID';
COMMENT ON COLUMN agent_identity_sync_config.update_time IS '配置最近更新时间';

COMMENT ON TABLE agent_identity_sync_run IS '第三方身份源用户同步运行与可重试快照';
COMMENT ON COLUMN agent_identity_sync_run.id IS '同步运行主键ID';
COMMENT ON COLUMN agent_identity_sync_run.retry_of_run_id IS '本次运行重试的原运行ID';
COMMENT ON COLUMN agent_identity_sync_run.provider_type IS '本次冻结的身份源类型';
COMMENT ON COLUMN agent_identity_sync_run.config_revision IS '本次冻结的配置修订号';
COMMENT ON COLUMN agent_identity_sync_run.status IS '运行状态：执行中、成功、部分成功、失败或不可用';
COMMENT ON COLUMN agent_identity_sync_run.requested_names_json IS '管理员指定同步的用户名列表';
COMMENT ON COLUMN agent_identity_sync_run.items_json IS '去敏后的来源用户快照及逐项执行结果';
COMMENT ON COLUMN agent_identity_sync_run.discovered_count IS '身份源发现用户数量';
COMMENT ON COLUMN agent_identity_sync_run.selected_count IS '本次选择执行的用户数量';
COMMENT ON COLUMN agent_identity_sync_run.created_count IS '本次新增本地用户数量';
COMMENT ON COLUMN agent_identity_sync_run.updated_count IS '本次更新本地用户数量';
COMMENT ON COLUMN agent_identity_sync_run.skipped_count IS '本次跳过用户数量';
COMMENT ON COLUMN agent_identity_sync_run.failed_count IS '本次失败用户数量';
COMMENT ON COLUMN agent_identity_sync_run.error_summary IS 'Provider或运行失败的安全错误摘要';
COMMENT ON COLUMN agent_identity_sync_run.requested_by IS '发起同步的管理员用户ID';
COMMENT ON COLUMN agent_identity_sync_run.started_at IS '运行开始时间';
COMMENT ON COLUMN agent_identity_sync_run.finished_at IS '运行结束时间';

COMMIT;
