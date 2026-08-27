package group.aitools.nhs.platform.iam.domain;

/**
 * 定义权限数据源相关的可选值。
 * Auditable origins of an authorization decision. */
public enum PermissionSource {
    PLATFORM_ROLE,
    PROFILE,
    USER_OVERRIDE,
    TEMPORARY_GRANT,
    SERVICE_ACCOUNT_GRANT,
    AGENT_POLICY,
    TASK_RESOURCE_SNAPSHOT,
    TASK_ACCESS_RULE,
    BUSINESS_RELATION,
    RESOURCE_STATE,
    DEFAULT_POLICY
}
