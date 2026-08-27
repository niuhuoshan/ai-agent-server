package group.aitools.nhs.platform.connector;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.domain.AgentTool;
import group.aitools.nhs.platform.connector.mapper.ConnectorCatalogMapper;
import group.aitools.nhs.platform.connector.service.ConnectorConfigurationValidator;
import group.aitools.nhs.platform.connector.service.SqlToolTemplateEngine;
import group.aitools.nhs.platform.connector.service.ToolCatalogService;
import group.aitools.nhs.platform.connector.web.CreateToolRequest;
import group.aitools.nhs.platform.data.service.DataQueryExecutionService;
import group.aitools.nhs.platform.data.web.DataQueryRequest;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ToolCatalogServiceTest {

    private ConnectorCatalogMapper mapper;
    private DataQueryExecutionService queryExecutionService;
    private ToolCatalogService service;

    @BeforeEach
    void setUp() {
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        when(principals.currentPrincipal()).thenReturn(new CurrentPrincipal(
            9L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
        ));
        mapper = mock(ConnectorCatalogMapper.class);
        when(mapper.selectToolVersions(any())).thenReturn(List.of());
        AgentConnector connector = new AgentConnector();
        connector.setId(7L);
        connector.setProviderType("api");
        connector.setStatus("active");
        when(mapper.selectConnectorById(7L)).thenReturn(connector);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        when(ids.nextId()).thenReturn(500L);
        JsonMapper jsonMapper = JsonMapper.builder().build();
        queryExecutionService = mock(DataQueryExecutionService.class);
        service = new ToolCatalogService(
            principals, mock(AuthorizationEnforcer.class), ids, mapper,
            new ConnectorConfigurationValidator(jsonMapper), new SqlToolTemplateEngine(),
            queryExecutionService, jsonMapper
        );
    }

    @Test
    void refusesLowRiskLabelsForMutatingOrDeleteApiTools() {
        assertThrows(
            ServiceException.class,
            () -> service.create(request("POST", "R1", false))
        );
        assertThrows(
            ServiceException.class,
            () -> service.create(request("DELETE", "R2", false))
        );
    }

    @Test
    void acceptsExplicitReadOnlyGetAndFreezesExecutionPolicy() {
        service.create(request("GET", "R1", true));
        ArgumentCaptor<AgentTool> inserted = ArgumentCaptor.forClass(AgentTool.class);

        verify(mapper).insertTool(inserted.capture());
        assertEquals("R1", inserted.getValue().getRiskLevel());
        assertEquals(
            Map.of("method", "GET", "path", "/reports", "readOnly", true),
            JsonMapper.builder().build().readValue(
                inserted.getValue().getExecutionPolicyJson(), Object.class
            )
        );
    }

    @Test
    void validatesAndPersistsGovernedSqlToolConfiguration() {
        service.create(new CreateToolRequest(
            "sql.customer-orders", "Customer orders", "Read customer orders", null,
            "sql", "R1",
            Map.of(
                "type", "object",
                "properties", Map.of("customer", Map.of("type", "string")),
                "required", List.of("customer"),
                "additionalProperties", false
            ),
            Map.of(
                "datasetId", "800",
                "queryPurpose", "按客户查询订单",
                "sqlTemplate", "SELECT o.customer_id FROM public.orders o WHERE o.customer_id = {{customer}}",
                "readOnly", true
            ),
            null, "active"
        ));
        ArgumentCaptor<DataQueryRequest> validation = ArgumentCaptor.forClass(DataQueryRequest.class);
        ArgumentCaptor<AgentTool> inserted = ArgumentCaptor.forClass(AgentTool.class);

        verify(queryExecutionService).validateForPrincipal(any(), validation.capture());
        verify(mapper).insertTool(inserted.capture());
        assertEquals(800L, validation.getValue().datasetId());
        assertEquals(
            "SELECT o.customer_id FROM public.orders o WHERE o.customer_id = 'sample'",
            validation.getValue().sql()
        );
        assertEquals("sql", inserted.getValue().getToolType());
        assertTrue(inserted.getValue().getExecutionPolicyJson().contains("sqlTemplate"));
    }

    @Test
    void acceptsSqlTemplatesAboveTheFormerEightKilobyteTextLimit() {
        String sql = "SELECT 1 AS value\n" + "-- governed query\n".repeat(600);

        service.create(new CreateToolRequest(
            "sql.long-report", "Long report", null, null, "sql", "R1",
            Map.of("type", "object", "properties", Map.of(), "required", List.of(), "additionalProperties", false),
            Map.of(
                "datasetId", "800", "queryPurpose", "长 SQL 模板回归验证",
                "sqlTemplate", sql, "readOnly", true
            ),
            null, "active"
        ));

        ArgumentCaptor<AgentTool> inserted = ArgumentCaptor.forClass(AgentTool.class);
        verify(mapper).insertTool(inserted.capture());
        assertTrue(inserted.getValue().getExecutionPolicyJson().length() > 8192);
    }

    @Test
    void deletesCompleteManualToolFamilyWhenUnreferenced() {
        AgentTool tool = tool("api", "api.reports");
        when(mapper.selectToolById(500L)).thenReturn(tool);
        when(mapper.softDeleteToolFamily(any(), any(), any())).thenReturn(2);

        service.delete(500L);

        verify(mapper).countActiveToolFamilyReferences("api.reports");
        verify(mapper).softDeleteToolFamily(any(), any(), any());
    }

    @Test
    void refusesToDeleteReferencedOrMcpManagedToolFamilies() {
        AgentTool api = tool("api", "api.reports");
        when(mapper.selectToolById(500L)).thenReturn(api);
        when(mapper.countActiveToolFamilyReferences("api.reports")).thenReturn(1);

        assertThrows(ServiceException.class, () -> service.delete(500L));

        AgentTool mcp = tool("mcp", "mcp.reports.search");
        when(mapper.selectToolById(501L)).thenReturn(mcp);
        assertThrows(ServiceException.class, () -> service.delete(501L));
    }

    private AgentTool tool(String type, String key) {
        AgentTool tool = new AgentTool();
        tool.setId("mcp".equals(type) ? 501L : 500L);
        tool.setToolKey(key);
        tool.setToolType(type);
        tool.setStatus("active");
        tool.setIsAvailable(true);
        return tool;
    }

    private CreateToolRequest request(String method, String risk, boolean readOnly) {
        return new CreateToolRequest(
            "api.reports", "Reports", "read reports", 7L, "api", risk,
            Map.of("type", "object"),
            Map.of("method", method, "path", "/reports", "readOnly", readOnly),
            "reports", "active"
        );
    }
}
