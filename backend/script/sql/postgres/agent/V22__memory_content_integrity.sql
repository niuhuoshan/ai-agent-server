-- Backfill legacy memory integrity data before enforcing frozen-runtime requirements.

UPDATE agent_memory
SET content_hash = encode(sha256(convert_to(content, 'UTF8')), 'hex')
WHERE content_hash IS NULL;

ALTER TABLE agent_memory ALTER COLUMN content_hash SET NOT NULL;
ALTER TABLE agent_memory DROP CONSTRAINT IF EXISTS ck_agent_memory_content_hash;
ALTER TABLE agent_memory ADD CONSTRAINT ck_agent_memory_content_hash
    CHECK (content_hash ~ '^[0-9a-f]{64}$');

COMMENT ON CONSTRAINT ck_agent_memory_content_hash ON agent_memory
    IS '记忆正文必须具备小写SHA-256完整性哈希';
