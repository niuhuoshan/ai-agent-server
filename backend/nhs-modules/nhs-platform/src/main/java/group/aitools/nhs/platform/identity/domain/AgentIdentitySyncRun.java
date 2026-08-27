package group.aitools.nhs.platform.identity.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体身份SyncRun相关的领域对象。
 * Durable synchronization run and redacted source snapshot. */
@Data
public class AgentIdentitySyncRun {

    private Long id;
    private Long retryOfRunId;
    private String providerType;
    private Long configRevision;
    private String status;
    private String requestedNamesJson;
    private String itemsJson;
    private Integer discoveredCount;
    private Integer selectedCount;
    private Integer createdCount;
    private Integer updatedCount;
    private Integer skippedCount;
    private Integer failedCount;
    private String errorSummary;
    private Long requestedBy;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
