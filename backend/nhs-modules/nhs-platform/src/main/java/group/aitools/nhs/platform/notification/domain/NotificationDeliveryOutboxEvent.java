package group.aitools.nhs.platform.notification.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示通知DeliveryOutbox相关的领域对象。
 * One owner-scoped external notification delivery persisted in the shared outbox. */
@Data
public class NotificationDeliveryOutboxEvent {

    private Long id;
    private Long userId;
    private String eventKey;
    private String payloadJson;
    private String status;
    private Integer attemptNo;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime publishedAt;
    private String lastError;
    private LocalDateTime createdAt;
}
