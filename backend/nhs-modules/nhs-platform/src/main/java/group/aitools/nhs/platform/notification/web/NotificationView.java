package group.aitools.nhs.platform.notification.web;

import group.aitools.nhs.platform.notification.domain.AgentNotification;

import java.time.LocalDateTime;

/**
 * 封装通知相关的不可变数据。
 * Personal inbox projection; internal event keys and metadata are not exposed. */
public record NotificationView(
    Long id,
    String category,
    String level,
    String title,
    String content,
    String resourceType,
    Long resourceId,
    LocalDateTime readAt,
    LocalDateTime createdAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param notification 通知参数
     * @return 处理结果
     */
    public static NotificationView from(AgentNotification notification) {
        return new NotificationView(
            notification.getId(),
            notification.getCategory(),
            notification.getLevel(),
            notification.getTitle(),
            notification.getContent(),
            notification.getResourceType(),
            notification.getResourceId(),
            notification.getReadAt(),
            notification.getCreatedAt()
        );
    }
}
