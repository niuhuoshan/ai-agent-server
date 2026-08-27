package group.aitools.nhs.platform.report.service;

import group.aitools.nhs.platform.report.domain.ReportNotificationOutboxEvent;
import group.aitools.nhs.platform.report.mapper.AgentReportMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ReportNotificationOutboxWorkerTest {

    private final AgentReportMapper mapper = mock(AgentReportMapper.class);
    private final ReportNotificationPublisher publisher = mock(ReportNotificationPublisher.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ReportNotificationOutboxWorker worker = new ReportNotificationOutboxWorker(
        mapper, publisher, jsonMapper
    );

    @Test
    void validOutboxPayloadPublishesIdempotentInboxNotification() {
        ReportNotificationOutboxEvent event = event(0);
        event.setPayloadJson(jsonMapper.writeValueAsString(new ReportNotificationPayload(
            101L, "report-delivery:51", "success", "完成", "共1行", 9L
        )));
        LocalDateTime now = LocalDateTime.of(2026, 8, 16, 1, 0);
        when(mapper.markReportNotificationPublished(91L, now)).thenReturn(1);

        worker.publish(event, now);

        ArgumentCaptor<ReportNotificationPayload> payload =
            ArgumentCaptor.forClass(ReportNotificationPayload.class);
        verify(publisher).publish(payload.capture());
        assertThat(payload.getValue().recipientId()).isEqualTo(101L);
        assertThat(payload.getValue().eventKey()).isEqualTo("report-delivery:51");
        assertThat(payload.getValue().reportId()).isEqualTo(9L);
        verify(mapper).markReportNotificationPublished(91L, now);
    }

    @Test
    void fifthPublishFailureMovesOutboxToFailed() {
        ReportNotificationOutboxEvent event = event(4);
        event.setPayloadJson("{}");
        LocalDateTime now = LocalDateTime.of(2026, 8, 16, 1, 0);

        worker.publish(event, now);

        verify(mapper).markReportNotificationFailed(
            eq(91L), eq("failed"), any(LocalDateTime.class), any(String.class)
        );
    }

    private ReportNotificationOutboxEvent event(int attempts) {
        ReportNotificationOutboxEvent event = new ReportNotificationOutboxEvent();
        event.setId(91L);
        event.setAttemptNo(attempts);
        return event;
    }
}
