package group.aitools.nhs.platform.report.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.web.DataQueryResultView;
import group.aitools.nhs.platform.report.domain.ReportDeliveryJob;
import group.aitools.nhs.platform.report.domain.ReportNotificationOutboxEvent;
import group.aitools.nhs.platform.report.domain.AgentReportSubscription;
import group.aitools.nhs.platform.report.mapper.AgentReportMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ReportDeliveryPersistenceServiceTest {

    private AgentReportMapper mapper;
    private PlatformIdGenerator ids;
    private ReportDeliveryPersistenceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentReportMapper.class);
        ids = mock(PlatformIdGenerator.class);
        service = new ReportDeliveryPersistenceService(
            mapper, ids, JsonMapper.builder().build()
        );
    }

    @Test
    void successfulDeliveryCompletesLeaseAndWritesNotificationOutbox() {
        ReportDeliveryJob job = job(1, 3);
        when(mapper.completeDeliveryJob(eq(51L), eq("worker-a"), eq("lease-a"), eq(81L), any()))
            .thenReturn(1);
        when(ids.nextId()).thenReturn(91L);
        DataQueryResultView query = new DataQueryResultView(
            71L, List.of("amount"), List.of(List.of(10)), 1, 1, false, 15
        );

        service.complete(
            job, "worker-a", new ReportApplicationService.ScheduledReportExecution(81L, query)
        );

        ArgumentCaptor<ReportNotificationOutboxEvent> event =
            ArgumentCaptor.forClass(ReportNotificationOutboxEvent.class);
        verify(mapper).insertReportNotificationOutbox(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("report.delivery.succeeded");
        assertThat(event.getValue().getEventKey()).isEqualTo("report-delivery:51");
        assertThat(event.getValue().getPayloadJson()).contains("\"recipientId\":101");
    }

    @Test
    void transientFailureSchedulesRetryWithoutNotification() {
        ReportDeliveryJob job = job(1, 3);
        when(mapper.failDeliveryJob(
            eq(51L), eq("worker-a"), eq("lease-a"), eq("retry"), any(), eq("temporary"), any()
        )).thenReturn(1);

        service.fail(job, "worker-a", "temporary");

        verify(mapper, never()).insertReportNotificationOutbox(any());
    }

    @Test
    void successNotificationCanBeDisabledWithoutSuppressingFinalFailure() {
        ReportDeliveryJob successful = job(1, 3);
        AgentReportSubscription subscription = new AgentReportSubscription();
        subscription.setNotifyPolicyJson("{\"onSuccess\":false,\"onFailure\":true}");
        when(mapper.selectSubscription(41L)).thenReturn(subscription);
        when(mapper.completeDeliveryJob(eq(51L), eq("worker-a"), eq("lease-a"), eq(81L), any()))
            .thenReturn(1);
        DataQueryResultView query = new DataQueryResultView(
            71L, List.of("amount"), List.of(List.of(10)), 1, 1, false, 15
        );

        service.complete(
            successful, "worker-a", new ReportApplicationService.ScheduledReportExecution(81L, query)
        );

        verify(mapper, never()).insertReportNotificationOutbox(any());
    }

    @Test
    void finalFailureMovesJobToDeadAndWritesFailureOutbox() {
        ReportDeliveryJob job = job(3, 3);
        when(mapper.failDeliveryJob(
            eq(51L), eq("worker-a"), eq("lease-a"), eq("dead"), any(), eq("denied"), any()
        )).thenReturn(1);
        when(ids.nextId()).thenReturn(92L);

        service.fail(job, "worker-a", "denied");

        ArgumentCaptor<ReportNotificationOutboxEvent> event =
            ArgumentCaptor.forClass(ReportNotificationOutboxEvent.class);
        verify(mapper).insertReportNotificationOutbox(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("report.delivery.failed");
        assertThat(event.getValue().getPayloadJson()).contains("denied");
    }

    private ReportDeliveryJob job(int attempt, int maximum) {
        ReportDeliveryJob job = new ReportDeliveryJob();
        job.setId(51L);
        job.setSubscriptionId(41L);
        job.setReportId(9L);
        job.setRecipientId(101L);
        job.setLeaseToken("lease-a");
        job.setAttemptNo(attempt);
        job.setMaxAttempts(maximum);
        return job;
    }
}
