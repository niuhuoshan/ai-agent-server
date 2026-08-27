package group.aitools.nhs.platform.nhs.portal.chatbi;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体对话BIFederated数据源相关的领域对象。
 * Durable governed subquery fact within a federated ChatBI run. */
@Data
public class AgentChatBIFederatedSource {

    private Long id;
    private Long runId;
    private Integer sequenceNo;
    private Long datasetId;
    private String tempTable;
    private String traceId;
    private String plannedSql;
    private String effectiveSql;
    private Long queryId;
    private String status;
    private Integer rowCount;
    private Boolean resultTruncated;
    private Integer repairCount;
    private String errorSummary;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
