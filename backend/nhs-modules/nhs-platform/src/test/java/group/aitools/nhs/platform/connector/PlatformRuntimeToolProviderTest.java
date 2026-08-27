package group.aitools.nhs.platform.connector;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.runtime.spi.RuntimeToolDefinition;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventStatus;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.runtime.spi.RuntimeSensitiveLevel;
import group.aitools.nhs.platform.audit.service.ToolInvocationAuditService;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.domain.AgentTool;
import group.aitools.nhs.platform.connector.mapper.ConnectorCatalogMapper;
import group.aitools.nhs.platform.connector.service.ApiToolExecutor;
import group.aitools.nhs.platform.connector.service.BuiltinToolCatalog;
import group.aitools.nhs.platform.connector.service.ConnectorMcpConnectionFactory;
import group.aitools.nhs.platform.connector.service.McpRemoteClient;
import group.aitools.nhs.platform.connector.service.PlatformRuntimeToolProvider;
import group.aitools.nhs.platform.connector.service.ToolArgumentValidator;
import group.aitools.nhs.platform.execution.service.FrozenRuntimePrincipalResolver;
import group.aitools.nhs.platform.data.service.PlatformRuntimeDataQueryProvider;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.sandbox.service.SandboxJobQueueService;
import group.aitools.nhs.platform.sandbox.service.SandboxJobSubmission;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PlatformRuntimeToolProviderTest {

    private ConnectorCatalogMapper mapper;
    private AuthorizationEnforcer authorization;
    private ConnectorMcpConnectionFactory connectionFactory;
    private McpRemoteClient remoteClient;
    private ToolInvocationAuditService audit;
    private PlatformRuntimeDataQueryProvider dataQueryProvider;
    private PlatformRuntimeToolProvider provider;
    private SandboxJobQueueService sandboxQueue;

    @BeforeEach
    void setUp() {
        mapper = mock(ConnectorCatalogMapper.class);
        authorization = mock(AuthorizationEnforcer.class);
        connectionFactory = mock(ConnectorMcpConnectionFactory.class);
        remoteClient = mock(McpRemoteClient.class);
        audit = mock(ToolInvocationAuditService.class);
        dataQueryProvider = mock(PlatformRuntimeDataQueryProvider.class);
        sandboxQueue = mock(SandboxJobQueueService.class);
        when(dataQueryProvider.resolve(any())).thenReturn(List.of());
        JsonMapper jsonMapper = JsonMapper.builder().build();
        provider = new PlatformRuntimeToolProvider(
            mapper, new FrozenRuntimePrincipalResolver(), authorization, connectionFactory, remoteClient,
            mock(ApiToolExecutor.class),
            new ToolArgumentValidator(jsonMapper), audit, dataQueryProvider, jsonMapper
        );
    }

    @Test
    void sandboxIsExternalR2AndQueuesOnlyAfterFrozenDenyFirstChecks() {
        stubSandboxTool();
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.ALLOW));
        JsonMapper jsonMapper = JsonMapper.builder().build();
        provider = new PlatformRuntimeToolProvider(
            mapper, new FrozenRuntimePrincipalResolver(), authorization, connectionFactory, remoteClient,
            mock(ApiToolExecutor.class), new ToolArgumentValidator(jsonMapper), audit,
            dataQueryProvider, sandboxQueue, jsonMapper
        );
        AgentRunRequest request = sandboxRequest(true);
        when(sandboxQueue.enqueue(any())).thenReturn(
            new SandboxJobQueueService.SandboxJobTicket(
                900L, "b".repeat(64), "queued", java.time.LocalDateTime.now()
            )
        );
        ArgumentCaptor<SandboxJobSubmission> submission = ArgumentCaptor.forClass(
            SandboxJobSubmission.class
        );

        List<RuntimeToolDefinition> definitions = provider.resolve(request);
        provider.enqueueExternalExecution(request, sandboxEvent(
            List.of("python", "-c", "print('ok')", ";rm -rf /", "$(id)")
        ));

        assertEquals(1, definitions.size());
        assertEquals("R2", definitions.getFirst().riskLevel());
        assertTrue(definitions.getFirst().externalExecution());
        verify(sandboxQueue).enqueue(submission.capture());
        assertEquals("python-3.11", submission.getValue().templateKey());
        assertEquals(";rm -rf /", submission.getValue().argv().get(3));
        verify(audit).record(
            any(), eq(500L), any(), any(), eq(true), eq("SANDBOX_JOB_QUEUED")
        );
    }

    @Test
    void explicitDenyPreventsSandboxJobCreation() {
        stubSandboxTool();
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.DENY));
        JsonMapper jsonMapper = JsonMapper.builder().build();
        provider = new PlatformRuntimeToolProvider(
            mapper, new FrozenRuntimePrincipalResolver(), authorization, connectionFactory, remoteClient,
            mock(ApiToolExecutor.class), new ToolArgumentValidator(jsonMapper), audit,
            dataQueryProvider, sandboxQueue, jsonMapper
        );

        assertThrows(ServiceException.class, () -> provider.enqueueExternalExecution(
            sandboxRequest(true), sandboxEvent(List.of("python"))
        ));

        verify(sandboxQueue, never()).enqueue(any());
    }

    @Test
    void sandboxWithFrozenSkillBindingFailsClosedUntilRunnerWorkspaceBridgeExists() {
        stubSandboxTool();
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.ALLOW));
        JsonMapper jsonMapper = JsonMapper.builder().build();
        provider = new PlatformRuntimeToolProvider(
            mapper, new FrozenRuntimePrincipalResolver(), authorization, connectionFactory, remoteClient,
            mock(ApiToolExecutor.class), new ToolArgumentValidator(jsonMapper), audit,
            dataQueryProvider, sandboxQueue, jsonMapper
        );
        AgentRunRequest source = sandboxRequest(true);
        Map<String, Object> attributes = new java.util.LinkedHashMap<>(source.attributes());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindings = new java.util.ArrayList<>(
            (List<Map<String, Object>>) attributes.get("resourceBindings")
        );
        bindings.add(Map.of(
            "resourceType", "skill", "resourceId", 700L, "permission", "invoke",
            "config", Map.of("resourceSnapshot", Map.of(
                "skillKey", "reviewer", "versionId", 701L
            ))
        ));
        attributes.put("resourceBindings", bindings);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> provider.enqueueExternalExecution(
                copy(source, attributes), sandboxEvent(List.of("python"))
            )
        );
        assertEquals(503, exception.getCode());
        assertTrue(exception.getMessage().contains("skill_unavailable"));
        verify(sandboxQueue, never()).enqueue(any());
    }

    @Test
    void resolvesFrozenTaskAgentIntersectionAndElevatesIamApprovalToR2() {
        stubCurrentTool(true);
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.APPROVAL_REQUIRED));

        List<RuntimeToolDefinition> tools = provider.resolve(request("human", List.of("member")));

        assertEquals(1, tools.size());
        assertEquals("platform_tool_500", tools.getFirst().name());
        assertEquals("R2", tools.getFirst().riskLevel());
        assertEquals("object", tools.getFirst().inputSchema().get("type"));
    }

    @Test
    void explicitDenyRemovesToolFromRuntimeCatalog() {
        stubCurrentTool(true);
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.DENY));

        assertTrue(provider.resolve(request("human", List.of("member"))).isEmpty());

        verify(remoteClient, never()).invoke(any(), any(), any());
    }

    @Test
    void connectorDisableImmediatelyRejectsFrozenTool() {
        stubCurrentTool(false);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> provider.invoke(request("human", List.of("member")), 500L, Map.of("query", "x"))
        );

        assertEquals(403, exception.getCode());
        verify(remoteClient, never()).invoke(any(), any(), any());
    }

    @Test
    void validatesArgumentsInvokesMcpAndWritesHashOnlyOutcomeAudit() {
        AgentConnector connector = stubCurrentTool(true);
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.ALLOW));
        McpRemoteClient.Connection connection = new McpRemoteClient.Connection(
            URI.create("https://mcp.example/rpc"), "streamable_http", "none", null, null,
            Duration.ofSeconds(1), Duration.ofSeconds(2)
        );
        when(connectionFactory.create(connector)).thenReturn(connection);
        when(remoteClient.invoke(connection, "search", Map.of("query", "finance"))).thenReturn(
            new McpRemoteClient.InvocationResult(false, List.of("ok"), Map.of("count", 1), Map.of())
        );

        Object result = provider.invoke(
            request("human", List.of("member")), 500L, Map.of("query", "finance")
        );

        assertTrue(result instanceof Map<?, ?>);
        verify(audit).record(
            any(), eq(500L), eq("{\"query\":\"finance\"}"), any(), eq(true),
            eq("MCP_TOOL_SUCCEEDED")
        );
    }

    @Test
    void rejectsMissingRequiredArgumentBeforeRemoteInvocation() {
        stubCurrentTool(true);
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.ALLOW));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> provider.invoke(request("human", List.of("member")), 500L, Map.of())
        );

        assertEquals(400, exception.getCode());
        verify(remoteClient, never()).invoke(any(), any(), any());
    }

    @Test
    void serviceAccountNeverRehydratesHumanRoles() {
        stubCurrentTool(true);
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.ALLOW));
        ArgumentCaptor<CurrentPrincipal> principal = ArgumentCaptor.forClass(CurrentPrincipal.class);

        provider.resolve(request("service_account", List.of("platform_admin", "member")));

        verify(authorization).decide(principal.capture(), any());
        assertEquals(java.util.Set.of(PlatformRole.SERVICE_ACCOUNT), principal.getValue().roles());
    }

    @Test
    void taskSnapshotCannotBeBypassedByAgentBinding() {
        AgentRunRequest source = request("human", List.of("member"));
        Map<String, Object> attributes = new java.util.LinkedHashMap<>(source.attributes());
        attributes.put("taskResourceSnapshot", Map.of(
            "agentVersionId", 100L,
            "resources", List.of()
        ));
        AgentRunRequest tampered = copy(source, attributes);

        assertThrows(SecurityException.class, () -> provider.resolve(tampered));
    }

    @Test
    void delegatesFrozenDatasetToolsToTheReadOnlyDataProvider() {
        AgentRunRequest request = request("human", List.of("member"));
        when(dataQueryProvider.supports(request, 800L)).thenReturn(true);
        when(dataQueryProvider.invoke(request, 800L, Map.of("question", "q", "sql", "select")))
            .thenReturn(Map.of("rowCount", 1));

        Object result = provider.invoke(
            request, 800L, Map.of("question", "q", "sql", "select")
        );

        assertEquals(Map.of("rowCount", 1), result);
        verify(dataQueryProvider).invoke(
            request, 800L, Map.of("question", "q", "sql", "select")
        );
    }

    @Test
    void executesBuiltinCurrentTimeThroughTheSameFrozenAuthorizationBoundary() {
        AgentRunRequest request = builtinRequest("get_current_time", 501L);
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.ALLOW));

        List<RuntimeToolDefinition> definitions = provider.resolve(request);
        Object raw = provider.invoke(request, 501L, Map.of("timezone", "UTC"));

        assertEquals(1, definitions.size());
        assertEquals("platform_tool_501", definitions.getFirst().name());
        assertTrue(raw instanceof Map<?, ?>);
        Map<?, ?> result = (Map<?, ?>) raw;
        assertEquals(true, result.get("ok"));
        assertEquals("success", result.get("status"));
        assertTrue(((Map<?, ?>) result.get("data")).containsKey("iso"));
        verify(audit).record(any(), eq(501L), any(), any(), eq(true), eq("BUILTIN_TOOL_SUCCEEDED"));
    }

    @Test
    void delegatesStaticDatasetSchemaToTheGovernedDataProvider() {
        AgentRunRequest request = builtinRequest("get_dataset_schema", 504L);
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.ALLOW));
        when(dataQueryProvider.schema(request, Map.of("keywords", "sales"))).thenReturn(Map.of(
            "status", "success", "dataset_count", 1, "datasets", List.of()
        ));

        Map<?, ?> envelope = (Map<?, ?>) provider.invoke(
            request, 504L, Map.of("keywords", "sales")
        );

        assertEquals("success", envelope.get("status"));
        assertEquals(1, ((Map<?, ?>) envelope.get("data")).get("dataset_count"));
        verify(dataQueryProvider).schema(request, Map.of("keywords", "sales"));
    }

    @Test
    void delegatesStaticSqlToTheGovernedDataProvider() {
        AgentRunRequest request = builtinRequest("execute_sql_query", 505L);
        Map<String, Object> arguments = Map.of(
            "sql", "SELECT total FROM public.sales",
            "data_source", "warehouse",
            "dataset_name", "sales"
        );
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.ALLOW));
        when(dataQueryProvider.executeBuiltin(request, arguments)).thenReturn(Map.of("rowCount", 2));

        Map<?, ?> envelope = (Map<?, ?>) provider.invoke(request, 505L, arguments);

        assertEquals(2, ((Map<?, ?>) envelope.get("data")).get("rowCount"));
        verify(dataQueryProvider).executeBuiltin(request, arguments);
    }

    @Test
    void executesFrozenManualSqlThroughConfiguredGovernedProvider() {
        AgentRunRequest request = manualSqlRequest();
        Map<String, Object> arguments = Map.of("customer", "C-1");
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.ALLOW));
        when(dataQueryProvider.configuredAvailable(any(), any(), any(), any())).thenReturn(true);
        when(dataQueryProvider.executeConfigured(
            request, sqlSchema(), sqlPolicy(), arguments, "sql.customer-orders"
        )).thenReturn(Map.of("rowCount", 1));

        List<RuntimeToolDefinition> definitions = provider.resolve(request);
        Object result = provider.invoke(request, 506L, arguments);

        assertEquals(1, definitions.size());
        assertEquals("platform_tool_506", definitions.getFirst().name());
        assertTrue(result instanceof Map<?, ?>);
        Map<?, ?> envelope = (Map<?, ?>) result;
        assertTrue((Boolean) envelope.get("ok"));
        assertEquals("success", envelope.get("status"));
        assertEquals(1, ((Number) ((Map<?, ?>) envelope.get("data")).get("rowCount")).intValue());
        verify(dataQueryProvider).executeConfigured(
            request, sqlSchema(), sqlPolicy(), arguments, "sql.customer-orders"
        );
        verify(audit).record(any(), eq(506L), any(), any(), eq(true), eq("SQL_TOOL_SUCCEEDED"));
    }

    @Test
    void executesFrozenManualSqlInTasklessConversationScope() {
        AgentRunRequest request = sessionSqlRequest();
        Map<String, Object> arguments = Map.of("customer", "C-1");
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.ALLOW));
        when(dataQueryProvider.configuredAvailable(any(), any(), any(), any())).thenReturn(true);
        when(dataQueryProvider.executeConfigured(
            request, sqlSchema(), sqlPolicy(), arguments, "sql.customer-orders"
        )).thenReturn(Map.of("rowCount", 1));

        assertEquals(1, provider.resolve(request).size());
        Object result = provider.invoke(request, 506L, arguments);

        Map<?, ?> envelope = (Map<?, ?>) result;
        assertTrue((Boolean) envelope.get("ok"));
        assertEquals("success", envelope.get("status"));
        assertEquals(1, ((Number) ((Map<?, ?>) envelope.get("data")).get("rowCount")).intValue());
        verify(dataQueryProvider).executeConfigured(
            request, sqlSchema(), sqlPolicy(), arguments, "sql.customer-orders"
        );
    }

    @Test
    void preservesConfiguredSqlFailureStatusAndAuditOutcome() {
        AgentRunRequest request = manualSqlRequest();
        Map<String, Object> arguments = Map.of("customer", "C-1");
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.ALLOW));
        when(dataQueryProvider.configuredAvailable(any(), any(), any(), any())).thenReturn(true);
        when(dataQueryProvider.executeConfigured(
            request, sqlSchema(), sqlPolicy(), arguments, "sql.customer-orders"
        )).thenReturn(Map.of(
            "status", "query_error",
            "error", "数据源超时",
            "retryable", true
        ));

        Map<?, ?> result = (Map<?, ?>) provider.invoke(request, 506L, arguments);

        assertEquals(false, result.get("ok"));
        assertEquals("query_error", result.get("status"));
        assertEquals(true, result.get("retryable"));
        assertEquals("数据源超时", result.get("error"));
        verify(audit).record(any(), eq(506L), any(), any(), eq(false), eq("SQL_TOOL_FAILED"));
    }

    @Test
    void reportsUnknownBuiltinAsUnavailableInsteadOfSilentlyFilteringIt() {
        AgentRunRequest request = builtinRequest("future_tool", 502L);
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.ALLOW));

        assertEquals(0, provider.resolve(request).size());
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> provider.invoke(request, 502L, Map.of())
        );

        assertEquals(503, exception.getCode());
        verify(audit).record(any(), eq(502L), any(), eq(null), eq(false),
            org.mockito.ArgumentMatchers.startsWith("TOOL_INVOCATION_EXCEPTION:"));
    }

    @Test
    void listsOnlyTaskFrozenResourcesForBuiltinCatalogTools() {
        AgentRunRequest request = builtinRequest("list_accessible_datasets", 503L);
        Map<String, Object> attributes = new java.util.LinkedHashMap<>(request.attributes());
        attributes.put("taskResourceSnapshot", Map.of(
            "agentVersionId", 100L,
            "resources", List.of(
                Map.of("resourceType", "dataset", "resourceId", 701L, "permission", "query"),
                Map.of("resourceType", "dataset", "resourceId", 702L, "permission", "admin"),
                Map.of("resourceType", "tool", "resourceId", 503L, "permission", "use"),
                Map.of("resourceType", "tool", "resourceId", 900L, "permission", "use")
            )
        ));
        request = copy(request, attributes);
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.ALLOW));
        when(dataQueryProvider.accessibleCatalog(request)).thenReturn(List.of(
            Map.of("resource_id", 701L, "permission", "query"),
            Map.of("resource_id", 702L, "permission", "admin")
        ));

        Map<?, ?> envelope = (Map<?, ?>) provider.invoke(request, 503L, Map.of());
        List<?> resources = (List<?>) envelope.get("data");

        assertEquals(2, resources.size());
        assertEquals(701L, ((Number) ((Map<?, ?>) resources.getFirst()).get("resource_id")).longValue());
        assertEquals(702L, ((Number) ((Map<?, ?>) resources.get(1)).get("resource_id")).longValue());
    }

    @Test
    void keepsTheCompleteNhsBuiltinLedgerVisibleToRuntimeValidation() {
        assertEquals(82, BuiltinToolCatalog.names().size());
        assertTrue(BuiltinToolCatalog.contains("get_dataset_schema"));
        assertTrue(BuiltinToolCatalog.contains("resolve_relative_dates"));
        assertTrue(BuiltinToolCatalog.descriptor("get_current_time").get("registered").equals(true));
        assertEquals("local", BuiltinToolCatalog.descriptor("get_dataset_schema").get("execution"));
        assertEquals("local", BuiltinToolCatalog.descriptor("execute_sql_query").get("execution"));
        assertEquals("local", BuiltinToolCatalog.descriptor("create_recurring_task").get("execution"));
        assertEquals("local", BuiltinToolCatalog.descriptor("browser_wait_for").get("execution"));
        assertEquals("local", BuiltinToolCatalog.descriptor("browser_download").get("execution"));
        Map<?, ?> createSchema = (Map<?, ?>) BuiltinToolCatalog.descriptor(
            "create_recurring_task"
        ).get("parameterSchema");
        assertEquals(List.of("name", "cron", "prompt"), createSchema.get("required"));
        Map<?, ?> cancelSchema = (Map<?, ?>) BuiltinToolCatalog.descriptor(
            "cancel_task"
        ).get("parameterSchema");
        assertEquals(List.of("task_id"), cancelSchema.get("required"));
        assertEquals(false, cancelSchema.get("additionalProperties"));
    }

    private AgentConnector stubCurrentTool(boolean activeConnector) {
        AgentTool tool = new AgentTool();
        tool.setId(500L);
        tool.setToolKey("mcp.search");
        tool.setToolType("mcp");
        tool.setRiskLevel("R1");
        tool.setVersionNo(3);
        tool.setConnectorId(700L);
        tool.setExternalName("search");
        tool.setStatus("active");
        tool.setIsAvailable(true);
        when(mapper.selectToolById(500L)).thenReturn(tool);

        AgentConnector connector = new AgentConnector();
        connector.setId(700L);
        connector.setProviderType("mcp");
        connector.setStatus(activeConnector ? "active" : "disabled");
        when(mapper.selectConnectorById(700L)).thenReturn(connector);
        return connector;
    }

    private void stubSandboxTool() {
        AgentTool tool = new AgentTool();
        tool.setId(500L);
        tool.setToolKey("sandbox.python");
        tool.setToolType("sandbox");
        tool.setRiskLevel("R1");
        tool.setVersionNo(3);
        tool.setStatus("active");
        tool.setIsAvailable(true);
        when(mapper.selectToolById(500L)).thenReturn(tool);
    }

    private AgentRunRequest sandboxRequest(boolean taskGrant) {
        Map<String, Object> toolSnapshot = new java.util.LinkedHashMap<>();
        toolSnapshot.put("toolKey", "sandbox.python");
        toolSnapshot.put("name", "Python Sandbox");
        toolSnapshot.put("description", "Execute argv in an isolated container");
        toolSnapshot.put("toolType", "sandbox");
        toolSnapshot.put("riskLevel", "R1");
        toolSnapshot.put("versionNo", 3);
        toolSnapshot.put("parameterSchema", Map.of(
            "type", "object",
            "properties", Map.of("argv", Map.of(
                "type", "array", "items", Map.of("type", "string"), "minItems", 1
            )),
            "required", List.of("argv"),
            "additionalProperties", false
        ));
        toolSnapshot.put("executionPolicy", Map.of(
            "templateKey", "python-3.11",
            "networkPolicy", "none",
            "workspaceAccess", "read_write",
            "timeoutMs", 300000,
            "memoryMb", 512,
            "cpuMillis", 1000,
            "pidsLimit", 128,
            "maxOutputBytes", 1048576
        ));
        List<Map<String, Object>> taskResources = taskGrant
            ? List.of(Map.of(
                "resourceType", "tool", "resourceId", 500L, "permission", "use"
            )) : List.of();
        Map<String, Object> attributes = Map.of(
            "taskResourceSnapshot", Map.of(
                "agentVersionId", 100L, "resources", taskResources
            ),
            "resourceBindings", List.of(Map.of(
                "resourceType", "tool", "resourceId", 500L, "permission", "invoke",
                "config", Map.of("resourceSnapshot", toolSnapshot)
            ))
        );
        return new AgentRunRequest(
            new RuntimeExecutionKey("run-10", "trace-10"),
            9L, null, 10L, 11L, 12L, 100L, "agent", "session", "input", "system",
            new RuntimeModelConfig("openai", "model", null, "env:MODEL_KEY", Map.of()),
            "run-11", 10,
            Map.of("principalId", 9L, "principalType", "human", "roles", List.of("member")),
            attributes
        );
    }

    private RuntimeEvent sandboxEvent(List<String> argv) {
        return new RuntimeEvent(
            "external-event", new RuntimeExecutionKey("run-10", "trace-10"),
            null, 11L, 12L, RuntimeEventType.EXTERNAL_EXECUTION_REQUIRED,
            RuntimeEventStatus.PENDING, Instant.now(), "external", Map.of(
                "replyId", "reply-1",
                "toolCalls", List.of(Map.of(
                    "id", "call-1", "name", "platform_tool_500",
                    "input", Map.of("argv", argv)
                ))
            ), RuntimeSensitiveLevel.INTERNAL
        );
    }

    private AgentRunRequest request(String principalType, List<String> roles) {
        Map<String, Object> toolSnapshot = new java.util.LinkedHashMap<>();
        toolSnapshot.put("toolKey", "mcp.search");
        toolSnapshot.put("name", "Enterprise Search");
        toolSnapshot.put("description", "Search approved sources");
        toolSnapshot.put("toolType", "mcp");
        toolSnapshot.put("riskLevel", "R1");
        toolSnapshot.put("versionNo", 3);
        toolSnapshot.put("connectorId", 700L);
        toolSnapshot.put("externalName", "search");
        toolSnapshot.put("parameterSchema", Map.of(
            "type", "object",
            "properties", Map.of("query", Map.of("type", "string")),
            "required", List.of("query"),
            "additionalProperties", false
        ));
        toolSnapshot.put("executionPolicy", Map.of(
            "outputSchema", Map.of("type", "object")
        ));
        Map<String, Object> attributes = Map.of(
            "taskResourceSnapshot", Map.of(
                "agentVersionId", 100L,
                "resources", List.of(Map.of(
                    "resourceType", "tool", "resourceId", 500L, "permission", "use"
                ))
            ),
            "resourceBindings", List.of(Map.of(
                "resourceType", "tool",
                "resourceId", 500L,
                "permission", "invoke",
                "config", Map.of("resourceSnapshot", toolSnapshot)
            ))
        );
        return new AgentRunRequest(
            new RuntimeExecutionKey("run-10", "trace-10"),
            9L, null, 10L, 11L, 12L, 100L, "agent", "session", "input", "system",
            new RuntimeModelConfig("openai", "model", null, "env:MODEL_KEY", Map.of()),
            "run-11", 10,
            Map.of(
                "principalId", 9L, "principalType", principalType, "roles", roles
            ),
            attributes
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

    private AgentRunRequest builtinRequest(String toolKey, Long toolId) {
        AgentRunRequest source = request("human", List.of("member"));
        AgentTool tool = new AgentTool();
        tool.setId(toolId);
        tool.setToolKey(toolKey);
        tool.setToolType("builtin");
        tool.setRiskLevel("R0");
        tool.setVersionNo(1);
        tool.setStatus("active");
        tool.setIsAvailable(true);
        when(mapper.selectToolById(toolId)).thenReturn(tool);

        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("toolKey", toolKey);
        snapshot.put("name", toolKey);
        snapshot.put("description", "Built-in platform tool");
        snapshot.put("toolType", "builtin");
        snapshot.put("riskLevel", "R0");
        snapshot.put("versionNo", 1);
        snapshot.put("parameterSchema", Map.of(
            "type", "object", "properties", Map.of(), "additionalProperties", true
        ));
        snapshot.put("executionPolicy", Map.of("readOnly", true));
        Map<String, Object> attributes = new java.util.LinkedHashMap<>(source.attributes());
        attributes.put("taskResourceSnapshot", Map.of(
            "agentVersionId", 100L,
            "resources", List.of(Map.of(
                "resourceType", "tool", "resourceId", toolId, "permission", "use"
            ))
        ));
        attributes.put("resourceBindings", List.of(Map.of(
            "resourceType", "tool", "resourceId", toolId, "permission", "invoke",
            "config", Map.of("resourceSnapshot", snapshot)
        )));
        return copy(source, attributes);
    }

    private AgentRunRequest manualSqlRequest() {
        AgentRunRequest source = request("human", List.of("member"));
        AgentTool tool = new AgentTool();
        tool.setId(506L);
        tool.setToolKey("sql.customer-orders");
        tool.setToolType("sql");
        tool.setRiskLevel("R1");
        tool.setVersionNo(1);
        tool.setStatus("active");
        tool.setIsAvailable(true);
        when(mapper.selectToolById(506L)).thenReturn(tool);

        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("toolKey", "sql.customer-orders");
        snapshot.put("name", "Customer orders");
        snapshot.put("description", "Read customer orders");
        snapshot.put("toolType", "sql");
        snapshot.put("riskLevel", "R1");
        snapshot.put("versionNo", 1);
        snapshot.put("parameterSchema", sqlSchema());
        snapshot.put("executionPolicy", sqlPolicy());
        Map<String, Object> attributes = new java.util.LinkedHashMap<>(source.attributes());
        attributes.put("taskResourceSnapshot", Map.of(
            "agentVersionId", 100L,
            "resources", List.of(
                Map.of("resourceType", "tool", "resourceId", 506L, "permission", "use"),
                Map.of("resourceType", "dataset", "resourceId", 800L, "permission", "query")
            )
        ));
        attributes.put("resourceBindings", List.of(Map.of(
            "resourceType", "tool", "resourceId", 506L, "permission", "invoke",
            "config", Map.of("resourceSnapshot", snapshot)
        )));
        return copy(source, attributes);
    }

    private AgentRunRequest sessionSqlRequest() {
        AgentRunRequest task = manualSqlRequest();
        Map<String, Object> attributes = new java.util.LinkedHashMap<>(task.attributes());
        attributes.put("taskResourceSnapshot", Map.of(
            "agentVersionId", 100L,
            "resources", List.of(Map.of(
                "resourceType", "tool", "resourceId", 506L, "permission", "invoke"
            ))
        ));
        return new AgentRunRequest(
            new RuntimeExecutionKey("conversation-turn-77", "trace-session"),
            9L, 77L, null, null, null, 100L, "agent", "session", "input", "system",
            task.model(), null, 10, task.authorizationSnapshot(), Map.copyOf(attributes)
        );
    }

    private Map<String, Object> sqlSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of("customer", Map.of("type", "string")),
            "required", List.of("customer"),
            "additionalProperties", false
        );
    }

    private Map<String, Object> sqlPolicy() {
        return Map.of(
            "datasetId", "800",
            "queryPurpose", "按客户查询订单",
            "sqlTemplate", "SELECT o.customer_id FROM public.orders o WHERE o.customer_id = {{customer}}",
            "readOnly", true
        );
    }

    private AuthorizationDecision decision(PermissionEffect effect) {
        return new AuthorizationDecision(effect, effect.name(), effect.name(), List.of());
    }
}
