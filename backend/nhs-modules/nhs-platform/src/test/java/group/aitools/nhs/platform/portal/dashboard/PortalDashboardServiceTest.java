package group.aitools.nhs.platform.portal.dashboard;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.portal.dashboard.persistence.PortalDashboardMapper;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardAgentHealthRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardApiSummaryRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardSummaryRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardTokenTotalsRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardTokenTrendRow;
import group.aitools.nhs.platform.portal.dashboard.service.PortalDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PortalDashboardServiceTest {

    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        101L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );
    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        202L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private CurrentPrincipalProvider principalProvider;
    private PortalDashboardMapper mapper;
    private PortalDashboardService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        mapper = mock(PortalDashboardMapper.class);
        service = new PortalDashboardService(principalProvider, mapper);
    }

    @Test
    void adminStatsUsesEnterpriseExecutionAndMachineApiFacts() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        DashboardSummaryRow summary = new DashboardSummaryRow();
        summary.setTotalRuns(4L);
        summary.setSucceededRuns(3L);
        summary.setFailedRuns(1L);
        summary.setAverageLatencyMs(125D);
        DashboardTokenTotalsRow tokens = new DashboardTokenTotalsRow();
        tokens.setPromptTokens(10L);
        tokens.setCompletionTokens(20L);
        tokens.setTotalTokens(30L);
        DashboardApiSummaryRow api = new DashboardApiSummaryRow();
        api.setTotalCalls(5L);
        api.setSucceededCalls(4L);
        api.setErrorCalls(1L);
        api.setAverageDurationMs(25D);
        when(mapper.selectSummary(any(), any(), isNull())).thenReturn(summary);
        when(mapper.selectTokenTotals(any(), any(), isNull())).thenReturn(tokens);
        when(mapper.selectApiSummary(any(), any(), isNull())).thenReturn(api);
        when(mapper.countActiveUsers()).thenReturn(8L);
        when(mapper.countActiveUsersInRange(any(), any(), isNull())).thenReturn(3L);

        Map<String, Object> result = service.adminStats("today");

        assertEquals("enterprise", result.get("scope"));
        assertEquals(4L, ((Map<?, ?>) result.get("execution_runs")).get("total"));
        assertEquals(5L, ((Map<?, ?>) result.get("api_calls")).get("total"));
        assertEquals(80D, result.get("success_rate"));
        assertEquals(8L, result.get("total_users"));
        verify(mapper).selectSummary(any(), any(), isNull());
        verify(mapper).selectTokenTotals(any(), any(), isNull());
    }

    @Test
    void memberStatsIsBoundToTheCurrentPrincipal() {
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
        when(mapper.selectSummary(any(), any(), anyLong())).thenReturn(new DashboardSummaryRow());
        when(mapper.selectTokenTotals(any(), any(), anyLong())).thenReturn(new DashboardTokenTotalsRow());
        when(mapper.selectApiSummary(any(), any(), anyLong())).thenReturn(new DashboardApiSummaryRow());

        Map<String, Object> result = service.userStats("week");

        assertEquals("self", result.get("scope"));
        assertEquals("active", result.get("api_key_status"));
        assertEquals("unavailable", ((Map<?, ?>) result.get("total_users")).get("status"));
        verify(mapper).selectSummary(any(), any(), org.mockito.ArgumentMatchers.eq(202L));
        verify(mapper).selectTokenTotals(any(), any(), org.mockito.ArgumentMatchers.eq(202L));
    }

    @Test
    void tokenTrendsReturnEveryRequestedDayIncludingEmptyDays() {
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
        DashboardTokenTrendRow row = new DashboardTokenTrendRow();
        row.setDayBucket(LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0));
        row.setCalls(2L);
        row.setTotalTokens(9L);
        when(mapper.selectTokenTrends(any(), any(), anyLong())).thenReturn(List.of(row));

        List<Map<String, Object>> result = service.tokenStatsTrends(2, null, null);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(item ->
            "agent_conversation_message|agent_task_run".equals(item.get("source"))));
        assertEquals(0L, result.getFirst().get("total_tokens"));
        verify(mapper).selectTokenTrends(any(), any(), org.mockito.ArgumentMatchers.eq(202L));
    }

    @Test
    void agentStatsUsesRunStepHealthAndMachineApiMetrics() {
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
        DashboardAgentHealthRow health = new DashboardAgentHealthRow();
        health.setTotalSteps(5L);
        health.setSucceededSteps(4L);
        health.setToolCalls(2L);
        health.setAverageLatencyMs(30D);
        when(mapper.selectAgentHealth(any(), any(), anyLong())).thenReturn(health);
        when(mapper.selectHourlyHealth(any(), any(), anyLong())).thenReturn(List.of());
        when(mapper.selectToolUsage(any(), any(), anyLong())).thenReturn(List.of());
        when(mapper.selectRecentErrors(anyLong(), anyInt())).thenReturn(List.of());
        when(mapper.selectAgentPerformance(any(), any(), anyLong())).thenReturn(List.of());
        when(mapper.selectApiSummary(any(), any(), anyLong())).thenReturn(new DashboardApiSummaryRow());

        Map<String, Object> result = service.agentStats("month");

        assertEquals(80D, ((Map<?, ?>) result.get("health_stats")).get("success_rate"));
        assertEquals(2L, ((Map<?, ?>) result.get("health_stats")).get("total_tool_calls"));
        assertEquals("agent_api_call", ((Map<?, ?>) result.get("api_metrics")).get("source"));
    }
}
