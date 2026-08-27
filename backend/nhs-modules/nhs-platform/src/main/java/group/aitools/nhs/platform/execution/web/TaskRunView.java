package group.aitools.nhs.platform.execution.web;

import group.aitools.nhs.platform.execution.domain.AgentTaskRun;

import java.time.LocalDateTime;

/**
 * 封装任务Run相关的不可变数据。
 * Public task-run metadata; frozen credentials and runtime documents are never returned. */
public record TaskRunView(
    Long id,
    Long taskId,
    Long taskVersionId,
    Long workflowVersionId,
    String traceId,
    String status,
    int attemptNo,
    Long parentRunId,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    String waitReason,
    String errorCode,
    String errorSummary,
    String cancelReason,
    Long createdBy,
    LocalDateTime createdAt
) {

    /**
     * 创建 {@code TaskRunView} 实例并初始化所需依赖。
     *
     * @param id 资源标识
     * @param taskId 资源标识
     * @param taskVersionId 资源标识
     * @param traceId 资源标识
     * @param status 目标状态
     * @param attemptNo {@code attemptNo}参数
     * @param parentRunId 资源标识
     * @param startedAt {@code startedAt}参数
     * @param finishedAt {@code finishedAt}参数
     * @param waitReason {@code waitReason}参数
     * @param errorCode {@code errorCode}参数
     * @param errorSummary {@code errorSummary}参数
     * @param cancelReason {@code cancelReason}参数
     * @param createdBy {@code createdBy}参数
     * @param createdAt {@code createdAt}参数
     */
    public TaskRunView(
        Long id,
        Long taskId,
        Long taskVersionId,
        String traceId,
        String status,
        int attemptNo,
        Long parentRunId,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String waitReason,
        String errorCode,
        String errorSummary,
        String cancelReason,
        Long createdBy,
        LocalDateTime createdAt
    ) {
        this(
            id, taskId, taskVersionId, null, traceId, status, attemptNo, parentRunId,
            startedAt, finishedAt, waitReason, errorCode, errorSummary, cancelReason,
            createdBy, createdAt
        );
    }

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    public static TaskRunView from(AgentTaskRun run) {
        return new TaskRunView(
            run.getId(),
            run.getTaskId(),
            run.getTaskVersionId(),
            run.getWorkflowVersionId(),
            run.getTraceId(),
            run.getStatus(),
            run.getAttemptNo(),
            run.getParentRunId(),
            run.getStartedAt(),
            run.getFinishedAt(),
            run.getWaitReason(),
            run.getErrorCode(),
            run.getErrorSummary(),
            run.getCancelReason(),
            run.getCreatedBy(),
            run.getCreatedAt()
        );
    }
}
