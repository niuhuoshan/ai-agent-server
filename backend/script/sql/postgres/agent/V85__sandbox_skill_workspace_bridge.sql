-- agent platform schema V85: immutable Skill manifest and job-scoped Runner bridge

BEGIN;

ALTER TABLE agent_sandbox_job
    ADD COLUMN IF NOT EXISTS workspace_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS skill_manifest_json JSONB NOT NULL
        DEFAULT '{"skills":[],"version":1}'::jsonb,
    ADD COLUMN IF NOT EXISTS skill_manifest_hash CHAR(64)
        NOT NULL DEFAULT encode(
            sha256(convert_to('{"skills":[],"version":1}', 'UTF8')),
            'hex'
        );

ALTER TABLE agent_sandbox_job
    ALTER COLUMN skill_manifest_json
        SET DEFAULT '{"skills":[],"version":1}'::jsonb,
    ALTER COLUMN skill_manifest_hash
        SET DEFAULT encode(
            sha256(convert_to('{"skills":[],"version":1}', 'UTF8')),
            'hex'
        );

-- Early bridge drafts stored the manifest as a bare array. Normalize those rows to the
-- versioned object consumed by both the platform and Runner before installing the check.
UPDATE agent_sandbox_job
SET skill_manifest_json = jsonb_build_object(
        'skills', skill_manifest_json,
        'version', 1
    ),
    skill_manifest_hash = encode(
        sha256(convert_to(
            CASE WHEN jsonb_array_length(skill_manifest_json) = 0
                THEN '{"skills":[],"version":1}'
                ELSE jsonb_build_object('skills', skill_manifest_json, 'version', 1)::text
            END,
            'UTF8'
        )),
        'hex'
    )
WHERE jsonb_typeof(skill_manifest_json) = 'array';

UPDATE agent_sandbox_job
SET skill_manifest_hash = encode(
    sha256(convert_to(skill_manifest_json::text, 'UTF8')), 'hex'
)
WHERE skill_manifest_hash IS NULL
   OR skill_manifest_hash = repeat('0', 64);

-- Keep the no-Skill representation byte-for-byte equal to the Java canonical encoder.  Empty
-- manifests do not need a workspace key and are deliberately bypassed by the Runner bridge.
UPDATE agent_sandbox_job
SET skill_manifest_hash = encode(
    sha256(convert_to('{"skills":[],"version":1}', 'UTF8')), 'hex'
)
WHERE jsonb_typeof(skill_manifest_json) = 'object'
  AND jsonb_typeof(skill_manifest_json -> 'skills') = 'array'
  AND jsonb_array_length(skill_manifest_json -> 'skills') = 0;

UPDATE agent_sandbox_job
SET skill_manifest_json = '{"skills":[],"version":1}'::jsonb
WHERE skill_manifest_json IS NULL;

UPDATE agent_sandbox_job
SET skill_manifest_hash = encode(
    sha256(convert_to('{"skills":[],"version":1}', 'UTF8')), 'hex'
)
WHERE skill_manifest_hash IS NULL;

ALTER TABLE agent_sandbox_job
    ALTER COLUMN skill_manifest_json SET NOT NULL,
    ALTER COLUMN skill_manifest_hash SET NOT NULL;

ALTER TABLE agent_sandbox_job
    DROP CONSTRAINT IF EXISTS ck_agent_sandbox_job_skill_manifest;

ALTER TABLE agent_sandbox_job
    ADD CONSTRAINT ck_agent_sandbox_job_skill_manifest CHECK (
        (workspace_key IS NULL OR workspace_key ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')
        AND jsonb_typeof(skill_manifest_json) = 'object'
        AND skill_manifest_json @> '{"version":1}'::jsonb
        AND jsonb_typeof(skill_manifest_json -> 'skills') = 'array'
        AND skill_manifest_hash ~ '^[0-9a-f]{64}$'
    );

CREATE INDEX IF NOT EXISTS idx_agent_sandbox_job_skill_manifest
    ON agent_sandbox_job (skill_manifest_hash)
    WHERE jsonb_array_length(skill_manifest_json -> 'skills') > 0;

COMMENT ON COLUMN agent_sandbox_job.workspace_key IS '应用运行工作区的不透明标识，不接受路径语义';
COMMENT ON COLUMN agent_sandbox_job.skill_manifest_json IS '领取时冻结的 Skill 资源版本、文件包哈希和运行依赖声明';
COMMENT ON COLUMN agent_sandbox_job.skill_manifest_hash IS 'Skill manifest 的规范 JSON SHA256，Runner 下载归档时必须匹配';

COMMIT;
