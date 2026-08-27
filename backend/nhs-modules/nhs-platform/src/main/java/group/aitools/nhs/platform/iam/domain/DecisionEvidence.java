package group.aitools.nhs.platform.iam.domain;

/**
 * 封装{@code DecisionEvidence}相关的不可变数据。
 * Auditable explanation item attached to a decision. */
public record DecisionEvidence(
    PermissionSource source,
    String sourceReference,
    PermissionEffect effect,
    String reason
) {
}
