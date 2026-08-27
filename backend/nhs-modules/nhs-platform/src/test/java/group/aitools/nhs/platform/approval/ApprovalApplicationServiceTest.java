package group.aitools.nhs.platform.approval;

import group.aitools.nhs.platform.approval.domain.AgentApprovalRequest;
import group.aitools.nhs.platform.approval.mapper.ApprovalRequestMapper;
import group.aitools.nhs.platform.approval.service.ApprovalApplicationService;
import group.aitools.nhs.platform.approval.web.ApprovalDecisionRequest;
import group.aitools.nhs.platform.approval.web.ApprovalDecisionResult;
import group.aitools.nhs.platform.approval.web.ApprovalView;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ApprovalApplicationServiceTest {

    private static final CurrentPrincipal APPROVER = new CurrentPrincipal(
        701L, "approver", PrincipalType.HUMAN, Set.of(PlatformRole.APPROVAL_USER)
    );

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private ApprovalRequestMapper approvalMapper;
    private TaskRunCommandMapper runMapper;
    private TaskRunApplicationService runService;
    private ApprovalApplicationService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        approvalMapper = mock(ApprovalRequestMapper.class);
        runMapper = mock(TaskRunCommandMapper.class);
        runService = mock(TaskRunApplicationService.class);
        when(principalProvider.currentPrincipal()).thenReturn(APPROVER);
        service = new ApprovalApplicationService(
            principalProvider,
            authorizationEnforcer,
            approvalMapper,
            runMapper,
            runService,
            jsonMapper
        );
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void approveLoadsAllActionsOnlyFromTheLockedDatabaseRequest() {
        AgentApprovalRequest pending = pending();
        AgentApprovalRequest approved = decided("approved", "decision-1");
        when(approvalMapper.selectById(42L)).thenReturn(pending, approved);
        when(approvalMapper.lockById(42L)).thenReturn(pending);
        when(approvalMapper.decide(eq(42L), eq("approved"), eq(701L), eq("reviewed"), any(), any()))
            .thenReturn(1);

        ApprovalDecisionResult result = service.approve(
            42L, new ApprovalDecisionRequest("decision-1", "reviewed")
        );

        assertEquals("approved", result.approval().status());
        assertTrue(result.runtimeResumed());
        assertFalse(result.replayed());
        ArgumentCaptor<List> actions = ArgumentCaptor.forClass(List.class);
        verify(runService).resumeFromApproval(eq(pending), eq(APPROVER), actions.capture());
        List<Map<String, Object>> captured = actions.getValue();
        assertEquals(List.of("call-a", "call-b"), captured.stream().map(a -> a.get("id")).toList());
        verify(runService, never()).rejectFromApproval(any());
    }

    @Test
    void rejectNeverParsesOrResumesThePendingToolSnapshot() {
        AgentApprovalRequest pending = pending();
        pending.setPendingActionsJson("malformed-but-server-owned");
        AgentApprovalRequest rejected = decided("rejected", "reject-1");
        when(approvalMapper.selectById(42L)).thenReturn(pending, rejected);
        when(approvalMapper.lockById(42L)).thenReturn(pending);
        when(approvalMapper.decide(eq(42L), eq("rejected"), eq(701L), any(), any(), any()))
            .thenReturn(1);

        ApprovalDecisionResult result = service.reject(
            42L, new ApprovalDecisionRequest("reject-1", "unsafe")
        );

        assertEquals("rejected", result.approval().status());
        assertFalse(result.runtimeResumed());
        verify(runService).rejectFromApproval(pending);
        verify(runService, never()).resumeFromApproval(any(), any(), any());
    }

    @Test
    void sameKeyAndSameDecisionReplaysWithoutSecondRuntimeLaunch() {
        AgentApprovalRequest approved = decided("approved", "decision-1");
        when(approvalMapper.selectById(42L)).thenReturn(approved);
        when(approvalMapper.lockById(42L)).thenReturn(approved);

        ApprovalDecisionResult result = service.approve(
            42L, new ApprovalDecisionRequest("decision-1", "ignored second comment")
        );

        assertTrue(result.replayed());
        assertTrue(result.runtimeResumed());
        verify(approvalMapper, never()).decide(any(), any(), any(), any(), any(), any());
        verify(runService, never()).resumeFromApproval(any(), any(), any());
    }

    @Test
    void sameKeyCannotChangeAnAlreadyCommittedDecision() {
        AgentApprovalRequest approved = decided("approved", "decision-1");
        when(approvalMapper.selectById(42L)).thenReturn(approved);
        when(approvalMapper.lockById(42L)).thenReturn(approved);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.reject(42L, new ApprovalDecisionRequest("decision-1", "change it"))
        );

        assertTrue(exception.getMessage().contains("不能改变决策"));
        verify(runService, never()).rejectFromApproval(any());
    }

    @Test
    void expiredApprovalTerminatesWaitingRunAndCannotResume() {
        AgentApprovalRequest pending = pending();
        pending.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        AgentApprovalRequest expired = copy(pending);
        expired.setStatus("expired");
        expired.setDecidedAt(LocalDateTime.now());
        when(approvalMapper.selectById(42L)).thenReturn(pending, expired);
        when(approvalMapper.lockById(42L)).thenReturn(pending);
        when(approvalMapper.expire(42L)).thenReturn(1);

        ApprovalDecisionResult result = service.approve(
            42L, new ApprovalDecisionRequest("too-late", null)
        );

        assertEquals("expired", result.approval().status());
        assertFalse(result.runtimeResumed());
        verify(runService).expireFromApproval(pending);
        verify(runService, never()).resumeFromApproval(any(), any(), any());
    }

    @Test
    void deniedReviewerCannotLockOrMutateApproval() {
        AgentApprovalRequest pending = pending();
        when(approvalMapper.selectById(42L)).thenReturn(pending);
        doThrow(new ServiceException("denied", 403))
            .when(authorizationEnforcer).requireAllowed(eq(APPROVER), any());

        assertThrows(
            ServiceException.class,
            () -> service.approve(42L, new ApprovalDecisionRequest("decision-1", null))
        );

        verify(approvalMapper, never()).lockById(any());
        verify(runMapper, never()).lockTask(any());
    }

    @Test
    void publicViewDoesNotSerializeRecoveryOrCredentialFields() {
        AgentApprovalRequest pending = pending();
        pending.setCredentialRef("vault:production");
        when(approvalMapper.selectById(42L)).thenReturn(pending);

        ApprovalView view = service.get(42L);
        String json = jsonMapper.writeValueAsString(view);

        assertFalse(json.contains("replyId"));
        assertFalse(json.contains("pendingActions"));
        assertFalse(json.contains("credential"));
        assertFalse(json.contains("vault:production"));
    }

    private AgentApprovalRequest pending() {
        AgentApprovalRequest request = new AgentApprovalRequest();
        request.setId(42L);
        request.setTaskId(10L);
        request.setRunId(500L);
        request.setStepId(501L);
        request.setRiskLevel("R3");
        request.setActionSummary("send and update");
        request.setInputSummary("redacted summary");
        request.setImpactScope("external systems");
        request.setStatus("pending");
        request.setRequestedBy(101L);
        request.setExpiresAt(LocalDateTime.now().plusHours(1));
        request.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        request.setRequestEventId("event-1");
        request.setReplyId("reply-1");
        request.setPendingActionsJson("""
            [{"id":"call-a","name":"send","input":{"to":"ops"}},
             {"id":"call-b","name":"update","input":{"ticket":"T-1"}}]
            """);
        return request;
    }

    private AgentApprovalRequest decided(String status, String key) {
        AgentApprovalRequest request = pending();
        request.setStatus(status);
        request.setReviewerId(701L);
        request.setDecidedAt(LocalDateTime.now());
        request.setDecisionKeyHash(ContentHashing.sha256("approval:42:" + key));
        return request;
    }

    private AgentApprovalRequest copy(AgentApprovalRequest source) {
        AgentApprovalRequest copy = pending();
        copy.setExpiresAt(source.getExpiresAt());
        return copy;
    }
}
