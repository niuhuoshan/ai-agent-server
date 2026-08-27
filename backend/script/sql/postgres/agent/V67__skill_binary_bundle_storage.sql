-- Preserve complete Skill bundles, including scripts, references, assets and binary files.
BEGIN;

ALTER TABLE agent_skill_file
    ALTER COLUMN content DROP NOT NULL;

ALTER TABLE agent_skill_file
    ADD COLUMN IF NOT EXISTS content_bytes BYTEA,
    ADD COLUMN IF NOT EXISTS content_encoding VARCHAR(32) NOT NULL DEFAULT 'utf8';

-- V37 stored directory entries as an empty TEXT value. Normalize them before
-- adding the text/binary consistency constraint so existing installations can
-- replay this migration without violating the new invariant.
UPDATE agent_skill_file
SET content = NULL,
    content_encoding = 'utf8'
WHERE file_kind = 'directory'
  AND content_bytes IS NULL;

ALTER TABLE agent_skill_file
    DROP CONSTRAINT IF EXISTS ck_agent_skill_file_content,
    ADD CONSTRAINT ck_agent_skill_file_content CHECK (
        (content IS NOT NULL AND content_bytes IS NULL AND content_encoding = 'utf8')
        OR (content IS NULL AND content_bytes IS NOT NULL AND content_encoding = 'binary')
        OR (file_kind = 'directory' AND content IS NULL AND content_bytes IS NULL)
    );

COMMENT ON COLUMN agent_skill_file.content IS 'UTF-8 文本文件内容；二进制文件使用 content_bytes 保存';
COMMENT ON COLUMN agent_skill_file.content_bytes IS '二进制文件原始字节内容，供脚本、参考资料和资产归档';
COMMENT ON COLUMN agent_skill_file.content_encoding IS '文件内容编码：utf8 或 binary';

COMMIT;
