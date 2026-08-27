package group.aitools.nhs.platform.automation.service;

import group.aitools.nhs.platform.automation.domain.AutomationFire;
import group.aitools.nhs.platform.automation.domain.AutomationTrigger;
import group.aitools.nhs.platform.automation.mapper.AutomationMapper;
import group.aitools.nhs.platform.automation.persistence.row.AutomationTaskTargetRow;
import group.aitools.nhs.platform.automation.web.CreateAutomationTriggerRequest;
import group.aitools.nhs.platform.automation.web.ManualAutomationFireRequest;
import group.aitools.nhs.platform.automation.web.UpdateAutomationTriggerRequest;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.identity.service.ServiceAccountPrincipalResolver;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class AutomationApplicationServiceTest {

    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );
    private static final CurrentPrincipal SERVICE = new CurrentPrincipal(
        20L, "worker", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
    );
    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        9L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private AutomationMapper mapper;
    private PlatformIdGenerator ids;
    private ServiceAccountPrincipalResolver accountResolver;
    private TaskRunApplicationService runService;
    private AutomationApplicationService service;
    private AutomationTrigger trigger;

    @BeforeEach
    void setUp() {
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        AuthorizationEnforcer authorization = mock(AuthorizationEnforcer.class);
        mapper = mock(AutomationMapper.class);
        ids = mock(PlatformIdGenerator.class);
        accountResolver = mock(ServiceAccountPrincipalResolver.class);
        runService = mock(TaskRunApplicationService.class);
        when(principals.currentPrincipal()).thenReturn(ADMIN);
        when(accountResolver.requireActive(20L)).thenReturn(SERVICE);
        when(mapper.selectTaskTarget(10L)).thenReturn(target(100L, 3L));
        trigger = trigger();
        when(mapper.lockTrigger(30L)).thenReturn(trigger);
        service = new AutomationApplicationService(
            principals, authorization, ids, mapper, accountResolver, runService,
            new CronScheduleCalculator(), JsonMapper.builder().build()
        );
    }

    @Test
    void duplicateManualIdempotencyKeyReplaysWithoutASecondJob() {
        when(ids.nextId()).thenReturn(1000L, 1001L, 1002L);
        when(mapper.insertFire(any())).thenReturn(1, 0);
        when(mapper.insertFireJob(anyLong(), anyLong(), anyString(), anyString(), anyInt(), any()))
            .thenReturn(1);
        when(mapper.bindFireJob(1000L, 1001L)).thenReturn(1);
        AutomationFire existing = fire(1000L, 1001L, "build report");
        when(mapper.selectFireByKey(anyLong(), anyString())).thenReturn(existing);

        var first = service.manualFire(30L, new ManualAutomationFireRequest("request-1", "build report"));
        var replay = service.manualFire(30L, new ManualAutomationFireRequest("request-1", "build report"));

        assertFalseReplay(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.id(), replay.id());
        verify(mapper).insertFireJob(anyLong(), anyLong(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void duplicateKeyWithDifferentPayloadFailsClosed() {
        when(ids.nextId()).thenReturn(1000L);
        when(mapper.insertFire(any())).thenReturn(0);
        when(mapper.selectFireByKey(anyLong(), anyString())).thenReturn(fire(1000L, 1001L, "old"));

        ServiceException exception = assertThrows(ServiceException.class, () ->
            service.manualFire(30L, new ManualAutomationFireRequest("request-1", "new"))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(mapper, never()).insertFireJob(anyLong(), anyLong(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void taskVersionDriftAndRevokedGrantAreRejectedBeforeQueueInsertion() {
        when(mapper.selectTaskTarget(10L)).thenReturn(target(999L, 4L));
        ServiceException drift = assertThrows(ServiceException.class, () ->
            service.manualFire(30L, new ManualAutomationFireRequest("request-1", "input"))
        );

        when(mapper.selectTaskTarget(10L)).thenReturn(target(100L, 3L));
        doThrow(new ServiceException("denied", HttpStatus.FORBIDDEN)).when(runService)
            .validateAs(SERVICE, 10L, 100L);
        ServiceException revoked = assertThrows(ServiceException.class, () ->
            service.manualFire(30L, new ManualAutomationFireRequest("request-2", "input"))
        );

        assertEquals(HttpStatus.CONFLICT, drift.getCode());
        assertEquals(HttpStatus.FORBIDDEN, revoked.getCode());
        verify(mapper, never()).insertFire(any());
    }

    @Test
    void triggerConfigCannotPersistCredentialLikeFields() {
        CreateAutomationTriggerRequest request = new CreateAutomationTriggerRequest(
            "secure-trigger", "Secure", "manual", 10L, 100L, 20L,
            null, null, null, null, null, "input", Map.of("apiKey", "raw-secret")
        );

        ServiceException exception = assertThrows(ServiceException.class, () -> service.create(request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(mapper, never()).insertTrigger(any());
    }

    @Test
    void frozenTaskOperatorAndOwnedServiceAccountAreBothReauthorizedOnCreate() {
        when(accountResolver.requireOwnedForAutomation(MEMBER, 20L)).thenReturn(SERVICE);
        when(ids.nextId()).thenReturn(40L);
        when(mapper.insertTrigger(any())).thenReturn(1);
        CreateAutomationTriggerRequest request = new CreateAutomationTriggerRequest(
            "member-cron", "Member cron", "cron", 10L, 100L, 20L,
            "0 8 * * *", "Asia/Shanghai", "fire_once", 1, 3,
            "build report", Map.of()
        );

        var created = service.createForTaskOperator(MEMBER, request);

        assertEquals("cron", created.triggerType());
        verify(runService).validateAs(MEMBER, 10L, 100L);
        verify(runService).validateAs(SERVICE, 10L, 100L);
        verify(accountResolver).requireOwnedForAutomation(MEMBER, 20L);
    }

    @Test
    void pausedRecurringTriggerCanStillQueueAnExplicitManualFire() {
        makeCronTrigger("paused");
        when(mapper.lockRecurringTriggerByTaskId(10L)).thenReturn(trigger);
        when(accountResolver.requireOwnedForAutomation(MEMBER, 20L)).thenReturn(SERVICE);
        when(ids.nextId()).thenReturn(1000L, 1001L);
        when(mapper.insertFire(any())).thenReturn(1);
        when(mapper.insertFireJob(anyLong(), anyLong(), anyString(), anyString(), anyInt(), any()))
            .thenReturn(1);
        when(mapper.bindFireJob(1000L, 1001L)).thenReturn(1);

        var fire = service.manualRunRecurringAs(MEMBER, 10L, "manual-1");

        assertEquals("manual", fire.sourceType());
        assertEquals("queued", fire.status());
        verify(runService).validateAs(MEMBER, 10L, 100L);
        verify(mapper).insertFireJob(
            eq(1001L), eq(1000L), eq("automation-fire:1000"), anyString(), eq(3), any()
        );
    }

    @Test
    void activeCronTriggerCanBePausedAndClearsItsNextRun() {
        makeCronTrigger("active");
        trigger.setNextRunAt(LocalDateTime.of(2026, 8, 18, 1, 0));
        when(mapper.updateTrigger(any())).thenReturn(1);

        var updated = service.update(30L, updateRequest("paused"));

        assertEquals("paused", updated.status());
        assertNull(updated.nextRunAt());
        assertEquals(2L, updated.revisionNo());
        verify(mapper).updateTrigger(trigger);
    }

    @Test
    void pausedCronTriggerCanBeResumedAndRecalculatesItsNextRun() {
        makeCronTrigger("paused");
        trigger.setNextRunAt(null);
        when(mapper.updateTrigger(any())).thenReturn(1);

        var updated = service.update(30L, updateRequest("active"));

        assertEquals("active", updated.status());
        assertNotNull(updated.nextRunAt());
        assertEquals(2L, updated.revisionNo());
    }

    @Test
    void pausedAndArchivedManualTriggersAreRejectedBeforeQueueInsertion() {
        trigger.setStatus("paused");
        ServiceException paused = assertThrows(ServiceException.class, () ->
            service.manualFire(30L, new ManualAutomationFireRequest("paused-fire", "input"))
        );

        trigger.setStatus("archived");
        ServiceException archived = assertThrows(ServiceException.class, () ->
            service.manualFire(30L, new ManualAutomationFireRequest("archived-fire", "input"))
        );

        assertEquals(HttpStatus.CONFLICT, paused.getCode());
        assertEquals(HttpStatus.CONFLICT, archived.getCode());
        assertEquals("触发器当前未启用", paused.getMessage());
        assertEquals("触发器当前未启用", archived.getMessage());
        verify(mapper, never()).insertFire(any());
        verify(runService, never()).validateAs(any(), anyLong(), anyLong());
    }

    @Test
    void staleRevisionIsRejectedBeforeConfigurationValidation() {
        UpdateAutomationTriggerRequest stale = new UpdateAutomationTriggerRequest(
            trigger.getName(), trigger.getTaskId(), trigger.getTaskVersionId(),
            trigger.getServiceAccountId(), null, null, null, null, trigger.getMaxAttempts(),
            trigger.getInputTemplate(), "paused", trigger.getRevisionNo() + 1, Map.of()
        );

        ServiceException exception = assertThrows(ServiceException.class, () -> service.update(30L, stale));

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        assertEquals("触发器配置已被并发修改", exception.getMessage());
        verify(mapper, never()).updateTrigger(any());
        verify(runService, never()).validateAs(any(), anyLong(), anyLong());
    }

    private AutomationTrigger trigger() {
        AutomationTrigger value = new AutomationTrigger();
        value.setId(30L);
        value.setTriggerKey("manual-report");
        value.setName("Manual report");
        value.setTriggerType("manual");
        value.setTaskId(10L);
        value.setTaskVersionId(100L);
        value.setTaskRevisionNo(3L);
        value.setServiceAccountId(20L);
        value.setStatus("active");
        value.setMaxAttempts(3);
        value.setRevisionNo(1L);
        value.setConfigJson("{}");
        return value;
    }

    private AutomationTaskTargetRow target(Long versionId, Long revision) {
        AutomationTaskTargetRow value = new AutomationTaskTargetRow();
        value.setTaskId(10L);
        value.setTaskVersionId(versionId);
        value.setTaskRevisionNo(revision);
        return value;
    }

    private void makeCronTrigger(String status) {
        trigger.setTriggerType("cron");
        trigger.setCronExpr("0 0 9 * * ?");
        trigger.setTimezone("Asia/Shanghai");
        trigger.setMisfirePolicy("fire_once");
        trigger.setMaxCatchupCount(1);
        trigger.setInputTemplate("build report");
        trigger.setStatus(status);
    }

    private UpdateAutomationTriggerRequest updateRequest(String status) {
        return new UpdateAutomationTriggerRequest(
            trigger.getName(), trigger.getTaskId(), trigger.getTaskVersionId(),
            trigger.getServiceAccountId(), trigger.getCronExpr(), trigger.getTimezone(),
            trigger.getMisfirePolicy(), trigger.getMaxCatchupCount(), trigger.getMaxAttempts(),
            trigger.getInputTemplate(), status, trigger.getRevisionNo(), Map.of()
        );
    }

    private AutomationFire fire(Long id, Long jobId, String input) {
        String payload = JsonMapper.builder().build().writeValueAsString(Map.of("input", input));
        AutomationFire value = new AutomationFire();
        value.setId(id);
        value.setTriggerId(30L);
        value.setSourceType("manual");
        value.setFireKey("manual:" + ContentHashing.sha256("request-1"));
        value.setPayloadJson(payload);
        value.setPayloadHash(ContentHashing.sha256(payload));
        value.setStatus("queued");
        value.setJobId(jobId);
        value.setAttemptNo(0);
        return value;
    }

    private void assertFalseReplay(boolean replayed) {
        assertEquals(false, replayed);
    }
}
