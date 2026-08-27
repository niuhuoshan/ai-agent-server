package group.aitools.nhs.platform.execution.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体任务Run相关的领域对象。
 * Durable execution attempt for one immutable task version. */
@Data
public class AgentTaskRun {

    private Long id;
    private Long taskId;
    private Long taskVersionId;
    private Long workflowVersionId;
    private String traceId;
    private String status;
    private Integer attemptNo;
    private Long parentRunId;
    private String workerId;
    private LocalDateTime leaseUntil;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String waitReason;
    private String errorCode;
    private String errorSummary;
    private String cancelReason;
    private String authorizationSnapshotJson;
    private String runtimeSnapshotJson;
    private String budgetSnapshotJson;
    private String usageJson;
    private Long createdBy;
    private LocalDateTime createdAt;
}
