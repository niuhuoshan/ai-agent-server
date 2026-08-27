-- Nhs-compatible file-backed Skill bundles. Content is versioned and immutable after publish.
BEGIN;

CREATE TABLE IF NOT EXISTS agent_skill_file (
    id              BIGINT PRIMARY KEY,
    skill_id        BIGINT NOT NULL,
    version_id      BIGINT NOT NULL,
    path            VARCHAR(512) NOT NULL,
    file_kind       VARCHAR(16) NOT NULL DEFAULT 'file',
    content         TEXT NOT NULL,
    content_hash    CHAR(64) NOT NULL,
    size_bytes      INTEGER NOT NULL,
    create_by       BIGINT,
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT,
    update_time     TIMESTAMP,
    del_flag        CHAR(1) NOT NULL DEFAULT '0',
    CONSTRAINT uk_agent_skill_file_path UNIQUE (version_id, path),
    CONSTRAINT ck_agent_skill_file_kind CHECK (file_kind IN ('file', 'directory')),
    CONSTRAINT ck_agent_skill_file_size CHECK (size_bytes >= 0 AND size_bytes <= 5242880)
);

CREATE INDEX IF NOT EXISTS idx_agent_skill_file_skill ON agent_skill_file (skill_id, version_id, del_flag);
COMMENT ON TABLE agent_skill_file IS '技能版本文件包清单与内容';
COMMENT ON COLUMN agent_skill_file.path IS '相对技能根目录的 POSIX 路径';
COMMENT ON COLUMN agent_skill_file.content IS '文本文件内容，二进制资产由后续对象存储 Profile 承载';

COMMIT;
