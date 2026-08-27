package group.aitools.nhs.platform.notification.web;

import group.aitools.nhs.platform.notification.domain.NotificationDeliveryOutboxEvent;
import group.aitools.nhs.platform.notification.service.NotificationDeliveryPayload;

import java.time.LocalDateTime;

/**
 * 封装通知Delivery相关的不可变数据。
 * Personal external notification delivery status without credentials or message bodies. */
public record NotificationDeliveryView(
    Long id,
    String sourceEventKey,
    String channelType,
    String status,
    int attemptNo,
    LocalDateTime nextAttemptAt,
    LocalDateTime publishedAt,
    String lastError,
    LocalDateTime createdAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param event 事件参数
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    public static NotificationDeliveryView from(
        NotificationDeliveryOutboxEvent event,
        NotificationDeliveryPayload payload
    ) {
        return new NotificationDeliveryView(
            event.getId(),
            payload.sourceEventKey(),
            payload.channelType(),
            event.getStatus(),
            event.getAttemptNo() == null ? 0 : event.getAttemptNo(),
            event.getNextAttemptAt(),
            event.getPublishedAt(),
            event.getLastError(),
            event.getCreatedAt()
        );
    }
}
