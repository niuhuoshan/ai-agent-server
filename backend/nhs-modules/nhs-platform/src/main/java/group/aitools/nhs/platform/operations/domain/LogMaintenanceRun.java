package group.aitools.nhs.platform.operations.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示{@code LogMaintenanceRun}相关的领域对象。
 * Persistent two-step cleanup preview and maintenance execution. */
@Data
public class LogMaintenanceRun {

    private Long id;
    private String triggerType;
    private String status;
    private Integer retentionDays;
    private Integer policyRevision;
    private LocalDateTime cutoffAt;
    private String confirmationTokenHash;
    private LocalDateTime confirmationExpiresAt;
    private Long requestedBy;
    private LocalDateTime confirmedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String summaryJson;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
