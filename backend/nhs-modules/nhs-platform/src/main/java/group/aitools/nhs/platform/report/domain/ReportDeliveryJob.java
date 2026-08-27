package group.aitools.nhs.platform.report.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示报表Delivery作业相关的领域对象。
 * Leased saved-report delivery attempt, separate from task automation jobs. */
@Data
public class ReportDeliveryJob {

    private Long id;
    private Long subscriptionId;
    private Long reportId;
    private Long recipientId;
    private LocalDateTime scheduledAt;
    private String status;
    private Integer attemptNo;
    private Integer maxAttempts;
    private LocalDateTime availableAt;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private String workerId;
    private Long reportRunId;
    private String lastError;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
