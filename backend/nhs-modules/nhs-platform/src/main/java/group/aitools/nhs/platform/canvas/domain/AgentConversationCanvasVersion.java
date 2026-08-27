package group.aitools.nhs.platform.canvas.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体会话画布版本相关的领域对象。
 * Append-only content snapshot for a conversation canvas. */
@Data
public class AgentConversationCanvasVersion {

    private Long id;
    private Long canvasId;
    private Integer versionNo;
    private String title;
    private String canvasType;
    private String content;
    private String metadataJson;
    private Long contentSize;
    private String contentSha256;
    private String changeType;
    private Integer sourceVersionNo;
    private Long createdBy;
    private LocalDateTime createdAt;
}
