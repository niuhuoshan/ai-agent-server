package group.aitools.nhs.platform.report.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.report.domain.AgentReportSubscription;
import group.aitools.nhs.platform.report.domain.ReportDeliveryJob;
import group.aitools.nhs.platform.report.mapper.AgentReportMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ReportSubscriptionSchedulerTest {

    @Test
    void dueSubscriptionIsPersistedAsJobBeforeCursorAdvances() {
        AgentReportMapper mapper = mock(AgentReportMapper.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        ReportSubscriptionScheduler scheduler = new ReportSubscriptionScheduler(
            mapper, ids, new ReportScheduleCalculator()
        );
        LocalDateTime due = LocalDateTime.of(2026, 8, 16, 1, 0);
        LocalDateTime now = LocalDateTime.of(2026, 8, 16, 1, 2);
        AgentReportSubscription subscription = intervalSubscription(due);
        when(ids.nextId()).thenReturn(91L);
        when(mapper.advanceSubscriptionSchedule(
            eq(41L), eq(3L), eq(due), any(LocalDateTime.class), eq(now)
        )).thenReturn(1);

        scheduler.enqueueAndAdvance(subscription, now);

        ArgumentCaptor<ReportDeliveryJob> job = ArgumentCaptor.forClass(ReportDeliveryJob.class);
        verify(mapper).insertDeliveryJob(job.capture());
        assertThat(job.getValue().getId()).isEqualTo(91L);
        assertThat(job.getValue().getSubscriptionId()).isEqualTo(41L);
        assertThat(job.getValue().getRecipientId()).isEqualTo(101L);
        assertThat(job.getValue().getScheduledAt()).isEqualTo(due);
        verify(mapper).advanceSubscriptionSchedule(
            41L, 3L, due, LocalDateTime.of(2026, 8, 16, 1, 17), now
        );
    }

    private AgentReportSubscription intervalSubscription(LocalDateTime due) {
        AgentReportSubscription subscription = new AgentReportSubscription();
        subscription.setId(41L);
        subscription.setReportId(9L);
        subscription.setCreateBy(101L);
        subscription.setScheduleType("interval");
        subscription.setIntervalMinutes(15);
        subscription.setMaxAttempts(4);
        subscription.setRevisionNo(3L);
        subscription.setNextRunAt(due);
        return subscription;
    }
}
