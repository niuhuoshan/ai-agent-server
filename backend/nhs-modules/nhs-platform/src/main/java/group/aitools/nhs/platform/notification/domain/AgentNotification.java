package group.aitools.nhs.platform.notification.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体通知相关的领域对象。
 * One durable notification addressed to a human platform user. */
@Data
public class AgentNotification {

    private Long id;
    private Long userId;
    private String eventKey;
    private String category;
    private String level;
    private String title;
    private String content;
    private String resourceType;
    private Long resourceId;
    /** JSON metadata retained for the Nhs portal inbox projection. */
    private String metadataJson;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
