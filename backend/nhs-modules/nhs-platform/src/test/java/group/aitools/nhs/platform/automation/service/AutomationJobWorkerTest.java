package group.aitools.nhs.platform.automation.service;

import group.aitools.nhs.platform.automation.domain.AutomationFire;
import group.aitools.nhs.platform.automation.domain.AutomationTrigger;
import group.aitools.nhs.platform.automation.mapper.AutomationMapper;
import group.aitools.nhs.platform.automation.persistence.row.AutomationJobRow;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.execution.web.TaskRunActionResult;
import group.aitools.nhs.platform.execution.web.TaskRunView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.service.ServiceAccountPrincipalResolver;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class AutomationJobWorkerTest {

    private AutomationQueuePersistenceService persistence;
    private AutomationMapper mapper;
    private ServiceAccountPrincipalResolver accountResolver;
    private TaskRunApplicationService runService;
    private AutomationJobWorker worker;
    private AutomationJobRow job;
    private AutomationFire fire;
    private AutomationTrigger trigger;
    private CurrentPrincipal principal;

    @BeforeEach
    void setUp() {
        persistence = mock(AutomationQueuePersistenceService.class);
        mapper = mock(AutomationMapper.class);
        accountResolver = mock(ServiceAccountPrincipalResolver.class);
        runService = mock(TaskRunApplicationService.class);
        worker = new AutomationJobWorker(
            persistence, mapper, accountResolver, runService, JsonMapper.builder().build()
        );
        principal = new CurrentPrincipal(
            20L, "worker", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
        );
        job = new AutomationJobRow();
        job.setId(10L);
        job.setFireId(11L);
        job.setPayloadJson("{\"input\":\"build report\"}");
        job.setLeaseToken("lease-a");
        job.setAttemptNo(1);
        job.setMaxAttempts(3);
        fire = new AutomationFire();
        fire.setId(11L);
        fire.setJobId(10L);
        fire.setTriggerId(12L);
        fire.setTriggerRevisionNo(4L);
        fire.setServiceAccountId(20L);
        fire.setStatus("running");
        trigger = new AutomationTrigger();
        trigger.setId(12L);
        trigger.setRevisionNo(4L);
        trigger.setServiceAccountId(20L);
        trigger.setTaskId(30L);
        trigger.setTaskVersionId(31L);
        trigger.setStatus("active");
        when(mapper.selectFire(11L)).thenReturn(fire);
        when(mapper.selectTrigger(12L)).thenReturn(trigger);
        when(accountResolver.requireActive(20L)).thenReturn(principal);
    }

    @Test
    void revokedServiceAccountIsRecheckedAtExecutionTime() {
        when(accountResolver.requireActive(20L)).thenThrow(
            new ServiceException("revoked", HttpStatus.FORBIDDEN)
        );

        worker.process(job);

        verify(runService, never()).createAs(any(), any(), any(), any());
        verify(persistence).fail(eq(job), anyString(), eq("revoked"));
    }

    @Test
    void triggerRevisionDriftFailsBeforeResolvingPrincipal() {
        trigger.setRevisionNo(5L);

        worker.process(job);

        verify(accountResolver, never()).requireActive(any());
        verify(runService, never()).createAs(any(), any(), any(), any());
        verify(persistence).fail(eq(job), anyString(), anyString());
    }

    @Test
    void workerThatLosesLeaseAfterCreateCannotStartOrCompleteRun() {
        TaskRunView view = new TaskRunView(
            99L, 30L, 31L, "trace", "queued", 1, null,
            null, null, null, null, null, null, 20L, null
        );
        when(runService.createAs(eq(principal), eq(30L), eq(31L), any()))
            .thenReturn(new TaskRunActionResult(view, false));
        doThrow(new AutomationQueuePersistenceService.StaleAutomationLeaseException())
            .when(persistence).renew(eq(job), anyString());

        worker.process(job);

        verify(runService, never()).startAs(any(), any(), any(), any());
        verify(persistence, never()).complete(any(), anyString(), any());
        verify(persistence, never()).fail(any(), anyString(), anyString());
    }

    @Test
    void explicitManualFireRemainsRunnableWhileCronTriggerIsPaused() {
        fire.setSourceType("manual");
        trigger.setStatus("paused");
        TaskRunView queued = new TaskRunView(
            99L, 30L, 31L, "trace", "queued", 1, null,
            null, null, null, null, null, null, 20L, null
        );
        TaskRunView running = new TaskRunView(
            99L, 30L, 31L, "trace", "running", 1, null,
            null, null, null, null, null, null, 20L, null
        );
        when(runService.createAs(eq(principal), eq(30L), eq(31L), any()))
            .thenReturn(new TaskRunActionResult(queued, false));
        when(runService.startAs(principal, 30L, 99L, 31L))
            .thenReturn(new TaskRunActionResult(running, false));

        worker.process(job);

        verify(runService).startAs(principal, 30L, 99L, 31L);
        verify(persistence).complete(eq(job), org.mockito.ArgumentMatchers.anyString(), eq(99L));
        verify(persistence, never()).fail(any(), anyString(), anyString());
    }

}
