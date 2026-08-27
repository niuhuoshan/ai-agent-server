-- Skill file packages are versioned independently from SKILL.md metadata.
BEGIN;

ALTER TABLE agent_skill_version
    ADD COLUMN IF NOT EXISTS file_bundle_hash CHAR(64);

COMMENT ON COLUMN agent_skill_version.file_bundle_hash IS '技能版本文件包按路径和文件哈希计算的 SHA-256';

COMMIT;
