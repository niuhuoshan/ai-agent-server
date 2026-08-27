-- agent platform schema V76: durable personal Skill publication and immutable review snapshots

BEGIN;

CREATE TABLE IF NOT EXISTS agent_skill_publication (
    id                          BIGINT PRIMARY KEY,
    source_skill_id             BIGINT NOT NULL,
    source_owner_id             BIGINT NOT NULL,
    system_skill_id             BIGINT,
    current_public_version_no   INTEGER,
    status                      VARCHAR(16) NOT NULL DEFAULT 'unpublished',
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_skill_publication_source UNIQUE (source_skill_id),
    CONSTRAINT ck_agent_skill_publication_status CHECK (
        status IN ('unpublished', 'pending', 'published', 'rejected', 'withdrawn')
    ),
    CONSTRAINT ck_agent_skill_publication_public_version CHECK (
        current_public_version_no IS NULL OR current_public_version_no > 0
    )
);

CREATE TABLE IF NOT EXISTS agent_skill_publication_version (
    id                          BIGINT PRIMARY KEY,
    publication_id              BIGINT NOT NULL,
    version_no                  INTEGER NOT NULL,
    source_skill_version_id     BIGINT NOT NULL,
    source_skill_key_snapshot   VARCHAR(128) NOT NULL,
    name_snapshot               VARCHAR(128) NOT NULL,
    description_snapshot        TEXT,
    content_snapshot            TEXT NOT NULL,
    manifest_json               JSONB NOT NULL DEFAULT '{}'::jsonb,
    runtime_requirements_json   JSONB NOT NULL DEFAULT '{}'::jsonb,
    status                      VARCHAR(16) NOT NULL DEFAULT 'pending',
    content_hash                CHAR(64) NOT NULL,
    file_bundle_hash            CHAR(64) NOT NULL,
    file_count                  INTEGER NOT NULL,
    total_size_bytes            BIGINT NOT NULL,
    submitted_by                BIGINT NOT NULL,
    submitted_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by                 BIGINT,
    reviewed_at                 TIMESTAMP,
    review_comment              VARCHAR(2000),
    withdrawn_by                BIGINT,
    withdrawn_at                TIMESTAMP,
    published_system_skill_id   BIGINT,
    published_system_version_id BIGINT,
    CONSTRAINT uk_agent_skill_publication_version UNIQUE (publication_id, version_no),
    CONSTRAINT ck_agent_skill_publication_version_no CHECK (version_no > 0),
    CONSTRAINT ck_agent_skill_publication_version_status CHECK (
        status IN ('pending', 'approved', 'rejected', 'withdrawn', 'superseded')
    ),
    CONSTRAINT ck_agent_skill_publication_file_count CHECK (file_count > 0 AND file_count <= 256),
    CONSTRAINT ck_agent_skill_publication_total_size CHECK (
        total_size_bytes >= 0 AND total_size_bytes <= 33554432
    )
);

