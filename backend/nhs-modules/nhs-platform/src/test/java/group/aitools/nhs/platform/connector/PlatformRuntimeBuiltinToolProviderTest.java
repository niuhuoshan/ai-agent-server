package group.aitools.nhs.platform.connector;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.platform.audit.service.ToolInvocationAuditService;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.domain.AgentTool;
import group.aitools.nhs.platform.connector.mapper.ConnectorCatalogMapper;
import group.aitools.nhs.platform.connector.service.ApiToolExecutor;
import group.aitools.nhs.platform.connector.service.BuiltinToolCatalog;
import group.aitools.nhs.platform.connector.service.ConnectorMcpConnectionFactory;
import group.aitools.nhs.platform.connector.service.McpRemoteClient;
import group.aitools.nhs.platform.connector.service.PlatformRuntimeToolProvider;
import group.aitools.nhs.platform.connector.service.TaskControlBuiltinService;
import group.aitools.nhs.platform.connector.service.ToolArgumentValidator;
import group.aitools.nhs.platform.data.service.PlatformRuntimeDataQueryProvider;
import group.aitools.nhs.platform.execution.service.FrozenRuntimePrincipalResolver;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.knowledge.service.PlatformRuntimeKnowledgeProvider;
import group.aitools.nhs.platform.memory.domain.AgentMemory;
import group.aitools.nhs.platform.memory.mapper.MemoryCatalogMapper;
import group.aitools.nhs.platform.memory.service.MemoryScopeAuthorizationService;
import group.aitools.nhs.platform.nhs.service.NhsWorkspaceService;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.platform.notification.service.NotificationMessage;
import group.aitools.nhs.platform.sandbox.service.SandboxJobQueueService;
import group.aitools.nhs.platform.task.domain.AgentTask;
import group.aitools.nhs.platform.task.mapper.AgentTaskMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PlatformRuntimeBuiltinToolProviderTest {

    @Test
    void currentModelReturnsAgentIdentityWithoutEndpointOrProviderOptions() {
        Fixture fixture = new Fixture("get_current_model", 606L);

        Map<?, ?> envelope = (Map<?, ?>) fixture.provider.invoke(
            fixture.request, 606L, Map.of()
        );

        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        assertEquals("openai", data.get("provider"));
        assertEquals("model", data.get("model_id"));
        assertEquals(100L, ((Number) data.get("agent_version_id")).longValue());
        assertEquals("agent", data.get("agent_name"));
        assertFalse(data.containsKey("endpoint"));
        assertFalse(data.containsKey("options"));
    }

    @Test
    void myTasksUsesFrozenPrincipalVisibilityAndWritesSuccessAudit() {
        Fixture fixture = new Fixture("get_my_tasks", 607L);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentTask task = new AgentTask();
        task.setId(700L);
        task.setTaskKey("task-700");
        task.setTitle("Shared task");
        task.setStatus("ready");
        task.setVisibility("enterprise_shared");
        task.setOwnerId(12L);
        task.setOwnerPrincipalType("user");
        when(tasks.selectVisibleTasks(9L, "user", true, true, false, false, 20))
            .thenReturn(List.of(task));
        fixture.provider = fixture.provider(tasks);

        Map<?, ?> envelope = (Map<?, ?>) fixture.provider.invoke(
            fixture.request, 607L, Map.of("limit", 20)
        );

        List<?> data = (List<?>) envelope.get("data");
        assertEquals(1, data.size());
        assertEquals(700L, ((Number) ((Map<?, ?>) data.getFirst()).get("id")).longValue());
        assertEquals(false, ((Map<?, ?>) data.getFirst()).get("owned_by_current_principal"));
        verify(tasks).selectVisibleTasks(9L, "user", true, true, false, false, 20);
        verify(fixture.audit).record(
            any(), eq(607L), any(), any(), eq(true), eq("BUILTIN_TOOL_SUCCEEDED")
        );
    }

    @Test
    void recurringTaskCreationUsesFrozenPrincipalAndTypedArguments() {
        Fixture fixture = new Fixture("create_recurring_task", 608L);
        TaskControlBuiltinService controls = mock(TaskControlBuiltinService.class);
        when(controls.createRecurring(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Map.of("task_id", 700L, "status", "active"));
        fixture.provider = fixture.provider(controls);
        ArgumentCaptor<CurrentPrincipal> principal = ArgumentCaptor.forClass(CurrentPrincipal.class);

        Map<?, ?> envelope = (Map<?, ?>) fixture.provider.invoke(
            fixture.request, 608L, Map.of(
                "name", "Daily report", "cron", "0 8 * * *", "prompt", "Build report",
                "notification_channels", List.of("portal"),
                "timezone", "Asia/Shanghai", "service_account_id", 20L
            )
        );

        assertEquals(700L, ((Number) ((Map<?, ?>) envelope.get("data")).get("task_id")).longValue());
        verify(controls).createRecurring(
            eq(fixture.request), principal.capture(), eq("Daily report"), eq("0 8 * * *"),
            eq("Build report"), eq(List.of("portal")), eq("Asia/Shanghai"), eq(20L)
        );
        assertEquals(9L, principal.getValue().id());
        assertEquals(true, principal.getValue().isHuman());
    }

    @Test
    void allRecurringTaskControlsDispatchToDurableService() {
        TaskControlBuiltinService controls = mock(TaskControlBuiltinService.class);
        when(controls.cancel(any(), eq(700L))).thenReturn(Map.of("status", "archived"));
        when(controls.start(any(), eq(700L))).thenReturn(Map.of("status", "active"));
        when(controls.pause(any(), eq(700L))).thenReturn(Map.of("status", "paused"));
        when(controls.runManually(any(), any(), eq(700L)))
            .thenReturn(Map.of("status", "queued"));

        Fixture cancel = new Fixture("cancel_task", 609L);
        cancel.provider = cancel.provider(controls);
        cancel.provider.invoke(cancel.request, 609L, Map.of("task_id", 700L));
        Fixture start = new Fixture("start_task", 610L);
        start.provider = start.provider(controls);
        start.provider.invoke(start.request, 610L, Map.of("task_id", 700L));
        Fixture pause = new Fixture("pause_task", 611L);
        pause.provider = pause.provider(controls);
        pause.provider.invoke(pause.request, 611L, Map.of("task_id", 700L));
        Fixture run = new Fixture("run_task_manually", 612L);
        run.provider = run.provider(controls);
        run.provider.invoke(run.request, 612L, Map.of("task_id", 700L));

        verify(controls).cancel(any(), eq(700L));
        verify(controls).start(any(), eq(700L));
        verify(controls).pause(any(), eq(700L));
        verify(controls).runManually(eq(run.request), any(), eq(700L));
    }

    @Test
    void delegatesKnowledgeSearchThroughTheFrozenKnowledgeProvider() {
        Fixture fixture = new Fixture("search_knowledge_base", 601L);
        PlatformRuntimeKnowledgeProvider knowledge = mock(PlatformRuntimeKnowledgeProvider.class);
        when(knowledge.search(fixture.request, 900L, "retention", 4))
            .thenReturn(Map.of("status", "ok", "citations", List.of("c1")));
        fixture.provider = fixture.provider(knowledge, null, null, null, null);

        Map<?, ?> envelope = (Map<?, ?>) fixture.provider.invoke(
            fixture.request, 601L,
            Map.of("knowledge_base_id", 900L, "query", "retention", "top_k", 4)
        );

        assertEquals(true, envelope.get("ok"));
        assertEquals("ok", ((Map<?, ?>) envelope.get("data")).get("status"));
        verify(knowledge).search(fixture.request, 900L, "retention", 4);
    }

    @Test
    void memorySearchOnlyReturnsApprovedUnexpiredNonSensitiveEntries() {
        Fixture fixture = new Fixture("memory_search", 602L);
        MemoryCatalogMapper memories = mock(MemoryCatalogMapper.class);
        MemoryScopeAuthorizationService scope = mock(MemoryScopeAuthorizationService.class);
        when(scope.canView(any(), eq("user"), eq(9L))).thenReturn(true);
        AgentMemory approved = memory(1L, "approved", "internal", null, "keep this");
        AgentMemory pending = memory(2L, "pending", "internal", null, "not yet");
        AgentMemory expired = memory(3L, "approved", "internal", LocalDateTime.now().minusMinutes(1), "expired");
        when(memories.selectScopeMemories("user", 9L, false, "keep", 20))
            .thenReturn(List.of(approved, pending, expired));
        fixture.provider = fixture.provider(null, memories, scope, null, null);

        Map<?, ?> envelope = (Map<?, ?>) fixture.provider.invoke(
            fixture.request, 602L, Map.of("query", "keep")
        );

        List<?> data = (List<?>) envelope.get("data");
        assertEquals(1, data.size());
        assertEquals("keep this", ((Map<?, ?>) data.getFirst()).get("content"));
    }

    @Test
    void portalNotificationUsesFrozenHumanPrincipalAndStableEventKey() {
        Fixture fixture = new Fixture("send_portal_notification", 603L);
        NotificationApplicationService notifications = mock(NotificationApplicationService.class);
        fixture.provider = fixture.provider(null, null, null, notifications, null);

        Map<?, ?> envelope = (Map<?, ?>) fixture.provider.invoke(
            fixture.request, 603L,
            Map.of("title", "Run completed", "content", "Your report is ready")
        );

        assertEquals(true, envelope.get("ok"));
        assertEquals(true, ((Map<?, ?>) envelope.get("data")).get("delivered"));
        verify(notifications).publish(any(), any(NotificationMessage.class));
    }

    @Test
    void userPreferenceUpdateWritesApprovedScopedMemory() {
        Fixture fixture = new Fixture("update_user_preference", 605L);
        MemoryCatalogMapper memories = mock(MemoryCatalogMapper.class);
        MemoryScopeAuthorizationService scope = mock(MemoryScopeAuthorizationService.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        when(scope.canView(any(), eq("user"), eq(9L))).thenReturn(true);
        when(ids.nextId()).thenReturn(1000L);
        when(memories.selectByScopeAndKey("user", 9L, "language")).thenReturn(null);
        when(memories.insertMemory(any(AgentMemory.class))).thenReturn(1);
        fixture.provider = fixture.provider(null, memories, scope, ids, null, null);

        Map<?, ?> envelope = (Map<?, ?>) fixture.provider.invoke(
            fixture.request, 605L,
            Map.of("user_id", 9L, "key", "language", "value", "中文")
        );

        assertEquals(true, envelope.get("ok"));
        assertEquals(true, ((Map<?, ?>) envelope.get("data")).get("updated"));
        verify(memories).insertMemory(any(AgentMemory.class));
    }

    @Test
    void configuredBuiltinWithoutBackendRemainsExplicitlyUnavailable() {
        Fixture fixture = new Fixture("search_knowledge_base", 604L);
        assertThrows(
            group.aitools.nhs.common.core.exception.ServiceException.class,
            () -> fixture.provider.invoke(
                fixture.request, 604L,
                Map.of("knowledge_base_id", 900L, "query", "x")
            )
        );
    }

    @Test
    void listsOnlyTaskGrantedSkillsFromTheFrozenResourceBindings() {
        Fixture fixture = new Fixture("list_available_skills", 613L);
        AgentRunRequest request = skillRequest(fixture.request, List.of(
            skillBinding(910L, "finance-helper", "Finance helper", "project", 3L,
                "Use approved finance data", 2),
            skillBinding(911L, "not-granted", "Should stay hidden", "user", 9L,
                "Do not expose", 1)
        ), List.of(Map.of(
            "resourceType", "skill", "resourceId", 910L, "permission", "use"
        )));

        Map<?, ?> envelope = (Map<?, ?>) fixture.provider.invoke(request, 613L, Map.of());

        List<?> skills = (List<?>) envelope.get("data");
        assertEquals(1, skills.size());
        Map<?, ?> item = (Map<?, ?>) skills.getFirst();
        assertEquals("finance-helper", item.get("id"));
        assertEquals("Finance helper", item.get("name"));
        assertEquals("finance-helper", item.get("key"));
        assertEquals("project", item.get("scope"));
        assertEquals(910L, ((Number) item.get("resource_id")).longValue());
        assertEquals(2, ((Number) item.get("version")).intValue());
        assertEquals("hash-finance-helper", item.get("hash"));
        verify(fixture.audit).record(
            any(), eq(613L), any(), any(), eq(true), eq("BUILTIN_TOOL_SUCCEEDED")
        );
    }

    @Test
    void readsFrozenSkillInstructionAndDependencyMetadataWithoutCatalogLookup() {
        Fixture fixture = new Fixture("read_skill_instruction", 614L);
        AgentRunRequest request = skillRequest(fixture.request, List.of(
            skillBinding(910L, "finance-helper", "Finance helper", "system", null,
                "Use approved finance data", 2)
        ), List.of(Map.of(
            "resourceType", "skill", "resourceId", 910L, "permission", "use"
        )));

        Map<?, ?> envelope = (Map<?, ?>) fixture.provider.invoke(
            request, 614L, Map.of("skill_id", "finance-helper")
        );

        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        assertEquals("Use approved finance data", data.get("content"));
        assertEquals(data.get("content"), data.get("instruction"));
        assertEquals("finance-helper", data.get("key"));
        assertEquals("hash-finance-helper", data.get("hash"));
        assertEquals(List.of("sql.customer-orders"),
            ((Map<?, ?>) data.get("dependencies")).get("required_tool_keys"));
        assertEquals(List.of(800L),
            ((Map<?, ?>) data.get("dependencies")).get("required_knowledge_base_ids"));
        verify(fixture.audit).record(
            any(), eq(614L), any(), any(), eq(true), eq("BUILTIN_TOOL_SUCCEEDED")
        );
    }

    @Test
    void skillInstructionRejectsUnboundAndOversizedContentWithTypedErrors() {
        Fixture missingGrant = new Fixture("read_skill_instruction", 615L);
        AgentRunRequest unbound = skillRequest(missingGrant.request, List.of(
            skillBinding(910L, "finance-helper", "Finance helper", "system", null, "content", 1)
        ), List.of());
        group.aitools.nhs.common.core.exception.ServiceException forbidden = assertThrows(
            group.aitools.nhs.common.core.exception.ServiceException.class,
            () -> missingGrant.provider.invoke(unbound, 615L, Map.of("skill_id", "finance-helper"))
        );
        assertEquals(403, forbidden.getCode());

        Fixture oversizedFixture = new Fixture("read_skill_instruction", 616L);
        AgentRunRequest oversized = skillRequest(oversizedFixture.request, List.of(
            skillBinding(910L, "finance-helper", "Finance helper", "system", null,
                "x".repeat(256 * 1024 + 1), 1)
        ), List.of(Map.of(
            "resourceType", "skill", "resourceId", 910L, "permission", "use"
        )));
        group.aitools.nhs.common.core.exception.ServiceException unavailable = assertThrows(
            group.aitools.nhs.common.core.exception.ServiceException.class,
            () -> oversizedFixture.provider.invoke(
                oversized, 616L, Map.of("skill_id", "finance-helper")
            )
        );
        assertEquals(503, unavailable.getCode());
        assertTrue(unavailable.getMessage().startsWith("skill_unavailable:"));
        verify(oversizedFixture.audit).record(
            any(), eq(616L), any(), eq(null), eq(false),
            org.mockito.ArgumentMatchers.startsWith("TOOL_INVOCATION_EXCEPTION:")
        );
    }

    @Test
    void rejectsAdminPermissionOnFrozenAgentSkillBinding() {
        Fixture fixture = new Fixture("list_available_skills", 617L);
        Map<String, Object> adminBinding = new java.util.LinkedHashMap<>(skillBinding(
            910L, "finance-helper", "Finance helper", "system", null,
            "Use approved finance data", 2
        ));
        adminBinding.put("permission", "admin");
        AgentRunRequest request = skillRequest(fixture.request, List.of(adminBinding), List.of());

        group.aitools.nhs.common.core.exception.ServiceException unavailable = assertThrows(
            group.aitools.nhs.common.core.exception.ServiceException.class,
            () -> fixture.provider.invoke(request, 617L, Map.of())
        );

        assertEquals(503, unavailable.getCode());
        assertTrue(unavailable.getMessage().startsWith("skill_unavailable:"));
        verify(fixture.audit).record(
            any(), eq(617L), any(), eq(null), eq(false),
            org.mockito.ArgumentMatchers.startsWith("TOOL_INVOCATION_EXCEPTION:")
        );
    }

    @Test
    void skillBuiltinDescriptorsExposeStrictSchemasAndLocalExecution() {
        assertEquals("local", BuiltinToolCatalog.descriptor("list_available_skills").get("execution"));
        assertEquals("local", BuiltinToolCatalog.descriptor("read_skill_instruction").get("execution"));
        Map<?, ?> schema = (Map<?, ?>) BuiltinToolCatalog.descriptor(
            "read_skill_instruction"
        ).get("parameterSchema");
        assertEquals(List.of("skill_id"), schema.get("required"));
        assertEquals(false, schema.get("additionalProperties"));
    }

    @Test
    void jiraBuiltinsReturnTypedProviderUnavailableWithoutDraftExecution() {
        Fixture fixture = new Fixture("jira_create_issue", 618L);

        group.aitools.nhs.common.core.exception.ServiceException unavailable = assertThrows(
            group.aitools.nhs.common.core.exception.ServiceException.class,
            () -> fixture.provider.invoke(fixture.request, 618L, Map.of(
                "project_key", "OPS", "summary", "summary", "description", "description"
            ))
        );

        assertEquals(503, unavailable.getCode());
        assertTrue(unavailable.getMessage().startsWith("tool_unavailable: jira_create_issue"));
        assertTrue(unavailable.getMessage().contains("Jira Provider is not configured"));
        verify(fixture.audit).record(
            any(), eq(618L), any(), eq(null), eq(false),
            org.mockito.ArgumentMatchers.startsWith("TOOL_INVOCATION_EXCEPTION:")
        );
    }

    private AgentRunRequest skillRequest(
        AgentRunRequest source,
        List<Map<String, Object>> skills,
        List<Map<String, Object>> taskSkills
    ) {
        Map<String, Object> attributes = new java.util.LinkedHashMap<>(source.attributes());
        List<Map<String, Object>> bindings = new java.util.ArrayList<>();
        bindings.addAll((List<Map<String, Object>>) source.attributes().get("resourceBindings"));
        bindings.addAll(skills);
        Map<?, ?> sourceSnapshot = (Map<?, ?>) source.attributes().get("taskResourceSnapshot");
        List<Map<String, Object>> resources = new java.util.ArrayList<>();
        resources.addAll((List<Map<String, Object>>) sourceSnapshot.get("resources"));
        resources.addAll(taskSkills);
        attributes.put("resourceBindings", bindings);
        attributes.put("taskResourceSnapshot", Map.of(
            "agentVersionId", 100L, "resources", resources
        ));
        return copy(source, attributes);
    }

    private Map<String, Object> skillBinding(
        Long id,
        String key,
        String name,
        String scopeType,
        Long scopeId,
        String content,
        int versionNo
    ) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("skillKey", key);
        snapshot.put("name", name);
        snapshot.put("description", "Frozen " + name);
        snapshot.put("scopeType", scopeType);
        if (scopeId != null) snapshot.put("scopeId", scopeId);
        snapshot.put("versionId", id + 1000L);
        snapshot.put("versionNo", versionNo);
        snapshot.put("content", content);
        snapshot.put("contentHash", "hash-" + key);
        snapshot.put("manifest", Map.of(
            "summary", "Frozen " + name,
            "requiredToolKeys", List.of("sql.customer-orders")
        ));
        snapshot.put("runtimeRequirements", Map.of(
            "requiredToolIds", List.of(500L),
            "requiredKnowledgeBaseIds", List.of(800L)
        ));
        return Map.of(
            "resourceType", "skill", "resourceId", id, "permission", "use",
            "config", Map.of("resourceSnapshot", snapshot)
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

    private AgentMemory memory(
        Long id, String reviewStatus, String sensitivity, LocalDateTime expiresAt, String content
    ) {
        AgentMemory memory = new AgentMemory();
        memory.setId(id);
        memory.setMemoryKey("memory-" + id);
        memory.setScopeType("user");
        memory.setScopeId(9L);
        memory.setMemoryType("preference");
        memory.setContent(content);
        memory.setReviewStatus(reviewStatus);
        memory.setSensitiveLevel(sensitivity);
        memory.setExpiresAt(expiresAt);
        return memory;
    }

    private final class Fixture {
        private final ConnectorCatalogMapper mapper = mock(ConnectorCatalogMapper.class);
        private final group.aitools.nhs.platform.iam.service.AuthorizationEnforcer authorization =
            mock(group.aitools.nhs.platform.iam.service.AuthorizationEnforcer.class);
        private final PlatformRuntimeDataQueryProvider dataQuery = mock(PlatformRuntimeDataQueryProvider.class);
        private final ToolInvocationAuditService audit = mock(ToolInvocationAuditService.class);
        private final AgentRunRequest request;
        private PlatformRuntimeToolProvider provider;

        private Fixture(String key, Long id) {
            AgentTool tool = new AgentTool();
            tool.setId(id);
            tool.setToolKey(key);
            tool.setToolType("builtin");
            tool.setRiskLevel("R0");
            tool.setVersionNo(1);
            tool.setStatus("active");
            tool.setIsAvailable(true);
            when(mapper.selectToolById(id)).thenReturn(tool);
            when(authorization.decide(any(), any())).thenReturn(
                new AuthorizationDecision(PermissionEffect.ALLOW, "ALLOW", "", List.of())
            );
            when(dataQuery.resolve(any())).thenReturn(List.of());
            Map<String, Object> snapshot = Map.of(
                "toolKey", key,
                "name", key,
                "toolType", "builtin",
                "riskLevel", "R0",
                "versionNo", 1,
                "parameterSchema", Map.of(
                    "type", "object", "properties", Map.of(), "additionalProperties", true
                ),
                "executionPolicy", Map.of("readOnly", true)
            );
            request = new AgentRunRequest(
                new RuntimeExecutionKey("run-10", "trace-10"),
                9L, null, 10L, 11L, 12L, 100L, "agent", "session", "input", "system",
                new RuntimeModelConfig("openai", "model", null, "env:MODEL_KEY", Map.of()),
                "run-11", 10,
                Map.of("principalId", 9L, "principalType", "human", "roles", List.of("member")),
                Map.of(
                    "taskResourceSnapshot", Map.of(
                        "agentVersionId", 100L,
                        "resources", List.of(Map.of(
                            "resourceType", "tool", "resourceId", id, "permission", "use"
                        ))
                    ),
                    "resourceBindings", List.of(Map.of(
                        "resourceType", "tool", "resourceId", id, "permission", "invoke",
                        "config", Map.of("resourceSnapshot", snapshot)
                    ))
                )
            );
            provider = provider(null, null, null, null, null);
        }

        private PlatformRuntimeToolProvider provider(
            PlatformRuntimeKnowledgeProvider knowledge,
            MemoryCatalogMapper memories,
            MemoryScopeAuthorizationService scope,
            NotificationApplicationService notifications,
            NhsWorkspaceService workspace
        ) {
            return provider(knowledge, memories, scope, null, notifications, workspace);
        }

        private PlatformRuntimeToolProvider provider(AgentTaskMapper tasks) {
            JsonMapper jsonMapper = JsonMapper.builder().build();
            return new PlatformRuntimeToolProvider(
                mapper, new FrozenRuntimePrincipalResolver(), authorization,
                mock(ConnectorMcpConnectionFactory.class), mock(McpRemoteClient.class),
                mock(ApiToolExecutor.class), new ToolArgumentValidator(jsonMapper), audit,
                dataQuery, (SandboxJobQueueService) null, jsonMapper,
                null, null, null, null, null, null, null, tasks
            );
        }

        private PlatformRuntimeToolProvider provider(TaskControlBuiltinService controls) {
            JsonMapper jsonMapper = JsonMapper.builder().build();
            return new PlatformRuntimeToolProvider(
                mapper, new FrozenRuntimePrincipalResolver(), authorization,
                mock(ConnectorMcpConnectionFactory.class), mock(McpRemoteClient.class),
                mock(ApiToolExecutor.class), new ToolArgumentValidator(jsonMapper), audit,
                dataQuery, (SandboxJobQueueService) null, jsonMapper, controls
            );
        }

        private PlatformRuntimeToolProvider provider(
            PlatformRuntimeKnowledgeProvider knowledge,
            MemoryCatalogMapper memories,
            MemoryScopeAuthorizationService scope,
            PlatformIdGenerator ids,
            NotificationApplicationService notifications,
            NhsWorkspaceService workspace
        ) {
            JsonMapper jsonMapper = JsonMapper.builder().build();
            return new PlatformRuntimeToolProvider(
                mapper, new FrozenRuntimePrincipalResolver(), authorization,
                mock(ConnectorMcpConnectionFactory.class), mock(McpRemoteClient.class),
                mock(ApiToolExecutor.class), new ToolArgumentValidator(jsonMapper), audit,
                dataQuery, (SandboxJobQueueService) null, jsonMapper,
                knowledge, memories, scope, ids, notifications, workspace
            );
        }
    }
}
