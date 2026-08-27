package group.aitools.nhs.platform.approval.web;

import group.aitools.nhs.platform.approval.domain.AgentApprovalRequest;

import java.time.LocalDateTime;

/**
 * 封装审批相关的不可变数据。
 * Public approval projection; recovery tokens, credentials and tool snapshots never leave the server. */
public record ApprovalView(
    Long id,
    Long taskId,
    Long runId,
    Long stepId,
    String riskLevel,
    String actionSummary,
    String inputSummary,
    String impactScope,
    String status,
    Long requestedBy,
    Long reviewerId,
    String reviewComment,
    LocalDateTime expiresAt,
    LocalDateTime decidedAt,
    LocalDateTime createdAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public static ApprovalView from(AgentApprovalRequest request) {
        return new ApprovalView(
            request.getId(),
            request.getTaskId(),
            request.getRunId(),
            request.getStepId(),
            request.getRiskLevel(),
            request.getActionSummary(),
            request.getInputSummary(),
            request.getImpactScope(),
            request.getStatus(),
            request.getRequestedBy(),
            request.getReviewerId(),
            request.getReviewComment(),
            request.getExpiresAt(),
            request.getDecidedAt(),
            request.getCreatedAt()
        );
    }
}
