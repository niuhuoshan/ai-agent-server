package group.aitools.nhs.platform.agent;

import group.aitools.nhs.platform.agent.domain.AgentDefinition;
import group.aitools.nhs.platform.agent.mapper.AgentDefinitionMapper;
import group.aitools.nhs.platform.agent.mapper.AgentExecutionHistoryMapper;
import group.aitools.nhs.platform.agent.persistence.row.AgentExecutionHistoryRow;
import group.aitools.nhs.platform.agent.service.AgentExecutionHistoryService;
import group.aitools.nhs.platform.agent.web.AgentExecutionHistoryView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class AgentExecutionHistoryServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        7L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );

    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private AgentDefinitionMapper definitionMapper;
    private AgentExecutionHistoryMapper historyMapper;
    private AgentExecutionHistoryService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        definitionMapper = mock(AgentDefinitionMapper.class);
        historyMapper = mock(AgentExecutionHistoryMapper.class);
        service = new AgentExecutionHistoryService(
            principalProvider, authorizationEnforcer, definitionMapper, historyMapper
        );
        when(definitionMapper.selectDefinitionById(10L)).thenReturn(agent(10L));
    }

    @Test
    void memberHistoryKeepsOwnerFilterAndNhsProjection() {
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
        when(historyMapper.selectExecutions(10L, 7L, false, 50)).thenReturn(List.of(history()));

        List<AgentExecutionHistoryView> result = service.list(10L, 50);

        assertEquals(1, result.size());
        AgentExecutionHistoryView item = result.getFirst();
        assertEquals(100L, item.id());
        assertEquals("trace-1", item.trace_id());
        assertEquals("10", item.agent_id());
        assertEquals("20", item.conversation_id());
        assertEquals("alice", item.username());
        assertEquals("问题", item.query());
        assertEquals("回答", item.summary());
        assertEquals("success", item.status());
        assertEquals("3", item.agent_version());
        assertEquals("30", item.model_id());
        assertEquals(125L, item.execution_time_ms());
        assertEquals(2L, item.turn_count());
        assertEquals("数据分析 Agent", item.agent_display_name());

        ArgumentCaptor<PermissionContext> context = ArgumentCaptor.forClass(PermissionContext.class);
        verify(authorizationEnforcer).requireAllowed(org.mockito.ArgumentMatchers.eq(MEMBER), context.capture());
        assertEquals("agent", context.getValue().resourceType());
        assertEquals(10L, context.getValue().resourceId());
        assertEquals("view", context.getValue().action());
        assertEquals(true, context.getValue().userInterfaceOperation());
        verify(historyMapper).selectExecutions(10L, 7L, false, 50);
    }

    @Test
    void platformAdministratorCanReadAllOwnersAfterObjectAuthorization() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        when(historyMapper.selectExecutions(10L, 1L, true, 200)).thenReturn(List.of());

        service.list(10L, 200);

        verify(authorizationEnforcer).requireAllowed(
            org.mockito.ArgumentMatchers.eq(ADMIN), org.mockito.ArgumentMatchers.any(PermissionContext.class)
        );
        verify(historyMapper).selectExecutions(10L, 1L, true, 200);
    }

    @Test
    void missingAgentDoesNotQueryConversationHistory() {
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
        when(definitionMapper.selectDefinitionById(99L)).thenReturn(null);

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.list(99L, 50)
        );

        assertEquals(404, exception.getCode());
        verify(historyMapper, never()).selectExecutions(99L, 7L, false, 50);
    }

    @Test
    void limitOutsideContractIsRejectedBeforeAuthorizationOrQuery() {
        ServiceException tooSmall = assertThrows(
            ServiceException.class, () -> service.list(10L, 0)
        );
        ServiceException tooLarge = assertThrows(
            ServiceException.class, () -> service.list(10L, 201)
        );

        assertEquals(400, tooSmall.getCode());
        assertEquals(400, tooLarge.getCode());
        verify(authorizationEnforcer, never()).requireAllowed(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        );
        verify(historyMapper, never()).selectExecutions(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyInt()
        );
    }

    private AgentDefinition agent(Long id) {
        AgentDefinition definition = new AgentDefinition();
        definition.setId(id);
        definition.setAgentKey("data-agent");
        definition.setName("数据分析 Agent");
        definition.setStatus("active");
        definition.setDelFlag("0");
        return definition;
    }

    private AgentExecutionHistoryRow history() {
        AgentExecutionHistoryRow row = new AgentExecutionHistoryRow();
        row.setId(100L);
        row.setTraceId("trace-1");
        row.setAgentId(10L);
        row.setConversationId(20L);
        row.setUsername("alice");
        row.setQuery("问题");
        row.setSummary("回答");
        row.setStatus("success");
        row.setAgentVersion("3");
        row.setModelId("30");
        row.setExecutionTimeMs(125L);
        row.setCreatedAt(LocalDateTime.of(2026, 8, 18, 2, 0));
        row.setTurnCount(2L);
        row.setAgentDisplayName("数据分析 Agent");
        return row;
    }
}
