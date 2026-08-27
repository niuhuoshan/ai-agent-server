package group.aitools.nhs.platform.nhs.portal.example;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体对话BIExample相关的领域对象。
 * Durable ChatBI/Few-shot example. The row remains useful without Redis. */
@Data
public class AgentChatBIExample {

    private Long id;
    private String traceId;
    private String agentId;
    private Long datasetId;
    private String userQuery;
    private String refinedQuery;
    private String contextSummary;
    private String sqlText;
    private String sqlMetadataJson;
    private String category;
    private String enhanceStatus;
    private String aiAnswer;
    private String feedbackType;
    private String reviewStatus;
    private String errorMessage;
    private Integer useCount;
    private String localSyncStatus;
    private String localSyncError;
    private LocalDateTime localSyncedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String delFlag;
}
