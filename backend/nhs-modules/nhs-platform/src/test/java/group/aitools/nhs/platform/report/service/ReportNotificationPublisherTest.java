package group.aitools.nhs.platform.report.service;

import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.platform.notification.service.NotificationMessage;
import group.aitools.nhs.platform.notification.service.NotificationRecipient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Tag("dev")
class ReportNotificationPublisherTest {

    @Test
    void mapsServerOwnedOutboxPayloadToHumanInboxNotification() {
        NotificationApplicationService notifications = mock(NotificationApplicationService.class);
        ReportNotificationPublisher publisher = new ReportNotificationPublisher(notifications);

        publisher.publish(new ReportNotificationPayload(
            101L, "report-delivery:51", "success", "完成", "共1行", 9L
        ));

        ArgumentCaptor<NotificationRecipient> recipient =
            ArgumentCaptor.forClass(NotificationRecipient.class);
        ArgumentCaptor<NotificationMessage> message =
            ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notifications).publish(recipient.capture(), message.capture());
        assertThat(recipient.getValue().id()).isEqualTo(101L);
        assertThat(message.getValue().eventKey()).isEqualTo("report-delivery:51");
        assertThat(message.getValue().resourceId()).isEqualTo(9L);
    }
}
