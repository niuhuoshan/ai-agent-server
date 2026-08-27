package group.aitools.nhs.platform.risk.web;

import group.aitools.nhs.platform.risk.domain.AgentRiskPolicy;

import java.time.LocalDateTime;

/**
 * 封装风险策略相关的不可变数据。
 */
public record RiskPolicyView(
    Long id,
    String policyKey,
    String name,
    String resourceType,
    String action,
    String riskLevel,
    String disposition,
    String approvalRole,
    boolean notifyEnabled,
    int priority,
    String description,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param policy 策略参数
     * @return 处理结果
     */
    public static RiskPolicyView from(AgentRiskPolicy policy) {
        return new RiskPolicyView(
            policy.getId(), policy.getPolicyKey(), policy.getName(), policy.getResourceType(),
            policy.getAction(), policy.getRiskLevel(), policy.getDisposition(),
            policy.getApprovalRole(), Boolean.TRUE.equals(policy.getNotifyEnabled()),
            policy.getPriority(), policy.getDescription(), policy.getStatus(),
            policy.getCreateTime(), policy.getUpdateTime()
        );
    }
}
