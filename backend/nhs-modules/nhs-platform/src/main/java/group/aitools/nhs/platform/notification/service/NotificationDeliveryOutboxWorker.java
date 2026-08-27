package group.aitools.nhs.platform.notification.service;

import lombok.extern.slf4j.Slf4j;
import group.aitools.nhs.platform.notification.domain.NotificationDeliveryOutboxEvent;
import group.aitools.nhs.platform.notification.mapper.NotificationDeliveryOutboxMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 表示通知DeliveryOutbox工作进程相关的领域对象。
 * Delivers external notifications from the durable outbox with bounded retries. */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "agent.platform.notification-delivery",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class NotificationDeliveryOutboxWorker {

    private static final int MAX_ATTEMPTS = 5;

    private final NotificationDeliveryOutboxMapper mapper;
    private final NotificationDeliveryOutboxService outboxService;
    private final UserNotificationConfigService configService;
    private final JsonMapper jsonMapper;
    private final NotificationOperationAuditService auditService;

    /**
     * 创建 {@code NotificationDeliveryOutboxWorker} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param outboxService {@code outboxService}参数
     * @param configService {@code configService}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param auditService 审计Service参数
     */
    public NotificationDeliveryOutboxWorker(
        NotificationDeliveryOutboxMapper mapper,
        NotificationDeliveryOutboxService outboxService,
        UserNotificationConfigService configService,
        JsonMapper jsonMapper,
        NotificationOperationAuditService auditService
    ) {
        this.mapper = mapper;
        this.outboxService = outboxService;
        this.configService = configService;
        this.jsonMapper = jsonMapper;
        this.auditService = auditService;
    }

    /**
     * 处理{@code publishDue}相关逻辑。
     */
    @Scheduled(
        fixedDelayString = "${agent.platform.notification-delivery.delay-ms:2000}",
        initialDelayString = "${agent.platform.notification-delivery.initial-delay-ms:10000}"
    )
    @Transactional(rollbackFor = Exception.class)
    public void publishDue() {
        LocalDateTime now = utcNow();
        for (NotificationDeliveryOutboxEvent event : mapper.lockDue(now, 50)) {
            publish(event, now);
        }
    }

    /**
     * 处理{@code publish}相关逻辑。
     *
     * @param event 事件参数
     * @param now {@code now}参数
     */
    void publish(NotificationDeliveryOutboxEvent event, LocalDateTime now) {
        try {
            NotificationDeliveryPayload payload = outboxService.payload(event);
            configService.sendForUser(
                payload.userId(), payload.channelType(), payload.title(),
                payload.content(), payload.recipient()
            );
            if (mapper.markPublished(event.getId(), now) != 1) {
                throw new IllegalStateException("通知投递 Outbox 状态发生变化");
            }
            auditService.recordSystemSafely(
                "notification.delivery.published", event.getId(), "success", null,
                "channel=" + payload.channelType()
            );
        } catch (RuntimeException exception) {
            int nextAttempt = (event.getAttemptNo() == null ? 0 : event.getAttemptNo()) + 1;
            String status = nextAttempt >= MAX_ATTEMPTS ? "failed" : "pending";
            LocalDateTime nextAttemptAt = now.plusSeconds(backoffSeconds(nextAttempt));
            mapper.markFailed(event.getId(), status, nextAttemptAt, safeError(exception));
            auditService.recordSystemSafely(
                "notification.delivery.failed", event.getId(), status,
                safeError(exception), "attempt=" + nextAttempt
            );
            log.warn(
                "Notification delivery {} failed (attempt {}, status={}): {}",
                event.getId(), nextAttempt, status, safeError(exception)
            );
        }
    }

    /**
     * 处理{@code backoffSeconds}并返回对应结果。
     *
     * @param attempt {@code attempt}参数
     * @return 处理结果
     */
    private long backoffSeconds(int attempt) {
        return switch (attempt) {
            case 1 -> 10;
            case 2 -> 60;
            default -> 300;
        };
    }

    /**
     * 处理{@code safeError}并返回对应结果。
     *
     * @param throwable {@code throwable}参数
     * @return 处理结果
     */
    private String safeError(Throwable throwable) {
        String value = throwable.getMessage();
        if (value == null || value.isBlank()) {
            value = throwable.getClass().getSimpleName();
        }
        String normalized = value.replace('\0', ' ').strip();
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
    }

    /**
     * 处理{@code utcNow}并返回对应结果。
     *
     * @return 处理结果
     */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
