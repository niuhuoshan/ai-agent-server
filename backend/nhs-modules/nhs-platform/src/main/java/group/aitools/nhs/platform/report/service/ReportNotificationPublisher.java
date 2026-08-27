package group.aitools.nhs.platform.report.service;

import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.platform.notification.service.NotificationDeliveryOutboxService;
import group.aitools.nhs.platform.notification.service.NotificationMessage;
import group.aitools.nhs.platform.notification.service.NotificationRecipient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表示报表通知Publisher相关的领域对象。
 * Isolates inbox publication so a provider failure cannot roll back outbox retry state. */
@Service
public class ReportNotificationPublisher {

    private final NotificationApplicationService notificationService;
    private final NotificationDeliveryOutboxService deliveryOutboxService;

    public ReportNotificationPublisher(NotificationApplicationService notificationService) {
        this(notificationService, null);
    }

    /**
     * 创建 {@code ReportNotificationPublisher} 实例并初始化所需依赖。
     *
     * @param notificationService 通知Service参数
     * @param deliveryOutboxService {@code deliveryOutboxService}参数
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ReportNotificationPublisher(
        NotificationApplicationService notificationService,
        NotificationDeliveryOutboxService deliveryOutboxService
    ) {
        this.notificationService = notificationService;
        this.deliveryOutboxService = deliveryOutboxService;
    }

    /**
     * 处理{@code publish}相关逻辑。
     *
     * @param payload {@code payload}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void publish(ReportNotificationPayload payload) {
        var channels = payload.channels();
        boolean portal = channels == null || channels.isEmpty()
            || channels.stream().anyMatch(channel -> "inbox".equalsIgnoreCase(channel)
                || "portal".equalsIgnoreCase(channel));
        if (portal) {
            notificationService.publish(
                new NotificationRecipient(payload.recipientId(), PrincipalType.HUMAN),
                new NotificationMessage(
                    payload.eventKey(), "run", payload.level(), payload.title(), payload.content(),
                    "report", payload.reportId()
                )
            );
        }
        if (deliveryOutboxService != null && channels != null && !channels.isEmpty()) {
            deliveryOutboxService.enqueueChannels(
                payload.recipientId(), payload.eventKey(), channels, payload.title(), payload.content()
            );
        }
    }
}
