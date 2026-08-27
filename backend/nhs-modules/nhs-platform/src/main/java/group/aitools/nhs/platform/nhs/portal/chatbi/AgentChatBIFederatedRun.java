package group.aitools.nhs.platform.nhs.portal.chatbi;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体对话BIFederatedRun相关的领域对象。
 * Durable parent fact for one bounded cross-dataset ChatBI query. */
@Data
public class AgentChatBIFederatedRun {

    private Long id;
    private String runKey;
    private Long ownerId;
    private Long conversationId;
    private Long primaryDatasetId;
    private Long resultQueryId;
    private String requestQuestion;
    private String datasetIdsJson;
    private String planJson;
    private String joinSql;
    private String status;
    private Integer sourceCount;
    private Integer rowCount;
    private Integer resultBytes;
    private Boolean resultTruncated;
    private String errorSummary;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
