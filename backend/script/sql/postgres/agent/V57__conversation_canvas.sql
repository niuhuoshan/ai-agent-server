-- agent platform schema V57: private conversation canvas persistence and immutable history

BEGIN;

CREATE TABLE IF NOT EXISTS agent_conversation_canvas (
    id                  BIGINT PRIMARY KEY,
    conversation_id     BIGINT NOT NULL,
    owner_id            BIGINT NOT NULL,
    title               VARCHAR(255) NOT NULL,
    canvas_type         VARCHAR(32) NOT NULL,
    current_version_no  INTEGER NOT NULL DEFAULT 1,
    revision_no         INTEGER NOT NULL DEFAULT 1,
    metadata_json       JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_size        BIGINT NOT NULL,
    content_sha256      CHAR(64) NOT NULL,
    create_by           BIGINT NOT NULL,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    CONSTRAINT ck_agent_conversation_canvas_type CHECK (
        canvas_type IN ('markdown', 'html', 'code', 'mermaid', 'pdf', 'csv', 'image', 'compare')
    ),
    CONSTRAINT ck_agent_conversation_canvas_revision CHECK (
        current_version_no >= 1 AND revision_no >= 1
    ),
    CONSTRAINT ck_agent_conversation_canvas_size CHECK (content_size BETWEEN 1 AND 10485760),
    CONSTRAINT ck_agent_conversation_canvas_del_flag CHECK (del_flag IN ('0', '1'))
);

CREATE INDEX IF NOT EXISTS idx_agent_conversation_canvas_owner_conversation
    ON agent_conversation_canvas (owner_id, conversation_id, del_flag, update_time DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_agent_conversation_canvas_owner_update
    ON agent_conversation_canvas (owner_id, update_time DESC, id DESC)
    WHERE del_flag = '0';

CREATE TABLE IF NOT EXISTS agent_conversation_canvas_version (
    id                  BIGINT PRIMARY KEY,
    canvas_id           BIGINT NOT NULL,
    version_no          INTEGER NOT NULL,
    title               VARCHAR(255) NOT NULL,
    canvas_type         VARCHAR(32) NOT NULL,
    content             TEXT NOT NULL,
    metadata_json       JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_size        BIGINT NOT NULL,
    content_sha256      CHAR(64) NOT NULL,
    change_type         VARCHAR(32) NOT NULL,
    source_version_no   INTEGER,
    created_by          BIGINT NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_conversation_canvas_version UNIQUE (canvas_id, version_no),
    CONSTRAINT ck_agent_conversation_canvas_version_type CHECK (
        canvas_type IN ('markdown', 'html', 'code', 'mermaid', 'pdf', 'csv', 'image', 'compare')
    ),
    CONSTRAINT ck_agent_conversation_canvas_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_agent_conversation_canvas_version_source CHECK (
        source_version_no IS NULL OR source_version_no >= 1
    ),
    CONSTRAINT ck_agent_conversation_canvas_version_size CHECK (content_size BETWEEN 1 AND 10485760),
    CONSTRAINT ck_agent_conversation_canvas_version_change CHECK (
        change_type IN ('created', 'updated', 'restored')
    )
);

CREATE INDEX IF NOT EXISTS idx_agent_conversation_canvas_version_canvas
    ON agent_conversation_canvas_version (canvas_id, version_no DESC);

CREATE OR REPLACE FUNCTION agent_prevent_canvas_version_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Canvas versions are immutable';
END;
$$;

DROP TRIGGER IF EXISTS trg_agent_canvas_version_immutable
    ON agent_conversation_canvas_version;
CREATE TRIGGER trg_agent_canvas_version_immutable
    BEFORE UPDATE OR DELETE ON agent_conversation_canvas_version
    FOR EACH ROW EXECUTE FUNCTION agent_prevent_canvas_version_mutation();

COMMENT ON TABLE agent_conversation_canvas IS '个人会话画布当前状态表';
COMMENT ON COLUMN agent_conversation_canvas.id IS '画布主键ID';
COMMENT ON COLUMN agent_conversation_canvas.conversation_id IS '所属个人会话ID';
COMMENT ON COLUMN agent_conversation_canvas.owner_id IS '会话和画布所有者用户ID';
COMMENT ON COLUMN agent_conversation_canvas.title IS '画布标题';
COMMENT ON COLUMN agent_conversation_canvas.canvas_type IS '画布类型：markdown、html、code、mermaid、pdf、csv、image或compare';
COMMENT ON COLUMN agent_conversation_canvas.current_version_no IS '当前不可变版本序号';
COMMENT ON COLUMN agent_conversation_canvas.revision_no IS '乐观锁修订号';
COMMENT ON COLUMN agent_conversation_canvas.metadata_json IS '当前画布受限元数据JSON';
COMMENT ON COLUMN agent_conversation_canvas.content_size IS '当前内容UTF-8字节数';
COMMENT ON COLUMN agent_conversation_canvas.content_sha256 IS '当前内容SHA256摘要';
COMMENT ON COLUMN agent_conversation_canvas.create_by IS '创建人用户ID';
COMMENT ON COLUMN agent_conversation_canvas.create_time IS '创建时间';
COMMENT ON COLUMN agent_conversation_canvas.update_by IS '最后更新人用户ID';
COMMENT ON COLUMN agent_conversation_canvas.update_time IS '最后更新时间';
COMMENT ON COLUMN agent_conversation_canvas.del_flag IS '逻辑删除标志：0正常，1删除';

COMMENT ON TABLE agent_conversation_canvas_version IS '个人会话画布不可变版本表';
COMMENT ON COLUMN agent_conversation_canvas_version.id IS '版本主键ID';
COMMENT ON COLUMN agent_conversation_canvas_version.canvas_id IS '所属画布ID';
COMMENT ON COLUMN agent_conversation_canvas_version.version_no IS '画布版本序号';
COMMENT ON COLUMN agent_conversation_canvas_version.title IS '该版本画布标题';
COMMENT ON COLUMN agent_conversation_canvas_version.canvas_type IS '该版本画布类型';
COMMENT ON COLUMN agent_conversation_canvas_version.content IS '该版本完整画布内容';
COMMENT ON COLUMN agent_conversation_canvas_version.metadata_json IS '该版本受限元数据JSON';
COMMENT ON COLUMN agent_conversation_canvas_version.content_size IS '该版本内容UTF-8字节数';
COMMENT ON COLUMN agent_conversation_canvas_version.content_sha256 IS '该版本内容SHA256摘要';
COMMENT ON COLUMN agent_conversation_canvas_version.change_type IS '版本产生方式：创建、更新或恢复';
COMMENT ON COLUMN agent_conversation_canvas_version.source_version_no IS '恢复操作引用的历史版本序号';
COMMENT ON COLUMN agent_conversation_canvas_version.created_by IS '版本创建人用户ID';
COMMENT ON COLUMN agent_conversation_canvas_version.created_at IS '版本创建时间';

COMMIT;
