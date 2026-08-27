package group.aitools.nhs.platform.nhs.portal.chatbi;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体对话BI结果相关的领域对象。
 * Durable lineage and presentation state for one immutable ChatBI result. */
@Data
public class AgentChatBIResultContext {
    private Long queryId;
    private Long ownerId;
    private Long conversationId;
    private Long parentQueryId;
    private String analysisContextJson;
    private String chartConfigJson;
    private String pivotConfigJson;
    private Integer revisionNo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
