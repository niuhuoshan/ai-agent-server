-- agent platform schema V55: encrypted per-user external notification channels

BEGIN;

CREATE TABLE IF NOT EXISTS agent_user_notification_channel (
    id               BIGINT PRIMARY KEY,
    user_id          BIGINT NOT NULL,
    channel_type     VARCHAR(32) NOT NULL,
    is_enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    config_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    secret_payload   TEXT,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_user_notification_channel UNIQUE (user_id, channel_type),
    CONSTRAINT ck_agent_user_notification_channel_type
        CHECK (channel_type IN ('dingtalk', 'wechat_work', 'email'))
);

CREATE INDEX IF NOT EXISTS idx_agent_user_notification_channel_user
    ON agent_user_notification_channel (user_id, channel_type);

COMMENT ON TABLE agent_user_notification_channel IS '用户个人外部通知渠道配置表';
COMMENT ON COLUMN agent_user_notification_channel.id IS '主键ID';
COMMENT ON COLUMN agent_user_notification_channel.user_id IS '若依用户ID，仅允许本人读取和修改';
COMMENT ON COLUMN agent_user_notification_channel.channel_type IS '渠道类型：dingtalk钉钉、wechat_work企业微信、email邮件';
COMMENT ON COLUMN agent_user_notification_channel.is_enabled IS '是否启用该通知渠道';
COMMENT ON COLUMN agent_user_notification_channel.config_json IS '不含密钥的渠道配置JSON';
COMMENT ON COLUMN agent_user_notification_channel.secret_payload IS 'AES-GCM加密后的Webhook及密码配置';
COMMENT ON COLUMN agent_user_notification_channel.created_at IS '创建时间';
COMMENT ON COLUMN agent_user_notification_channel.updated_at IS '最后更新时间';

COMMIT;
