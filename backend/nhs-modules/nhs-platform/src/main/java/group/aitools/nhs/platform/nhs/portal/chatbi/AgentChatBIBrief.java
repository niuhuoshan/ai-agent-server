package group.aitools.nhs.platform.nhs.portal.chatbi;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体对话BIBrief相关的领域对象。
 * Durable, owner-scoped ChatBI business brief. */
@Data
public class AgentChatBIBrief {

    private String id;
    private Long ownerId;
    private String conversationId;
    private String resultId;
    private String title;
    private String briefPayload;
    private String markdownContent;
    private String artifactPayload;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String delFlag;
}
