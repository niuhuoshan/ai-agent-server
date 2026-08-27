package group.aitools.nhs.platform.connector;

import group.aitools.nhs.platform.audit.service.ToolInvocationAuditService;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.domain.AgentTool;
import group.aitools.nhs.platform.connector.mapper.ConnectorCatalogMapper;
import group.aitools.nhs.platform.connector.service.ApiToolExecutor;
import group.aitools.nhs.platform.connector.service.ConnectorMcpConnectionFactory;
import group.aitools.nhs.platform.connector.service.McpRemoteClient;
import group.aitools.nhs.platform.connector.service.SqlToolTemplateEngine;
import group.aitools.nhs.platform.connector.service.ToolArgumentValidator;
import group.aitools.nhs.platform.connector.service.ToolOnlineTestService;
import group.aitools.nhs.platform.connector.web.ToolOnlineTestRequest;
import group.aitools.nhs.platform.data.service.DataQueryExecutionService;
import group.aitools.nhs.platform.data.web.DataQueryRequest;
import group.aitools.nhs.platform.data.web.DataQueryResultView;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
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

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ToolOnlineTestServiceTest {

    private final CurrentPrincipal principal = new CurrentPrincipal(
        9L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );
    private ConnectorCatalogMapper mapper;
    private AuthorizationEnforcer authorization;
    private ConnectorMcpConnectionFactory connectionFactory;
    private McpRemoteClient remoteClient;
    private ApiToolExecutor apiToolExecutor;
    private ToolInvocationAuditService auditService;
    private DataQueryExecutionService queryExecutionService;
    private McpRemoteClient.Connection connection;
    private ToolOnlineTestService service;

    @BeforeEach
    void setUp() {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        when(principals.currentPrincipal()).thenReturn(principal);
        mapper = mock(ConnectorCatalogMapper.class);
        authorization = mock(AuthorizationEnforcer.class);
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.ALLOW));
        connectionFactory = mock(ConnectorMcpConnectionFactory.class);
        remoteClient = mock(McpRemoteClient.class);
        apiToolExecutor = mock(ApiToolExecutor.class);
        auditService = mock(ToolInvocationAuditService.class);
        queryExecutionService = mock(DataQueryExecutionService.class);
        connection = new McpRemoteClient.Connection(
            URI.create("https://mcp.example/rpc"), "streamable_http", "none", null, null,
            Duration.ofSeconds(1), Duration.ofSeconds(2)
        );
        service = new ToolOnlineTestService(
            principals, authorization, mapper, connectionFactory, remoteClient, apiToolExecutor,
            new ToolArgumentValidator(jsonMapper), new SqlToolTemplateEngine(),
            queryExecutionService, auditService, jsonMapper
        );
    }

    @Test
    void executesMcpTestAndRedactsSecretsFromReturnedData() {
        AgentTool tool = tool("mcp", "R1");
        AgentConnector connector = connector("mcp");
        stub(tool, connector);
        when(connectionFactory.create(connector)).thenReturn(connection);
        when(remoteClient.invoke(connection, "search", Map.of("query", "finance"))).thenReturn(
            new McpRemoteClient.InvocationResult(
                false, Map.of("items", List.of("one")), null, Map.of("accessToken", "raw-secret")
            )
        );

        var result = service.execute(
            500L, new ToolOnlineTestRequest(Map.of("query", "finance"), false)
        );

        assertTrue(result.ok());
        assertEquals("succeeded", result.status());
        assertTrue(result.data().toString().contains("[redacted]"));
        assertFalse(result.data().toString().contains("raw-secret"));
        verify(auditService).recordUiTest(eq(principal), eq(500L), any(), any(), eq(true), any());
    }

    @Test
    void returnsTypedFailureWhenProviderReportsAnError() {
        AgentTool tool = tool("mcp", "R1");
        AgentConnector connector = connector("mcp");
        stub(tool, connector);
        when(connectionFactory.create(connector)).thenReturn(connection);
        when(remoteClient.invoke(any(), any(), any())).thenReturn(
            new McpRemoteClient.InvocationResult(true, Map.of("message", "remote failed"), null, Map.of())
        );

        var result = service.execute(500L, new ToolOnlineTestRequest(Map.of("query", "x"), false));

        assertFalse(result.ok());
        assertEquals("provider_error", result.status());
        assertEquals("remote failed", result.error());
        verify(auditService).recordUiTest(eq(principal), eq(500L), any(), any(), eq(false), any());
    }

    @Test
    void rejectsInvalidArgumentsBeforeRemoteInvocation() {
        AgentTool tool = tool("mcp", "R1");
        stub(tool, connector("mcp"));

        assertThrows(
            ServiceException.class,
            () -> service.execute(500L, new ToolOnlineTestRequest(Map.of(), false))
        );

        verify(remoteClient, never()).invoke(any(), any(), any());
    }

    @Test
    void requiresExplicitRiskConfirmation() {
        stub(tool("mcp", "R2"), connector("mcp"));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.execute(
                500L, new ToolOnlineTestRequest(Map.of("query", "finance"), false)
            )
        );

        assertEquals(409, exception.getCode());
        verify(remoteClient, never()).invoke(any(), any(), any());
    }

    @Test
    void refusesApprovalRequiredToolOutsideTaskApprovalFlow() {
        stub(tool("mcp", "R1"), connector("mcp"));
        when(authorization.decide(any(), any()))
            .thenReturn(decision(PermissionEffect.APPROVAL_REQUIRED));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.execute(
                500L, new ToolOnlineTestRequest(Map.of("query", "finance"), false)
            )
        );

        assertEquals(409, exception.getCode());
        verify(remoteClient, never()).invoke(any(), any(), any());
    }

    @Test
    void executesSqlDiagnosticsThroughGovernedQueryServiceWithoutAConnector() {
        AgentTool tool = tool("sql", "R1");
        when(mapper.selectToolById(500L)).thenReturn(tool);
        when(queryExecutionService.executeWithTrace(any(), any())).thenReturn(
            new DataQueryResultView(
                990L, List.of("customer_id"), List.of(List.of("C-1")),
                1, 16, false, 4
            )
        );

        var result = service.execute(
            500L, new ToolOnlineTestRequest(Map.of("query", "C-1' OR TRUE --"), false)
        );
        ArgumentCaptor<DataQueryRequest> query = ArgumentCaptor.forClass(DataQueryRequest.class);

        assertTrue(result.ok());
        assertEquals("succeeded", result.status());
        assertTrue(result.data() instanceof Map<?, ?>);
        assertEquals(1L, ((Number) ((Map<?, ?>) result.data()).get("rowCount")).longValue());
        verify(queryExecutionService).executeWithTrace(query.capture(), any());
        assertEquals(800L, query.getValue().datasetId());
        assertTrue(query.getValue().sql().contains("'C-1'' OR TRUE --'"));
        verify(mapper, never()).selectConnectorById(any());
    }

    @Test
    void returnsTheRealGovernedSqlErrorToDiagnostics() {
        AgentTool tool = tool("sql", "R1");
        when(mapper.selectToolById(500L)).thenReturn(tool);
        when(queryExecutionService.executeWithTrace(any(), any())).thenThrow(
            new ServiceException("字段不属于当前活动数据集：public.orders.secret", 400)
        );

        var result = service.execute(
            500L, new ToolOnlineTestRequest(Map.of("query", "C-1"), false)
        );

        assertFalse(result.ok());
        assertEquals("query_error", result.status());
        assertEquals("字段不属于当前活动数据集：public.orders.secret", result.error());
        assertFalse(result.retryable());
        verify(auditService).recordUiTest(
            eq(principal), eq(500L), any(), eq(null), eq(false),
            org.mockito.ArgumentMatchers.startsWith("UI_SQL_TOOL_TEST_QUERY_ERROR:")
        );
    }

    private void stub(AgentTool tool, AgentConnector connector) {
        when(mapper.selectToolById(500L)).thenReturn(tool);
        when(mapper.selectConnectorById(7L)).thenReturn(connector);
    }

    private AgentTool tool(String type, String risk) {
        AgentTool tool = new AgentTool();
        tool.setId(500L);
        tool.setToolKey(type + ".reports.search");
        tool.setName("Search reports");
        tool.setConnectorId("sql".equals(type) ? null : 7L);
        tool.setToolType(type);
        tool.setRiskLevel(risk);
        tool.setParameterSchemaJson("""
            {
              "type":"object",
              "required":["query"],
              "properties":{"query":{"type":"string"}},
              "additionalProperties":false
            }
            """);
        tool.setExecutionPolicyJson("sql".equals(type) ? """
            {
              "datasetId":"800",
              "queryPurpose":"按客户查询订单",
              "sqlTemplate":"SELECT o.customer_id FROM public.orders o WHERE o.customer_id = {{query}}",
              "readOnly":true
            }
            """ : "{}");
        tool.setExternalName("sql".equals(type) ? null : "search");
        tool.setStatus("active");
        tool.setIsAvailable(true);
        return tool;
    }

    private AgentConnector connector(String type) {
        AgentConnector connector = new AgentConnector();
        connector.setId(7L);
        connector.setProviderType(type);
        connector.setStatus("active");
        return connector;
    }

    private AuthorizationDecision decision(PermissionEffect effect) {
        return new AuthorizationDecision(effect, effect.name(), effect.name(), List.of());
    }
}
