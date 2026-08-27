package group.aitools.nhs.platform.canvas.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体会话画布相关的领域对象。
 * Owner-scoped current pointer for one conversation canvas. */
@Data
public class AgentConversationCanvas {

    private Long id;
    private Long conversationId;
    private Long ownerId;
    private String title;
    private String canvasType;
    private Integer currentVersionNo;
    private Integer revisionNo;
    private String metadataJson;
    private Long contentSize;
    private String contentSha256;
    private String content;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
}
