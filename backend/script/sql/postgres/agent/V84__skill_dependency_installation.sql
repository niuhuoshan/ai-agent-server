-- Explicit, version-scoped Skill dependency installation state.
BEGIN;

CREATE TABLE IF NOT EXISTS agent_skill_dependency_install (
    id                  BIGINT PRIMARY KEY,
    skill_id            BIGINT NOT NULL,
    version_id          BIGINT NOT NULL,
    dependency_hash     CHAR(64) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'queued',
    attempt_no          INTEGER NOT NULL DEFAULT 1,
    requested_by        BIGINT,
    requested_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP,
    install_root        VARCHAR(512),
    message             VARCHAR(32768),
    CONSTRAINT uk_agent_skill_dependency_install UNIQUE (version_id, dependency_hash),
    CONSTRAINT ck_agent_skill_dependency_install_status CHECK (
        status IN ('queued', 'running', 'succeeded', 'failed', 'blocked', 'skipped')
    ),
    CONSTRAINT ck_agent_skill_dependency_install_attempt CHECK (attempt_no > 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_skill_dependency_install_skill
    ON agent_skill_dependency_install (skill_id, version_id, requested_at DESC);

COMMENT ON TABLE agent_skill_dependency_install IS 'Skill版本显式依赖安装状态；运行时不隐式安装';
COMMENT ON COLUMN agent_skill_dependency_install.id IS '安装记录ID';
COMMENT ON COLUMN agent_skill_dependency_install.skill_id IS 'Skill ID';
COMMENT ON COLUMN agent_skill_dependency_install.version_id IS '不可变Skill版本ID';
COMMENT ON COLUMN agent_skill_dependency_install.dependency_hash IS '规范化依赖声明SHA-256';
COMMENT ON COLUMN agent_skill_dependency_install.status IS '安装状态：排队、运行中、成功、失败、阻断或无需安装';
COMMENT ON COLUMN agent_skill_dependency_install.attempt_no IS '安装尝试次数';
COMMENT ON COLUMN agent_skill_dependency_install.requested_by IS '发起安装的用户ID';
COMMENT ON COLUMN agent_skill_dependency_install.requested_at IS '发起时间';
COMMENT ON COLUMN agent_skill_dependency_install.completed_at IS '完成时间';
COMMENT ON COLUMN agent_skill_dependency_install.install_root IS '依赖缓存相对路径，不保存主机绝对路径';
COMMENT ON COLUMN agent_skill_dependency_install.message IS '有界诊断信息';

COMMIT;
