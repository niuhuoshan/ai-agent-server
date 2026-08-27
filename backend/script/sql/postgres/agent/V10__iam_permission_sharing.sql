-- agent platform schema V10: permission profiles, per-user overrides and shared-task ACLs

BEGIN;

CREATE TABLE IF NOT EXISTS iam_permission_profile (
    id                  BIGINT PRIMARY KEY,
    profile_key         VARCHAR(128) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    description         TEXT,
    profile_type        VARCHAR(16) NOT NULL DEFAULT 'custom',
    version_no          INTEGER NOT NULL DEFAULT 1,
    status              VARCHAR(16) NOT NULL DEFAULT 'draft',
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    CONSTRAINT uk_iam_permission_profile UNIQUE (profile_key, version_no),
    CONSTRAINT ck_iam_permission_profile_type CHECK (profile_type IN ('system', 'custom', 'snapshot')),
    CONSTRAINT ck_iam_permission_profile_version CHECK (version_no > 0),
    CONSTRAINT ck_iam_permission_profile_status CHECK (status IN ('draft', 'published', 'archived'))
);

CREATE TABLE IF NOT EXISTS iam_permission_profile_entry (
    id                  BIGINT PRIMARY KEY,
    profile_id          BIGINT NOT NULL,
    resource_type       VARCHAR(32) NOT NULL,
    resource_id         BIGINT,
    resource_key        VARCHAR(255),
    action              VARCHAR(32) NOT NULL,
    effect              VARCHAR(24) NOT NULL DEFAULT 'allow',
    policy_json         JSONB,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_iam_profile_entry_target
        CHECK (resource_id IS NOT NULL OR NULLIF(BTRIM(resource_key), '') IS NOT NULL),
    CONSTRAINT ck_iam_profile_entry_effect CHECK (effect IN ('allow', 'deny', 'approval_required'))
);

