-- agent platform schema V62: persisted branding, locale and platform timezone configuration

BEGIN;

CREATE TABLE IF NOT EXISTS agent_platform_configuration (
    id                  BIGINT PRIMARY KEY,
    product_name        VARCHAR(128) NOT NULL,
    product_short_name  VARCHAR(32) NOT NULL,
    logo_url            VARCHAR(512),
    favicon_url         VARCHAR(512),
    primary_color       CHAR(7) NOT NULL,
    platform_timezone   VARCHAR(64) NOT NULL,
    default_locale      VARCHAR(16) NOT NULL,
    watermark_enabled   BOOLEAN NOT NULL DEFAULT FALSE,
    revision_no         BIGINT NOT NULL DEFAULT 1,
    update_by           BIGINT,
    update_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_platform_configuration_singleton CHECK (id = 1),
    CONSTRAINT ck_agent_platform_configuration_revision CHECK (revision_no > 0),
    CONSTRAINT ck_agent_platform_configuration_color CHECK (primary_color ~ '^#[0-9A-Fa-f]{6}$'),
    CONSTRAINT ck_agent_platform_configuration_locale CHECK (default_locale IN ('zh-CN', 'en-US'))
);

CREATE TABLE IF NOT EXISTS agent_platform_configuration_history (
    id                  BIGINT PRIMARY KEY,
    configuration_id    BIGINT NOT NULL,
    product_name        VARCHAR(128) NOT NULL,
    product_short_name  VARCHAR(32) NOT NULL,
    logo_url            VARCHAR(512),
    favicon_url         VARCHAR(512),
    primary_color       CHAR(7) NOT NULL,
    platform_timezone   VARCHAR(64) NOT NULL,
    default_locale      VARCHAR(16) NOT NULL,
    watermark_enabled   BOOLEAN NOT NULL,
    revision_no         BIGINT NOT NULL,
    change_reason       VARCHAR(500) NOT NULL,
    changed_by          BIGINT NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_platform_configuration_history_revision
        UNIQUE (configuration_id, revision_no),
    CONSTRAINT ck_agent_platform_configuration_history_revision CHECK (revision_no > 0),
    CONSTRAINT ck_agent_platform_configuration_history_color CHECK (primary_color ~ '^#[0-9A-Fa-f]{6}$'),
    CONSTRAINT ck_agent_platform_configuration_history_locale CHECK (default_locale IN ('zh-CN', 'en-US'))
);

CREATE INDEX IF NOT EXISTS idx_agent_platform_configuration_history_time
    ON agent_platform_configuration_history (created_at DESC, id DESC);

INSERT INTO agent_platform_configuration (
    id, product_name, product_short_name, logo_url, favicon_url, primary_color,
    platform_timezone, default_locale, watermark_enabled, revision_no,
    update_by, update_time
) VALUES (
    1, '企业级智能体工作平台', '智能体平台', NULL, NULL, '#18A058',
    'Asia/Shanghai', 'zh-CN', FALSE, 1, NULL, CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_platform_configuration_history (
    id, configuration_id, product_name, product_short_name, logo_url, favicon_url,
    primary_color, platform_timezone, default_locale, watermark_enabled,
    revision_no, change_reason, changed_by, created_at
)
SELECT
    1, id, product_name, product_short_name, logo_url, favicon_url,
    primary_color, platform_timezone, default_locale, watermark_enabled,
    revision_no, '系统初始化', 1, update_time
FROM agent_platform_configuration
WHERE id = 1
ON CONFLICT (configuration_id, revision_no) DO NOTHING;

COMMENT ON TABLE agent_platform_configuration IS '私有化实例平台外观、语言和时区当前配置表';
COMMENT ON COLUMN agent_platform_configuration.id IS '单例配置ID，固定为1';
COMMENT ON COLUMN agent_platform_configuration.product_name IS '平台完整产品名称';
COMMENT ON COLUMN agent_platform_configuration.product_short_name IS '平台导航短名称';
COMMENT ON COLUMN agent_platform_configuration.logo_url IS '平台Logo相对路径或HTTPS地址';
COMMENT ON COLUMN agent_platform_configuration.favicon_url IS '浏览器图标相对路径或HTTPS地址';
COMMENT ON COLUMN agent_platform_configuration.primary_color IS '平台主品牌色十六进制值';
COMMENT ON COLUMN agent_platform_configuration.platform_timezone IS '平台统一IANA时区';
COMMENT ON COLUMN agent_platform_configuration.default_locale IS '未设置个人偏好时的默认界面语言';
COMMENT ON COLUMN agent_platform_configuration.watermark_enabled IS '是否默认启用登录用户水印';
COMMENT ON COLUMN agent_platform_configuration.revision_no IS '乐观并发修订号';
COMMENT ON COLUMN agent_platform_configuration.update_by IS '最近更新管理员用户ID';
COMMENT ON COLUMN agent_platform_configuration.update_time IS '最近更新时间';

COMMENT ON TABLE agent_platform_configuration_history IS '平台配置不可变版本历史表';
COMMENT ON COLUMN agent_platform_configuration_history.id IS '配置历史主键ID';
COMMENT ON COLUMN agent_platform_configuration_history.configuration_id IS '平台配置ID';
COMMENT ON COLUMN agent_platform_configuration_history.product_name IS '该版本平台完整产品名称';
COMMENT ON COLUMN agent_platform_configuration_history.product_short_name IS '该版本平台导航短名称';
COMMENT ON COLUMN agent_platform_configuration_history.logo_url IS '该版本平台Logo地址';
COMMENT ON COLUMN agent_platform_configuration_history.favicon_url IS '该版本浏览器图标地址';
COMMENT ON COLUMN agent_platform_configuration_history.primary_color IS '该版本平台主品牌色';
COMMENT ON COLUMN agent_platform_configuration_history.platform_timezone IS '该版本平台统一IANA时区';
COMMENT ON COLUMN agent_platform_configuration_history.default_locale IS '该版本默认界面语言';
COMMENT ON COLUMN agent_platform_configuration_history.watermark_enabled IS '该版本是否默认启用用户水印';
COMMENT ON COLUMN agent_platform_configuration_history.revision_no IS '配置版本修订号';
COMMENT ON COLUMN agent_platform_configuration_history.change_reason IS '管理员填写的变更原因';
COMMENT ON COLUMN agent_platform_configuration_history.changed_by IS '执行变更的管理员用户ID';
COMMENT ON COLUMN agent_platform_configuration_history.created_at IS '配置版本创建时间';

COMMIT;
