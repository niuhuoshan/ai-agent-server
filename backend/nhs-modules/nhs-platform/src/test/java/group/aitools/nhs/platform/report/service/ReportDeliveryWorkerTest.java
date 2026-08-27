package group.aitools.nhs.platform.report.service;

import group.aitools.nhs.platform.data.web.DataQueryResultView;
import group.aitools.nhs.platform.report.domain.ReportDeliveryJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ReportDeliveryWorkerTest {

    private ReportDeliveryPersistenceService persistence;
    private ReportApplicationService reportService;
    private ReportDeliveryWorker worker;
    private ReportDeliveryJob job;

    @BeforeEach
    void setUp() {
        persistence = mock(ReportDeliveryPersistenceService.class);
        reportService = mock(ReportApplicationService.class);
        worker = new ReportDeliveryWorker(persistence, reportService);
        job = new ReportDeliveryJob();
        job.setId(51L);
        job.setSubscriptionId(41L);
        job.setLeaseToken("lease-a");
        job.setAttemptNo(1);
        job.setMaxAttempts(3);
    }

    @Test
    void successfulExecutionCompletesClaimedDelivery() {
        var query = new DataQueryResultView(
            71L, List.of("amount"), List.of(List.of(10)), 1, 1, false, 15
        );
        var execution = new ReportApplicationService.ScheduledReportExecution(81L, query);
        when(reportService.executeScheduledSubscription(41L)).thenReturn(execution);

        worker.process(job);

        verify(persistence).complete(eq(job), anyString(), eq(execution));
        verify(persistence, never()).fail(eq(job), anyString(), anyString());
    }

    @Test
    void queryFailureIsHandedToBoundedRetryPersistence() {
        when(reportService.executeScheduledSubscription(41L))
            .thenThrow(new IllegalStateException("warehouse unavailable"));

        worker.process(job);

        verify(persistence).fail(eq(job), anyString(), eq("warehouse unavailable"));
    }

    @Test
    void pausedSubscriptionCancelsDeliveryInsteadOfRetrying() {
        when(reportService.executeScheduledSubscription(41L))
            .thenThrow(new ReportDeliveryCancelledException("paused"));

        worker.process(job);

        verify(persistence).cancel(eq(job), anyString(), eq("paused"));
        verify(persistence, never()).fail(eq(job), anyString(), anyString());
    }
}
