package group.aitools.nhs.platform.data;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.service.DataQueryExecutionService;
import group.aitools.nhs.platform.data.service.PlatformRuntimeDataQueryProvider;
import group.aitools.nhs.platform.connector.service.SqlToolTemplateEngine;
import group.aitools.nhs.platform.data.web.DataQueryRequest;
import group.aitools.nhs.platform.data.web.DataQueryResultView;
import group.aitools.nhs.platform.execution.service.FrozenRuntimePrincipalResolver;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PlatformRuntimeDataQueryProviderTest {

    private DataCatalogMapper mapper;
    private AuthorizationEnforcer authorization;
    private DataQueryExecutionService executionService;
    private PlatformRuntimeDataQueryProvider provider;

    @BeforeEach
    void setUp() {
        mapper = mock(DataCatalogMapper.class);
        authorization = mock(AuthorizationEnforcer.class);
        executionService = mock(DataQueryExecutionService.class);
        provider = new PlatformRuntimeDataQueryProvider(
            mapper, new FrozenRuntimePrincipalResolver(), authorization,
            executionService, new SqlToolTemplateEngine(), JsonMapper.builder().build()
        );
        when(mapper.countTaskDatasetQueryBinding(10L, 800L)).thenReturn(1);
        when(mapper.selectDataset(800L)).thenReturn(dataset());
        when(mapper.selectSource(700L)).thenReturn(source());
        when(mapper.selectTables(800L)).thenReturn(List.of(table()));
        when(mapper.selectColumns(800L)).thenReturn(List.of(
            column(901L, "customer_id", false),
            column(902L, "credential_ref", true)
        ));
        when(authorization.decide(any(), any())).thenReturn(
            new AuthorizationDecision(PermissionEffect.ALLOW, "ALLOW", "allowed", List.of())
        );
    }

    @Test
    void exposesOnlyNonSensitiveMetadataAsAnR1ReadOnlyTool() {
        var tools = provider.resolve(request("human", List.of("member")));

        assertEquals(1, tools.size());
        assertEquals(800L, tools.getFirst().id());
        assertEquals("R1", tools.getFirst().riskLevel());
        assertTrue(tools.getFirst().readOnly());
        assertTrue(tools.getFirst().description().contains("customer_id"));
        assertFalse(tools.getFirst().description().contains("credential_ref"));
    }

    @Test
    void invokeUsesFrozenRuntimeIdentityAndCurrentTaskGrant() {
        AgentRunRequest request = request("human", List.of("member"));
        DataQueryResultView expected = new DataQueryResultView(
            990L, List.of("customer_id"), List.of(List.of(1L)), 1, 16, false, 3
        );
        when(executionService.executeRuntime(any(), any(), eq("trace-10"))).thenReturn(expected);

        Object result = provider.invoke(request, 800L, Map.of(
            "question", "客户数", "sql", "SELECT o.customer_id FROM public.orders o"
        ));

        assertEquals(expected, result);
        ArgumentCaptor<group.aitools.nhs.platform.iam.domain.CurrentPrincipal> principal =
            ArgumentCaptor.forClass(group.aitools.nhs.platform.iam.domain.CurrentPrincipal.class);
        verify(executionService).executeRuntime(principal.capture(), any(), eq("trace-10"));
        assertEquals(9L, principal.getValue().id());
    }

    @Test
    void staticSchemaReturnsOnlyAuthorizedNonSensitiveMetadata() {
        Map<String, Object> result = provider.schema(
            request("human", List.of("member")), Map.of("keywords", "customer")
        );

        assertEquals("success", result.get("status"));
        assertEquals(1, result.get("dataset_count"));
        List<?> datasets = (List<?>) result.get("datasets");
        Map<?, ?> dataset = (Map<?, ?>) datasets.getFirst();
        assertEquals(800L, dataset.get("id"));
        List<?> tables = (List<?>) dataset.get("tables");
        List<?> columns = (List<?>) ((Map<?, ?>) tables.getFirst()).get("columns");
        assertEquals(1, columns.size());
        assertEquals("customer_id", ((Map<?, ?>) columns.getFirst()).get("column"));
        assertFalse(result.toString().contains("credential_ref"));
    }

    @Test
    void staticSqlUsesNhsNamesAndTheGovernedRuntimeExecutor() {
        AgentRunRequest request = request("human", List.of("member"));
        DataQueryResultView expected = new DataQueryResultView(
            991L, List.of("customer_id"), List.of(List.of(1L)), 1, 16, false, 4
        );
        when(executionService.executeRuntime(any(), any(), eq("trace-10"))).thenReturn(expected);

        Object result = provider.executeBuiltin(request, Map.of(
            "sql", "SELECT o.customer_id FROM public.orders o",
            "data_source", "warehouse",
            "dataset_name", "sales"
        ));

        assertEquals(expected, result);
        ArgumentCaptor<DataQueryRequest> query = ArgumentCaptor.forClass(DataQueryRequest.class);
        verify(executionService).executeRuntime(any(), query.capture(), eq("trace-10"));
        assertEquals(800L, query.getValue().datasetId());
        assertEquals("input", query.getValue().userQuery());
    }

    @Test
    void configuredSqlPreservesFrozenDatasetAndRendersTypedArguments() {
        AgentRunRequest request = request("human", List.of("member"));
        Map<String, Object> schema = sqlSchema();
        Map<String, Object> policy = sqlPolicy("800");
        DataQueryResultView expected = new DataQueryResultView(
            992L, List.of("customer_id"), List.of(List.of("C-1")), 1, 16, false, 4
        );
        when(executionService.executeRuntime(any(), any(), eq("trace-10"))).thenReturn(expected);

        assertTrue(provider.configuredAvailable(
            request, schema, policy, "sql.customer-orders"
        ));
        Object result = provider.executeConfigured(
            request, schema, policy, Map.of("customer", "C-1' OR TRUE --"),
            "sql.customer-orders"
        );
        ArgumentCaptor<DataQueryRequest> query = ArgumentCaptor.forClass(DataQueryRequest.class);

        assertEquals(expected, result);
        verify(executionService).executeRuntime(any(), query.capture(), eq("trace-10"));
        assertEquals(800L, query.getValue().datasetId());
        assertTrue(query.getValue().sql().contains("'C-1'' OR TRUE --'"));
        assertTrue(query.getValue().userQuery().contains("sql.customer-orders"));
    }

    @Test
    void configuredSqlRejectsDatasetMissingFromTaskSnapshot() {
        AgentRunRequest request = request("human", List.of("member"));

        assertFalse(provider.configuredAvailable(
            request, sqlSchema(), sqlPolicy("999"), "sql.customer-orders"
        ));
        ServiceException exception = assertThrows(ServiceException.class, () ->
            provider.executeConfigured(
                request, sqlSchema(), sqlPolicy("999"), Map.of("customer", "C-1"),
                "sql.customer-orders"
            )
        );

        assertEquals(403, exception.getCode());
    }

    @Test
    void tasklessConversationExecutesOnlyTheDatasetDeclaredByFrozenSqlTool() {
        AgentRunRequest request = sessionRequest();
        DataQueryResultView expected = new DataQueryResultView(
            993L, List.of("customer_id"), List.of(List.of("C-1")), 1, 16, false, 4
        );
        when(executionService.executeSessionRuntime(any(), any(), eq("trace-session")))
            .thenReturn(expected);

        assertTrue(provider.configuredAvailable(
            request, sqlSchema(), sqlPolicy("800"), "sql.customer-orders"
        ));
        Object result = provider.executeConfigured(
            request, sqlSchema(), sqlPolicy("800"), Map.of("customer", "C-1"),
            "sql.customer-orders"
        );
        ArgumentCaptor<DataQueryRequest> query = ArgumentCaptor.forClass(DataQueryRequest.class);

        assertEquals(expected, result);
        verify(executionService).executeSessionRuntime(any(), query.capture(), eq("trace-session"));
        assertEquals(800L, query.getValue().datasetId());
        assertEquals(null, query.getValue().taskId());
        assertEquals(77L, query.getValue().conversationId());
        verify(mapper, org.mockito.Mockito.never()).countTaskDatasetQueryBinding(any(), any());
    }

    @Test
    void tasklessConversationRejectsTamperedDatasetAndRevokedCurrentAccess() {
        AgentRunRequest request = sessionRequest();

        assertFalse(provider.configuredAvailable(
            request, sqlSchema(), sqlPolicy("999"), "sql.customer-orders"
        ));
        ServiceException tampered = assertThrows(ServiceException.class, () ->
            provider.executeConfigured(
                request, sqlSchema(), sqlPolicy("999"), Map.of("customer", "C-1"),
                "sql.customer-orders"
            )
        );
        assertEquals(403, tampered.getCode());

        when(authorization.decide(any(), any())).thenReturn(
            new AuthorizationDecision(PermissionEffect.DENY, "REVOKED", "revoked", List.of())
        );
        assertFalse(provider.configuredAvailable(
            request, sqlSchema(), sqlPolicy("800"), "sql.customer-orders"
        ));
        ServiceException revoked = assertThrows(ServiceException.class, () ->
            provider.executeConfigured(
                request, sqlSchema(), sqlPolicy("800"), Map.of("customer", "C-1"),
                "sql.customer-orders"
            )
        );
        assertEquals(403, revoked.getCode());
        verify(executionService, org.mockito.Mockito.never())
            .executeSessionRuntime(any(), any(), any());
    }

    @Test
    void tasklessConversationRejectsDisabledCurrentDataSource() {
        AgentDataSource disabled = source();
        disabled.setStatus("disabled");
        when(mapper.selectSource(700L)).thenReturn(disabled);
        AgentRunRequest request = sessionRequest();

        assertFalse(provider.configuredAvailable(
            request, sqlSchema(), sqlPolicy("800"), "sql.customer-orders"
        ));
        ServiceException exception = assertThrows(ServiceException.class, () ->
            provider.executeConfigured(
                request, sqlSchema(), sqlPolicy("800"), Map.of("customer", "C-1"),
                "sql.customer-orders"
            )
        );

        assertEquals(409, exception.getCode());
        verify(executionService, org.mockito.Mockito.never())
            .executeSessionRuntime(any(), any(), any());
    }

    @Test
    void staticSchemaRejectsDatasetOutsideTheFrozenSnapshot() {
        ServiceException exception = assertThrows(ServiceException.class, () -> provider.schema(
            request("human", List.of("member")), Map.of("dataset_id", 999L)
        ));

        assertEquals(403, exception.getCode());
    }

    @Test
    void revokedCurrentTaskBindingRemovesAndRejectsTheFrozenTool() {
        when(mapper.countTaskDatasetQueryBinding(10L, 800L)).thenReturn(0);
        AgentRunRequest request = request("human", List.of("member"));

        assertTrue(provider.resolve(request).isEmpty());
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> provider.invoke(request, 800L, Map.of("question", "q", "sql", "select"))
        );
        assertEquals(403, exception.getCode());
    }

    @Test
    void malformedOrMismatchedFrozenSnapshotFailsClosed() {
        AgentRunRequest source = request("human", List.of("member"));
        Map<String, Object> attributes = Map.of(
            "taskResourceSnapshot", Map.of(
                "agentVersionId", 999L,
                "resources", List.of(Map.of(
                    "resourceType", "dataset", "resourceId", 800L, "permission", "query"
                ))
            )
        );
        AgentRunRequest tampered = copy(source, attributes);

        assertThrows(SecurityException.class, () -> provider.resolve(tampered));
    }

    private AgentRunRequest request(String principalType, List<String> roles) {
        Map<String, Object> toolSnapshot = new java.util.LinkedHashMap<>();
        toolSnapshot.put("toolKey", "sql.customer-orders");
        toolSnapshot.put("toolType", "sql");
        toolSnapshot.put("connectorId", null);
        toolSnapshot.put("externalName", null);
        toolSnapshot.put("parameterSchema", sqlSchema());
        toolSnapshot.put("executionPolicy", sqlPolicy("800"));
        return new AgentRunRequest(
            new RuntimeExecutionKey("run-10", "trace-10"),
            9L, null, 10L, 11L, 12L, 100L, "agent", "session", "input", "system",
            new RuntimeModelConfig("openai", "model", null, "env:MODEL", Map.of()),
            "run-11", 10,
            Map.of("principalId", 9L, "principalType", principalType, "roles", roles),
            Map.of(
                "taskResourceSnapshot", Map.of(
                    "agentVersionId", 100L,
                    "resources", List.of(Map.of(
                        "resourceType", "dataset", "resourceId", 800L, "permission", "query"
                    ))
                ),
                "resourceBindings", List.of(Map.of(
                    "resourceType", "tool",
                    "resourceId", 500L,
                    "permission", "invoke",
                    "config", Map.of("resourceSnapshot", toolSnapshot)
                ))
            )
        );
    }

    private AgentRunRequest sessionRequest() {
        AgentRunRequest task = request("human", List.of("member"));
        Map<String, Object> attributes = new java.util.LinkedHashMap<>(task.attributes());
        attributes.put("taskResourceSnapshot", Map.of(
            "agentVersionId", 100L,
            "resources", List.of(Map.of(
                "resourceType", "tool", "resourceId", 500L, "permission", "use"
            ))
        ));
        return new AgentRunRequest(
            new RuntimeExecutionKey("conversation-turn-78", "trace-session"),
            9L, 77L, null, null, null, 100L, "agent", "session", "input", "system",
            task.model(), null, 10, task.authorizationSnapshot(), Map.copyOf(attributes)
        );
    }

    private AgentRunRequest copy(AgentRunRequest source, Map<String, Object> attributes) {
        return new AgentRunRequest(
            source.executionKey(), source.userId(), source.conversationId(), source.taskId(),
            source.runId(), source.stepId(), source.agentVersionId(), source.agentName(),
            source.sessionId(), source.input(), source.systemPrompt(), source.model(),
            source.workspaceKey(), source.maxIterations(), source.authorizationSnapshot(), attributes
        );
    }

    private AgentDataSource source() {
        AgentDataSource source = new AgentDataSource();
        source.setId(700L);
        source.setSourceKey("warehouse");
        source.setName("经营数据仓库");
        source.setDbType("postgresql");
        source.setStatus("active");
        return source;
    }

    private Map<String, Object> sqlSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of("customer", Map.of("type", "string")),
            "required", List.of("customer"),
            "additionalProperties", false
        );
    }

    private Map<String, Object> sqlPolicy(String datasetId) {
        return Map.of(
            "datasetId", datasetId,
            "queryPurpose", "按客户查询订单",
            "sqlTemplate", "SELECT o.customer_id FROM public.orders o WHERE o.customer_id = {{customer}}",
            "readOnly", true
        );
    }

    private AgentDataDataset dataset() {
        AgentDataDataset dataset = new AgentDataDataset();
        dataset.setId(800L);
        dataset.setDataSourceId(700L);
        dataset.setDatasetKey("sales");
        dataset.setName("销售数据集");
        dataset.setOwnerId(9L);
        dataset.setStatus("active");
        return dataset;
    }

    private AgentDataTable table() {
        AgentDataTable table = new AgentDataTable();
        table.setId(900L);
        table.setPhysicalSchema("public");
        table.setPhysicalName("orders");
        table.setStatus("active");
        table.setMetadataPresent(true);
        return table;
    }

    private AgentDataColumn column(Long id, String name, boolean sensitive) {
        AgentDataColumn column = new AgentDataColumn();
        column.setId(id);
        column.setTableId(900L);
        column.setPhysicalName(name);
        column.setStatus("active");
        column.setMetadataPresent(true);
        column.setIsSensitive(sensitive);
        return column;
    }
}