CREATE TABLE IF NOT EXISTS iam_user_permission_binding (
    id                  BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    profile_id          BIGINT,
    profile_version     INTEGER,
    binding_type        VARCHAR(16) NOT NULL DEFAULT 'profile',
    snapshot_json       JSONB,
    source_user_id      BIGINT,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,
    CONSTRAINT ck_iam_user_binding_type CHECK (binding_type IN ('profile', 'snapshot')),
    CONSTRAINT ck_iam_user_binding_status CHECK (status IN ('active', 'inactive', 'replaced')),
    CONSTRAINT ck_iam_user_binding_version CHECK (profile_version IS NULL OR profile_version > 0),
    CONSTRAINT ck_iam_user_binding_payload CHECK (
        (binding_type = 'profile' AND profile_id IS NOT NULL AND profile_version IS NOT NULL)
        OR (binding_type = 'snapshot' AND snapshot_json IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS iam_user_permission_override (
    id                  BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    resource_type       VARCHAR(32) NOT NULL,
    resource_id         BIGINT,
    resource_key        VARCHAR(255),
    action              VARCHAR(32) NOT NULL,
    effect              VARCHAR(24) NOT NULL,
    policy_json         JSONB,
    reason              TEXT,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    expires_at          TIMESTAMP,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_iam_user_override_target
        CHECK (resource_id IS NOT NULL OR NULLIF(BTRIM(resource_key), '') IS NOT NULL),
    CONSTRAINT ck_iam_user_override_effect CHECK (effect IN ('allow', 'deny', 'approval_required')),
    CONSTRAINT ck_iam_user_override_status CHECK (status IN ('active', 'expired', 'revoked'))
);

CREATE TABLE IF NOT EXISTS iam_user_agent_policy (
    id                  BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    agent_id            BIGINT NOT NULL,
    data_scope_json     JSONB,
    tool_scope_json     JSONB,
    model_scope_json    JSONB,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,
    CONSTRAINT ck_iam_user_agent_policy_status CHECK (status IN ('active', 'inactive', 'revoked'))
);

CREATE TABLE IF NOT EXISTS iam_temporary_grant (
    id                  BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    resource_type       VARCHAR(32) NOT NULL,
    resource_id         BIGINT,
    resource_key        VARCHAR(255),
    action              VARCHAR(32) NOT NULL,
    effect              VARCHAR(24) NOT NULL DEFAULT 'allow',
    policy_json         JSONB,
    reason              TEXT NOT NULL,
    approval_id         BIGINT,
    expires_at          TIMESTAMP NOT NULL,
    revoked_at          TIMESTAMP,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_iam_temporary_grant_target
        CHECK (resource_id IS NOT NULL OR NULLIF(BTRIM(resource_key), '') IS NOT NULL),
    CONSTRAINT ck_iam_temporary_grant_effect CHECK (effect IN ('allow', 'approval_required')),
    CONSTRAINT ck_iam_temporary_grant_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_iam_temporary_grant_revoked CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE TABLE IF NOT EXISTS iam_permission_copy_record (
    id                  BIGINT PRIMARY KEY,
    source_user_id      BIGINT NOT NULL,
    target_user_id      BIGINT NOT NULL,
    source_profile_id   BIGINT,
    source_profile_version INTEGER,
    copy_mode           VARCHAR(24) NOT NULL,
    before_binding_id   BIGINT,
    after_binding_id    BIGINT,
    diff_json           JSONB NOT NULL,
    excluded_json       JSONB,
    idempotency_key     VARCHAR(128) NOT NULL,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_iam_permission_copy_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_iam_permission_copy_users CHECK (source_user_id <> target_user_id),
    CONSTRAINT ck_iam_permission_copy_version
        CHECK (source_profile_version IS NULL OR source_profile_version > 0),
    CONSTRAINT ck_iam_permission_copy_mode CHECK (copy_mode IN ('copy_base', 'append_missing', 'replace_base', 'save_template'))
);

CREATE TABLE IF NOT EXISTS task_access_rule (
    id                  BIGINT PRIMARY KEY,
    task_id             BIGINT NOT NULL,
    artifact_id         BIGINT,
    subject_type        VARCHAR(20) NOT NULL,
    subject_id          BIGINT,
    subject_key         VARCHAR(128),
    action              VARCHAR(16) NOT NULL,
    effect              VARCHAR(8) NOT NULL DEFAULT 'allow',
    expires_at          TIMESTAMP,
    revoked_at          TIMESTAMP,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_task_access_subject_type CHECK (subject_type IN ('user', 'platform_role', 'service_account')),
    CONSTRAINT ck_task_access_subject CHECK (
        (subject_type = 'platform_role' AND NULLIF(BTRIM(subject_key), '') IS NOT NULL AND subject_id IS NULL)
        OR (subject_type IN ('user', 'service_account') AND subject_id IS NOT NULL AND subject_key IS NULL)
    ),
    CONSTRAINT ck_task_access_action CHECK (action IN ('view', 'comment', 'operate', 'admin')),
    CONSTRAINT ck_task_access_effect CHECK (effect IN ('allow', 'deny')),
    CONSTRAINT ck_task_access_revoked CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX IF NOT EXISTS idx_iam_profile_status
    ON iam_permission_profile (status, profile_type, profile_key);
CREATE INDEX IF NOT EXISTS idx_iam_profile_entry_profile
    ON iam_permission_profile_entry (profile_id, resource_type, action);
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_profile_entry_target
    ON iam_permission_profile_entry (
        profile_id,
        resource_type,
        COALESCE(resource_id, 0),
        COALESCE(resource_key, ''),
        action
    );

CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_user_binding_active
    ON iam_user_permission_binding (user_id) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_iam_user_binding_profile
    ON iam_user_permission_binding (profile_id, profile_version, status)
    WHERE profile_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_user_override_active
    ON iam_user_permission_override (
        user_id,
        resource_type,
        COALESCE(resource_id, 0),
        COALESCE(resource_key, ''),
        action
    ) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_iam_user_override_expiry
    ON iam_user_permission_override (expires_at) WHERE status = 'active' AND expires_at IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_user_agent_policy_active
    ON iam_user_agent_policy (user_id, agent_id) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_iam_user_agent_policy_agent
    ON iam_user_agent_policy (agent_id, status);

CREATE INDEX IF NOT EXISTS idx_iam_temporary_grant_user_expiry
    ON iam_temporary_grant (user_id, expires_at) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_iam_temporary_grant_resource
    ON iam_temporary_grant (resource_type, resource_id, resource_key)
    WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_iam_permission_copy_target
    ON iam_permission_copy_record (target_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_iam_permission_copy_source
    ON iam_permission_copy_record (source_user_id, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uk_task_access_rule_active
    ON task_access_rule (
        task_id,
        COALESCE(artifact_id, 0),
        subject_type,
        COALESCE(subject_id, 0),
        COALESCE(subject_key, ''),
        action
    ) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_task_access_rule_lookup
    ON task_access_rule (task_id, artifact_id, action, effect)
    WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_task_access_rule_subject
    ON task_access_rule (subject_type, subject_id, subject_key)
    WHERE revoked_at IS NULL;

COMMIT;
