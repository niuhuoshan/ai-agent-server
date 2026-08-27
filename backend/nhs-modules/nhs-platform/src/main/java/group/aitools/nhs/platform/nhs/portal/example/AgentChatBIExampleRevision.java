package group.aitools.nhs.platform.nhs.portal.example;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体对话BIExampleRevision相关的领域对象。
 * Append-only content snapshot for one ChatBI example mutation. */
@Data
public class AgentChatBIExampleRevision {

    private Long id;
    private Long revisionNo;
    private Long exampleId;
    private String action;
    private String reviewStatus;
    private String userQuery;
    private String refinedQuery;
    private String contextSummary;
    private String sqlText;
    private String sqlMetadataJson;
    private String category;
    private String enhanceStatus;
    private String localSyncStatus;
    private String actorType;
    private Long actorId;
    private String reason;
    private String contentHash;
    private LocalDateTime createdAt;
}
