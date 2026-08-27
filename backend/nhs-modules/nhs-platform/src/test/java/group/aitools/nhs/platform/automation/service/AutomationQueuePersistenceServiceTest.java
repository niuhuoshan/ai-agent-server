package group.aitools.nhs.platform.automation.service;

import group.aitools.nhs.platform.automation.mapper.AutomationMapper;
import group.aitools.nhs.platform.automation.persistence.row.AutomationJobRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class AutomationQueuePersistenceServiceTest {

    @Test
    void staleLeaseCannotCompleteFireAfterJobOwnershipWasLost() {
        AutomationMapper mapper = mock(AutomationMapper.class);
        AutomationQueuePersistenceService service = new AutomationQueuePersistenceService(mapper);
        AutomationJobRow job = job(1, 3);
        when(mapper.completeJob(eq(10L), eq("worker-a"), eq("lease-a"), any())).thenReturn(0);

        assertThrows(
            AutomationQueuePersistenceService.StaleAutomationLeaseException.class,
            () -> service.complete(job, "worker-a", 99L)
        );

        verify(mapper, never()).completeFire(any(), any(), any(), any());
    }

    @Test
    void finalRetryTransitionsBothJobAndFireToDead() {
        AutomationMapper mapper = mock(AutomationMapper.class);
        AutomationQueuePersistenceService service = new AutomationQueuePersistenceService(mapper);
        AutomationJobRow job = job(3, 3);
        when(mapper.failJob(
            eq(10L), eq("worker-a"), eq("lease-a"), eq("dead"), any(), eq("denied"), any()
        )).thenReturn(1);
        when(mapper.failFire(
            eq(20L), eq(10L), eq(3), eq("dead"), eq("denied"), any()
        )).thenReturn(1);

        service.fail(job, "worker-a", "denied");

        verify(mapper).failJob(
            eq(10L), eq("worker-a"), eq("lease-a"), eq("dead"), any(), eq("denied"), any()
        );
        verify(mapper).failFire(
            eq(20L), eq(10L), eq(3), eq("dead"), eq("denied"), any()
        );
    }

    @Test
    void nonFinalFailureIsRetriedWithoutMarkingCompletion() {
        AutomationMapper mapper = mock(AutomationMapper.class);
        AutomationQueuePersistenceService service = new AutomationQueuePersistenceService(mapper);
        AutomationJobRow job = job(1, 3);
        when(mapper.failJob(
            eq(10L), eq("worker-a"), eq("lease-a"), eq("queued"), any(), eq("temporary"), any()
        )).thenReturn(1);
        when(mapper.failFire(
            eq(20L), eq(10L), eq(1), eq("retry"), eq("temporary"), any()
        )).thenReturn(1);

        service.fail(job, "worker-a", "temporary");

        verify(mapper).failFire(
            eq(20L), eq(10L), eq(1), eq("retry"), eq("temporary"), any()
        );
    }

    private AutomationJobRow job(int attempt, int maximum) {
        AutomationJobRow job = new AutomationJobRow();
        job.setId(10L);
        job.setFireId(20L);
        job.setAttemptNo(attempt);
        job.setMaxAttempts(maximum);
        job.setLeaseToken("lease-a");
        return job;
    }
}
