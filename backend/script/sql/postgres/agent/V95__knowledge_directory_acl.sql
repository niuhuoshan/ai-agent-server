BEGIN;

CREATE TABLE IF NOT EXISTS agent_knowledge_directory_acl (
    id                  BIGINT PRIMARY KEY,
    knowledge_base_id   BIGINT NOT NULL,
    directory_id        BIGINT,
    user_id             BIGINT NOT NULL,
    permission          VARCHAR(16) NOT NULL,
    effect              VARCHAR(16) NOT NULL,
    inherit_children    BOOLEAN NOT NULL DEFAULT TRUE,
    revision_no         BIGINT NOT NULL DEFAULT 1,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    created_by          BIGINT NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    updated_by          BIGINT,
    updated_at          TIMESTAMP,
    CONSTRAINT ck_agent_knowledge_directory_acl_permission
        CHECK (permission IN ('read', 'write')),
    CONSTRAINT ck_agent_knowledge_directory_acl_effect
        CHECK (effect IN ('allow', 'deny')),
    CONSTRAINT ck_agent_knowledge_directory_acl_status
        CHECK (status IN ('active', 'revoked')),
    CONSTRAINT ck_agent_knowledge_directory_acl_revision
        CHECK (revision_no > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_knowledge_directory_acl_target
    ON agent_knowledge_directory_acl (
        knowledge_base_id, COALESCE(directory_id, 0), user_id, permission
    ) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_agent_knowledge_directory_acl_lookup
    ON agent_knowledge_directory_acl (knowledge_base_id, user_id, status, permission);
CREATE INDEX IF NOT EXISTS idx_agent_knowledge_directory_acl_directory
    ON agent_knowledge_directory_acl (directory_id, status);

COMMENT ON TABLE agent_knowledge_directory_acl IS '知识库目录及根目录的用户 ACL；deny 优先，子目录默认继承';
COMMENT ON COLUMN agent_knowledge_directory_acl.directory_id IS '空值表示知识库根目录';
COMMENT ON COLUMN agent_knowledge_directory_acl.inherit_children IS '是否对子目录和其文档生效';

COMMIT;
