-- agent platform schema V29: immutable first-phase multi-agent templates

BEGIN;

ALTER TABLE agent_run_step
    ADD COLUMN IF NOT EXISTS role_key VARCHAR(64),
    ADD COLUMN IF NOT EXISTS depends_on_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS runtime_template_json JSONB,
    ADD COLUMN IF NOT EXISTS runtime_snapshot_json JSONB,
    ADD COLUMN IF NOT EXISTS authorization_snapshot_json JSONB,
    ADD COLUMN IF NOT EXISTS wait_reason VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_workflow_definition_key
    ON agent_workflow_definition (workflow_key)
    WHERE del_flag = '0';

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_run_step_key
    ON agent_run_step (run_id, step_key);

CREATE INDEX IF NOT EXISTS idx_agent_run_step_ready
    ON agent_run_step (run_id, status, sequence_no);

ALTER TABLE agent_run_step
    DROP CONSTRAINT IF EXISTS ck_agent_run_step_dependencies;

ALTER TABLE agent_run_step
    ADD CONSTRAINT ck_agent_run_step_dependencies
    CHECK (jsonb_typeof(depends_on_json) = 'array');

COMMENT ON COLUMN agent_run_step.role_key IS
    'Published workflow role resolved to one frozen Agent version for this run';
COMMENT ON COLUMN agent_run_step.depends_on_json IS
    'Frozen workflow step keys that must succeed before this step can run';
COMMENT ON COLUMN agent_run_step.runtime_template_json IS
    'Frozen Agent/model/resource definition before dependency outputs are attached';
COMMENT ON COLUMN agent_run_step.runtime_snapshot_json IS
    'Exact materialized request used for execution and resume';
COMMENT ON COLUMN agent_run_step.authorization_snapshot_json IS
    'Deny-first authorization evidence frozen for this workflow step';

CREATE OR REPLACE FUNCTION agent_guard_published_workflow_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.status = 'published' THEN
        RAISE EXCEPTION 'published workflow versions are immutable';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'published' AND (
        NEW.workflow_id IS DISTINCT FROM OLD.workflow_id
        OR NEW.version_no IS DISTINCT FROM OLD.version_no
        OR NEW.graph_json IS DISTINCT FROM OLD.graph_json
        OR NEW.runtime_policy_json IS DISTINCT FROM OLD.runtime_policy_json
        OR NEW.content_hash IS DISTINCT FROM OLD.content_hash
        OR NEW.published_at IS DISTINCT FROM OLD.published_at
    ) THEN
        RAISE EXCEPTION 'published workflow version content is immutable';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

DROP TRIGGER IF EXISTS trg_agent_workflow_version_immutable
    ON agent_workflow_version;

CREATE TRIGGER trg_agent_workflow_version_immutable
BEFORE UPDATE OR DELETE ON agent_workflow_version
FOR EACH ROW EXECUTE FUNCTION agent_guard_published_workflow_version();

INSERT INTO agent_workflow_definition (
    id, workflow_key, name, workflow_type, status, owner_id,
    create_by, create_time, del_flag, extra_json
) VALUES
    (900000000000029001, 'supervisor_executor', 'Supervisor + Executor',
     'fixed_template', 'active', NULL, 1, CURRENT_TIMESTAMP, '0',
     '{"systemTemplate":true,"editable":false}'::jsonb),
    (900000000000029002, 'delivery_team', 'Backend + Frontend + Test',
     'fixed_template', 'active', NULL, 1, CURRENT_TIMESTAMP, '0',
     '{"systemTemplate":true,"editable":false}'::jsonb)
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_workflow_version (
    id, workflow_id, version_no, graph_json, runtime_policy_json,
    content_hash, status, published_at, created_by, created_at
) VALUES
    (
        900000000000029101,
        900000000000029001,
        1,
        '{"maxParallelism":1,"nodes":[{"dependsOn":[],"instruction":"Plan the task into concrete, authorized work items.","key":"supervisor_plan","role":"supervisor","sequence":1,"type":"agent"},{"dependsOn":["supervisor_plan"],"instruction":"Execute the approved plan and produce concrete deliverables.","key":"executor","role":"executor","sequence":2,"type":"agent"},{"dependsOn":["executor"],"instruction":"Review the execution output, identify gaps, and prepare the acceptance summary.","key":"supervisor_review","role":"supervisor","sequence":3,"type":"agent"}],"roles":[{"key":"supervisor","name":"Supervisor"},{"key":"executor","name":"Executor"}],"schemaVersion":1,"templateKey":"supervisor_executor"}'::jsonb,
        '{"failFast":true,"maxDependencyBytes":65536,"maxParallelism":1}'::jsonb,
        '8dbff4a45e6a6c4f91aa5305ff03293ba0fd1f4914e80979b075976495e16675',
        'published', CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP
    ),
    (
        900000000000029102,
        900000000000029002,
        1,
        '{"maxParallelism":3,"nodes":[{"dependsOn":[],"instruction":"Implement backend APIs, OpenAPI definitions, and backend tests.","key":"backend","role":"backend","sequence":1,"type":"agent"},{"dependsOn":[],"instruction":"Implement the frontend UI and integrate the frozen API contract.","key":"frontend","role":"frontend","sequence":2,"type":"agent"},{"dependsOn":[],"instruction":"Produce contract, end-to-end, and regression verification evidence.","key":"test","role":"test","sequence":3,"type":"agent"},{"dependsOn":["backend","frontend","test"],"instruction":"Collect bounded branch outputs for human acceptance.","key":"summary","sequence":4,"type":"aggregate"}],"roles":[{"key":"backend","name":"Backend Agent"},{"key":"frontend","name":"Frontend Agent"},{"key":"test","name":"Test Agent"}],"schemaVersion":1,"templateKey":"delivery_team"}'::jsonb,
        '{"failFast":true,"maxDependencyBytes":65536,"maxParallelism":3}'::jsonb,
        '501c412378ba0da0fca9c6cc216e6d782b631be143659638137b1c645cf63622',
        'published', CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP
    )
ON CONFLICT (id) DO NOTHING;

COMMIT;
