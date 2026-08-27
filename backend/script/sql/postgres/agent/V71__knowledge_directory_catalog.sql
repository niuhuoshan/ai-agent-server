-- Local knowledge directory tree and document catalog metadata revision.

BEGIN;

CREATE TABLE IF NOT EXISTS agent_knowledge_directory (
    id                  BIGINT PRIMARY KEY,
    knowledge_base_id   BIGINT NOT NULL,
    parent_id           BIGINT,
    directory_key       VARCHAR(128) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    revision_no         BIGINT NOT NULL DEFAULT 1,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          BIGINT,
    updated_at          TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    CONSTRAINT ck_agent_knowledge_directory_parent
        CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_agent_knowledge_directory_name
        CHECK (
            name = btrim(name) AND name <> '' AND name NOT IN ('.', '..')
            AND position('/' IN name) = 0 AND position(chr(92) IN name) = 0
            AND name !~ '[[:cntrl:]]'
        ),
    CONSTRAINT ck_agent_knowledge_directory_revision CHECK (revision_no > 0),
    CONSTRAINT ck_agent_knowledge_directory_del_flag CHECK (del_flag IN ('0', '1'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_knowledge_directory_key_active
    ON agent_knowledge_directory (knowledge_base_id, directory_key)
    WHERE del_flag = '0';
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_knowledge_directory_sibling_name_active
    ON agent_knowledge_directory (
        knowledge_base_id, COALESCE(parent_id, 0::BIGINT), lower(name)
    )
    WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_knowledge_directory_parent
    ON agent_knowledge_directory (knowledge_base_id, parent_id, lower(name), id)
    WHERE del_flag = '0';

ALTER TABLE agent_knowledge_document
    ADD COLUMN IF NOT EXISTS directory_id BIGINT,
    ADD COLUMN IF NOT EXISTS catalog_revision_no BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS tags_json JSONB NOT NULL DEFAULT '[]'::JSONB,
    ADD COLUMN IF NOT EXISTS remark TEXT;

UPDATE agent_knowledge_document SET tags_json = '[]'::JSONB WHERE tags_json IS NULL;
ALTER TABLE agent_knowledge_document ALTER COLUMN tags_json SET DEFAULT '[]'::JSONB;
ALTER TABLE agent_knowledge_document ALTER COLUMN tags_json SET NOT NULL;

ALTER TABLE agent_knowledge_document
    DROP CONSTRAINT IF EXISTS ck_agent_knowledge_document_catalog_revision;
ALTER TABLE agent_knowledge_document
    ADD CONSTRAINT ck_agent_knowledge_document_catalog_revision
    CHECK (catalog_revision_no > 0);
ALTER TABLE agent_knowledge_document
    DROP CONSTRAINT IF EXISTS ck_agent_knowledge_document_tags;
ALTER TABLE agent_knowledge_document
    ADD CONSTRAINT ck_agent_knowledge_document_tags
    CHECK (jsonb_typeof(tags_json) = 'array' AND jsonb_array_length(tags_json) <= 32);
ALTER TABLE agent_knowledge_document
    DROP CONSTRAINT IF EXISTS ck_agent_knowledge_document_remark;
ALTER TABLE agent_knowledge_document
    ADD CONSTRAINT ck_agent_knowledge_document_remark
    CHECK (remark IS NULL OR length(remark) <= 4000);

CREATE INDEX IF NOT EXISTS idx_agent_knowledge_document_directory
    ON agent_knowledge_document (knowledge_base_id, directory_id, updated_at DESC, id DESC)
    WHERE del_flag = '0' AND status <> 'deleted';

COMMENT ON TABLE agent_knowledge_directory IS '知识库虚拟目录表';
COMMENT ON COLUMN agent_knowledge_directory.id IS '虚拟目录主键ID';
COMMENT ON COLUMN agent_knowledge_directory.knowledge_base_id IS '所属知识库ID';
COMMENT ON COLUMN agent_knowledge_directory.parent_id IS '父目录ID，空值表示知识库根目录';
COMMENT ON COLUMN agent_knowledge_directory.directory_key IS '目录稳定业务标识';
COMMENT ON COLUMN agent_knowledge_directory.name IS '目录显示名称';
COMMENT ON COLUMN agent_knowledge_directory.revision_no IS '目录乐观并发修订号';
COMMENT ON COLUMN agent_knowledge_directory.created_by IS '创建人用户ID';
COMMENT ON COLUMN agent_knowledge_directory.created_at IS '创建时间';
COMMENT ON COLUMN agent_knowledge_directory.updated_by IS '最后更新人用户ID';
COMMENT ON COLUMN agent_knowledge_directory.updated_at IS '最后更新时间';
COMMENT ON COLUMN agent_knowledge_directory.del_flag IS '逻辑删除标志：0正常，1删除';
COMMENT ON COLUMN agent_knowledge_document.directory_id IS '所属虚拟目录ID，空值表示知识库根目录';
COMMENT ON COLUMN agent_knowledge_document.catalog_revision_no IS '文档目录、名称、标签和备注的乐观并发修订号';
COMMENT ON COLUMN agent_knowledge_document.tags_json IS '文档分类标签JSON数组';
COMMENT ON COLUMN agent_knowledge_document.remark IS '文档运营备注';

COMMIT;
