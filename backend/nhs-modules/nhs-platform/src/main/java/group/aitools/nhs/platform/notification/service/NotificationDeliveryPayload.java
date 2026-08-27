package group.aitools.nhs.platform.notification.service;

/**
 * 封装通知DeliveryPayload相关的不可变数据。
 * Server-owned external-channel delivery payload. Credentials are never persisted here. */
public record NotificationDeliveryPayload(
    Long userId,
    String sourceEventKey,
    String channelType,
    String title,
    String content,
    String recipient
) {
}
