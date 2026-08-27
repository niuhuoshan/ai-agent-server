package group.aitools.nhs.platform.iam.domain;

/**
 * 定义资源State相关的可选值。
 * Coarse resource lifecycle used before capability evaluation. */
public enum ResourceState {
    ACTIVE,
    INACTIVE,
    REVOKED,
    DELETED
}