CREATE TABLE IF NOT EXISTS agent_skill_publication_file (
    id                          BIGINT PRIMARY KEY,
    publication_version_id      BIGINT NOT NULL,
    path                        VARCHAR(512) NOT NULL,
    file_kind                   VARCHAR(16) NOT NULL DEFAULT 'file',
    content                     TEXT,
    content_bytes               BYTEA,
    content_encoding            VARCHAR(32) NOT NULL DEFAULT 'utf8',
    content_hash                CHAR(64) NOT NULL,
    size_bytes                  INTEGER NOT NULL,
    CONSTRAINT uk_agent_skill_publication_file UNIQUE (publication_version_id, path),
    CONSTRAINT ck_agent_skill_publication_file_kind CHECK (file_kind IN ('file', 'directory')),
    CONSTRAINT ck_agent_skill_publication_file_size CHECK (size_bytes >= 0 AND size_bytes <= 5242880),
    CONSTRAINT ck_agent_skill_publication_file_content CHECK (
        (file_kind = 'directory' AND content IS NULL AND content_bytes IS NULL)
        OR (file_kind = 'file' AND content IS NOT NULL AND content_bytes IS NULL AND content_encoding = 'utf8')
        OR (file_kind = 'file' AND content IS NULL AND content_bytes IS NOT NULL AND content_encoding = 'binary')
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_skill_publication_pending
    ON agent_skill_publication_version (publication_id)
    WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_agent_skill_publication_status
    ON agent_skill_publication (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_skill_publication_version_status
    ON agent_skill_publication_version (status, submitted_at ASC, id ASC);
CREATE INDEX IF NOT EXISTS idx_agent_skill_publication_file_version
    ON agent_skill_publication_file (publication_version_id, path);

COMMENT ON TABLE agent_skill_publication IS '个人技能向系统技能发布的聚合记录';
COMMENT ON COLUMN agent_skill_publication.id IS '发布聚合记录ID';
COMMENT ON COLUMN agent_skill_publication.source_skill_id IS '来源个人技能ID';
COMMENT ON COLUMN agent_skill_publication.source_owner_id IS '来源个人技能所有者用户ID';
COMMENT ON COLUMN agent_skill_publication.system_skill_id IS '审核通过后生成的系统技能ID';
COMMENT ON COLUMN agent_skill_publication.current_public_version_no IS '当前已公开的发布申请版本号';
COMMENT ON COLUMN agent_skill_publication.status IS '聚合发布状态';
COMMENT ON COLUMN agent_skill_publication.created_at IS '聚合记录创建时间';
COMMENT ON COLUMN agent_skill_publication.updated_at IS '聚合记录更新时间';

COMMENT ON TABLE agent_skill_publication_version IS '个人技能发布申请的不可变版本快照';
COMMENT ON COLUMN agent_skill_publication_version.id IS '发布申请版本ID';
COMMENT ON COLUMN agent_skill_publication_version.publication_id IS '所属发布聚合记录ID';
COMMENT ON COLUMN agent_skill_publication_version.version_no IS '发布申请递增版本号';
COMMENT ON COLUMN agent_skill_publication_version.source_skill_version_id IS '提交时来源个人技能版本ID';
COMMENT ON COLUMN agent_skill_publication_version.source_skill_key_snapshot IS '提交时技能稳定标识快照';
COMMENT ON COLUMN agent_skill_publication_version.name_snapshot IS '提交时技能名称快照';
COMMENT ON COLUMN agent_skill_publication_version.description_snapshot IS '提交时技能描述快照';
COMMENT ON COLUMN agent_skill_publication_version.content_snapshot IS '提交时主指令内容快照';
COMMENT ON COLUMN agent_skill_publication_version.manifest_json IS '提交时技能清单JSON快照';
COMMENT ON COLUMN agent_skill_publication_version.runtime_requirements_json IS '提交时运行要求JSON快照';
COMMENT ON COLUMN agent_skill_publication_version.status IS '申请版本状态';
COMMENT ON COLUMN agent_skill_publication_version.content_hash IS '主指令、清单和运行要求的SHA-256';
COMMENT ON COLUMN agent_skill_publication_version.file_bundle_hash IS '完整文件包按路径和文件哈希计算的SHA-256';
COMMENT ON COLUMN agent_skill_publication_version.file_count IS '快照普通文件数量';
COMMENT ON COLUMN agent_skill_publication_version.total_size_bytes IS '快照普通文件总字节数';
COMMENT ON COLUMN agent_skill_publication_version.submitted_by IS '提交人用户ID';
COMMENT ON COLUMN agent_skill_publication_version.submitted_at IS '提交时间';
COMMENT ON COLUMN agent_skill_publication_version.reviewed_by IS '审核人用户ID';
COMMENT ON COLUMN agent_skill_publication_version.reviewed_at IS '审核时间';
COMMENT ON COLUMN agent_skill_publication_version.review_comment IS '审核意见';
COMMENT ON COLUMN agent_skill_publication_version.withdrawn_by IS '撤回人用户ID';
COMMENT ON COLUMN agent_skill_publication_version.withdrawn_at IS '撤回时间';
COMMENT ON COLUMN agent_skill_publication_version.published_system_skill_id IS '本申请生成的系统技能ID';
COMMENT ON COLUMN agent_skill_publication_version.published_system_version_id IS '本申请生成的系统技能版本ID';

COMMENT ON TABLE agent_skill_publication_file IS '发布申请版本的不可变文件内容快照';
COMMENT ON COLUMN agent_skill_publication_file.id IS '发布文件快照ID';
COMMENT ON COLUMN agent_skill_publication_file.publication_version_id IS '所属发布申请版本ID';
COMMENT ON COLUMN agent_skill_publication_file.path IS '相对技能根目录的POSIX路径';
COMMENT ON COLUMN agent_skill_publication_file.file_kind IS '文件类型：普通文件或目录';
COMMENT ON COLUMN agent_skill_publication_file.content IS 'UTF-8文本文件内容';
COMMENT ON COLUMN agent_skill_publication_file.content_bytes IS '二进制文件原始字节内容';
COMMENT ON COLUMN agent_skill_publication_file.content_encoding IS '内容编码：utf8或binary';
COMMENT ON COLUMN agent_skill_publication_file.content_hash IS '文件内容SHA-256';
COMMENT ON COLUMN agent_skill_publication_file.size_bytes IS '文件字节数';

COMMIT;
