package group.aitools.nhs.platform.notification.service;

/**
 * 封装通知消息相关的不可变数据。
 * Server-owned notification content. Arbitrary metadata is deliberately unsupported. */
public record NotificationMessage(
    String eventKey,
    String category,
    String level,
    String title,
    String content,
    String resourceType,
    Long resourceId
) {
}
