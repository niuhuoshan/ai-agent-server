package group.aitools.nhs.platform.automation.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示自动化Fire相关的领域对象。
 */
@Data
public class AutomationFire {
    private Long id;
    private Long triggerId;
    private Long triggerRevisionNo;
    private Long serviceAccountId;
    private String sourceType;
    private String fireKey;
    private String payloadHash;
    private String payloadJson;
    private LocalDateTime scheduledAt;
    private String status;
    private Long jobId;
    private Long runId;
    private Integer attemptNo;
    private String lastError;
    private LocalDateTime acceptedAt;
    private LocalDateTime dispatchedAt;
    private LocalDateTime completedAt;
}
