package group.aitools.nhs.platform.audit;

import group.aitools.nhs.platform.audit.mapper.AgentAuditQueryMapper;
import group.aitools.nhs.platform.audit.service.AuditOperationsApplicationService;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.execution.domain.AgentExecutionEvent;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentExecutionEventMapper;
import group.aitools.nhs.platform.execution.service.ExecutionTraceAggregationService;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class AuditOperationsApplicationServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private AgentAuditQueryMapper auditMapper;
    private AgentExecutionEventMapper executionEventMapper;
    private AgentConversationMapper conversationMapper;
    private TaskQueryService taskQueryService;
    private AuditOperationsApplicationService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        auditMapper = mock(AgentAuditQueryMapper.class);
        executionEventMapper = mock(AgentExecutionEventMapper.class);
        conversationMapper = mock(AgentConversationMapper.class);
        taskQueryService = mock(TaskQueryService.class);
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
        JsonMapper jsonMapper = JsonMapper.builder().build();
        service = new AuditOperationsApplicationService(
            principalProvider, authorizationEnforcer, auditMapper, executionEventMapper,
            conversationMapper, taskQueryService, jsonMapper,
            new ExecutionTraceAggregationService(jsonMapper)
        );
    }

    @Test
    void taskTraceReplaysTaskVisibilityBeforeReturningSteps() {
        AgentExecutionEvent event = event();
        event.setRunId(401L);
        when(auditMapper.selectTraceEvents("trace-1", 1001)).thenReturn(List.of(event));
        when(executionEventMapper.selectTaskIdForRun(401L)).thenReturn(301L);

        var trace = service.trace("trace-1");

        assertEquals(1, trace.totalSteps());
        assertEquals(401L, trace.steps().getFirst().runId());
        verify(taskQueryService).get(301L);
    }

    @Test
    void crossUserConversationTraceIsHiddenBeforeReturningEvents() {
        AgentExecutionEvent event = event();
        event.setConversationId(201L);
        when(auditMapper.selectTraceEvents("private-trace", 1001)).thenReturn(List.of(event));
        when(conversationMapper.selectOwnedConversation(201L, MEMBER.id())).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.trace("private-trace"));
    }

    @Test
    void mixedTraceScopesReplayAuthorizationForEveryDistinctRun() {
        AgentExecutionEvent first = event();
        first.setRunId(401L);
        AgentExecutionEvent second = event();
        second.setId(2L);
        second.setEventId("event-2");
        second.setCursor(2L);
        second.setRunId(402L);
        when(auditMapper.selectTraceEvents("mixed-trace", 1001)).thenReturn(List.of(first, second));
        when(executionEventMapper.selectTaskIdForRun(401L)).thenReturn(301L);
        when(executionEventMapper.selectTaskIdForRun(402L)).thenReturn(302L);

        service.trace("mixed-trace");

        verify(taskQueryService).get(301L);
        verify(taskQueryService).get(302L);
        verify(executionEventMapper).selectTaskIdForRun(401L);
        verify(executionEventMapper).selectTaskIdForRun(402L);
    }

    private AgentExecutionEvent event() {
        AgentExecutionEvent event = new AgentExecutionEvent();
        event.setId(1L);
        event.setEventId("event-1");
        event.setTraceId("trace-1");
        event.setCursor(1L);
        event.setEventType("model_call");
        event.setEventStatus("success");
        event.setSummary("model completed");
        event.setPayloadJson("{\"model\":\"test-model\"}");
        event.setQueryProjectionJson("{}");
        event.setSensitiveLevel("public");
        event.setOccurredAt(LocalDateTime.of(2026, 8, 18, 4, 0));
        return event;
    }
}
