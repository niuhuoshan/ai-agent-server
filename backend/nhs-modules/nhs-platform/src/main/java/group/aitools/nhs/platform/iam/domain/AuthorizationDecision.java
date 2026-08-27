package group.aitools.nhs.platform.iam.domain;

import java.util.List;
import java.util.Objects;

/**
 * 封装授权Decision相关的不可变数据。
 * Final authorization result returned to every platform entry point. */
public record AuthorizationDecision(
    PermissionEffect effect,
    String reasonCode,
    String reason,
    List<DecisionEvidence> evidence
) {

    /**
     * 创建 {@code AuthorizationDecision} 实例并初始化所需依赖。
     *
     * @param effect {@code effect}参数
     * @param reasonCode {@code reasonCode}参数
     * @param reason {@code reason}参数
     * @param evidence {@code evidence}参数
     */
    public AuthorizationDecision {
        Objects.requireNonNull(effect, "effect must not be null");
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        reason = reason == null ? "" : reason;
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
    }

    /**
     * 处理{@code allowed}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean allowed() {
        return effect == PermissionEffect.ALLOW;
    }

    /**
     * 校验审批，并在条件不满足时终止处理。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean requiresApproval() {
        return effect == PermissionEffect.APPROVAL_REQUIRED;
    }
}
