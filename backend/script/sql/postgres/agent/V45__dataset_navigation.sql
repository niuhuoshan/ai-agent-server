-- agent platform schema V45: private per-user dataset navigation state

BEGIN;

CREATE TABLE IF NOT EXISTS agent_dataset_navigation_cache (
    user_id BIGINT NOT NULL,
    menu_hash VARCHAR(64) NOT NULL,
    payload_json JSONB NOT NULL,
    generated_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, menu_hash),
    CONSTRAINT chk_agent_dataset_navigation_cache_hash
        CHECK (menu_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_dataset_navigation_cache_payload
        CHECK (jsonb_typeof(payload_json) = 'object'),
    CONSTRAINT chk_agent_dataset_navigation_cache_expiry
        CHECK (expires_at > generated_at)
);

COMMENT ON TABLE agent_dataset_navigation_cache IS '用户数据门户导航缓存，按当前用户和授权目录指纹隔离';
COMMENT ON COLUMN agent_dataset_navigation_cache.user_id IS '用户ID';
COMMENT ON COLUMN agent_dataset_navigation_cache.menu_hash IS '授权数据目录SHA-256指纹';
COMMENT ON COLUMN agent_dataset_navigation_cache.payload_json IS '结构化导航、Markdown及模型降级状态JSON';
COMMENT ON COLUMN agent_dataset_navigation_cache.generated_at IS '导航生成时间';
COMMENT ON COLUMN agent_dataset_navigation_cache.expires_at IS '缓存失效时间';
COMMENT ON COLUMN agent_dataset_navigation_cache.created_at IS '创建时间';
COMMENT ON COLUMN agent_dataset_navigation_cache.updated_at IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_agent_dataset_navigation_cache_expiry
    ON agent_dataset_navigation_cache (expires_at);

CREATE TABLE IF NOT EXISTS agent_dataset_navigation_click (
    user_id BIGINT NOT NULL,
    question_hash VARCHAR(64) NOT NULL,
    query_text VARCHAR(2000) NOT NULL,
    label VARCHAR(200),
    group_id VARCHAR(128),
    click_count BIGINT NOT NULL DEFAULT 1,
    last_clicked_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, question_hash),
    CONSTRAINT chk_agent_dataset_navigation_click_hash
        CHECK (question_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_dataset_navigation_click_count
        CHECK (click_count > 0),
    CONSTRAINT chk_agent_dataset_navigation_click_expiry
        CHECK (expires_at > last_clicked_at)
);

COMMENT ON TABLE agent_dataset_navigation_click IS '用户数据门户问题点击排序统计';
COMMENT ON COLUMN agent_dataset_navigation_click.user_id IS '用户ID';
COMMENT ON COLUMN agent_dataset_navigation_click.question_hash IS '规范化问题SHA-256指纹';
COMMENT ON COLUMN agent_dataset_navigation_click.query_text IS '完整快捷提问文本';
COMMENT ON COLUMN agent_dataset_navigation_click.label IS '快捷按钮短标签';
COMMENT ON COLUMN agent_dataset_navigation_click.group_id IS '业务场景卡片ID';
COMMENT ON COLUMN agent_dataset_navigation_click.click_count IS '累计点击次数';
COMMENT ON COLUMN agent_dataset_navigation_click.last_clicked_at IS '最后点击时间';
COMMENT ON COLUMN agent_dataset_navigation_click.expires_at IS '统计失效时间';
COMMENT ON COLUMN agent_dataset_navigation_click.created_at IS '创建时间';
COMMENT ON COLUMN agent_dataset_navigation_click.updated_at IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_agent_dataset_navigation_click_rank
    ON agent_dataset_navigation_click (user_id, click_count DESC, last_clicked_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_dataset_navigation_click_expiry
    ON agent_dataset_navigation_click (expires_at);

CREATE TABLE IF NOT EXISTS agent_dataset_navigation_recent_question (
    user_id BIGINT NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    group_hash VARCHAR(64) NOT NULL,
    question_hash VARCHAR(64) NOT NULL,
    query_text VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, purpose, group_hash, question_hash),
    CONSTRAINT chk_agent_dataset_navigation_recent_purpose
        CHECK (purpose IN ('questions', 'followups', 'table')),
    CONSTRAINT chk_agent_dataset_navigation_recent_group_hash
        CHECK (group_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_dataset_navigation_recent_question_hash
        CHECK (question_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_dataset_navigation_recent_expiry
        CHECK (expires_at > created_at)
);

COMMENT ON TABLE agent_dataset_navigation_recent_question IS '数据门户局部刷新短期去重问题';
COMMENT ON COLUMN agent_dataset_navigation_recent_question.user_id IS '用户ID';
COMMENT ON COLUMN agent_dataset_navigation_recent_question.purpose IS '用途：推荐问题、继续追问或单表推荐';
COMMENT ON COLUMN agent_dataset_navigation_recent_question.group_hash IS '场景卡片或数据表SHA-256指纹';
COMMENT ON COLUMN agent_dataset_navigation_recent_question.question_hash IS '问题SHA-256指纹';
COMMENT ON COLUMN agent_dataset_navigation_recent_question.query_text IS '已生成的完整问题文本';
COMMENT ON COLUMN agent_dataset_navigation_recent_question.created_at IS '生成时间';
COMMENT ON COLUMN agent_dataset_navigation_recent_question.expires_at IS '去重记录失效时间';

CREATE INDEX IF NOT EXISTS idx_agent_dataset_navigation_recent_expiry
    ON agent_dataset_navigation_recent_question (expires_at);

COMMIT;
