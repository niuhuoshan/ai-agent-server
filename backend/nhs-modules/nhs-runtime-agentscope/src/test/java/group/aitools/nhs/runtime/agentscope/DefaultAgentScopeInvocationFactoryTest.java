package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequestResolver;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.runtime.spi.RuntimeKnowledgeDefinition;
import group.aitools.nhs.runtime.spi.RuntimeKnowledgeProvider;
import group.aitools.nhs.runtime.spi.RuntimeMemoryDefinition;
import group.aitools.nhs.runtime.spi.RuntimeMemoryProvider;
import group.aitools.nhs.runtime.spi.RuntimeToolDefinition;
import group.aitools.nhs.runtime.spi.RuntimeToolProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Tag("dev")
class DefaultAgentScopeInvocationFactoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsAgentWithDurableStateAndNoShellOrFilesystemToolsByDefault() {
        AtomicReference<String> resolvedReference = new AtomicReference<>();
        AgentStateStore stateStore = mock(AgentStateStore.class);
        DefaultAgentScopeInvocationFactory factory = factory(
            reference -> {
                resolvedReference.set(reference);
                return "test-secret";
            },
            request -> RuntimeFixtures.runRequest(),
            stateStore,
            false
        );

        HarnessAgentInvocation invocation = (HarnessAgentInvocation) factory.create(
            RuntimeFixtures.runRequest()
        );
        List<String> toolNames = invocation.harnessAgent().getDelegate().getToolkit()
            .getToolSchemas().stream()
            .map(ToolSchema::getName)
            .toList();

        assertEquals("credential:model-main", resolvedReference.get());
        assertEquals(stateStore, invocation.harnessAgent().getDelegate().getStateStore());
        assertFalse(toolNames.contains("execute"));
        assertFalse(toolNames.contains("read_file"));
        assertFalse(toolNames.contains("write_file"));
        invocation.close();
    }

    @Test
    void rejectsWorkspaceTraversalAndSymbolicLinkEscape() throws Exception {
        AgentScopeWorkspaceResolver resolver = new AgentScopeWorkspaceResolver(temporaryDirectory);

        IllegalArgumentException traversal = assertThrows(
            IllegalArgumentException.class,
            () -> resolver.resolve(runRequest(
                "../outside",
                RuntimeFixtures.runRequest().model(),
                Map.of()
            ))
        );
        assertTrue(traversal.getMessage().contains("opaque identifier"));

        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside-target"));
        Files.createSymbolicLink(temporaryDirectory.resolve("linked"), outside);
        assertThrows(
            SecurityException.class,
            () -> resolver.resolve(runRequest(
                "linked",
                RuntimeFixtures.runRequest().model(),
                Map.of()
            ))
        );
    }

    @Test
    void rejectsInsecureEndpointAndUnknownModelOptionBeforeCallingProvider() {
        RuntimeModelConfig insecureModel = new RuntimeModelConfig(
            "openai-compatible",
            "model",
            "http://model.internal/v1",
            "env:MODEL_API_KEY",
            Map.of()
        );
        DefaultAgentScopeInvocationFactory factory = factory(
            ignored -> "secret",
            request -> RuntimeFixtures.runRequest(),
            mock(AgentStateStore.class),
            false
        );

        IllegalArgumentException insecure = assertThrows(
            IllegalArgumentException.class,
            () -> factory.create(runRequest("workspace", insecureModel, Map.of()))
        );
        assertEquals("model baseUrl must use https", insecure.getMessage());

        RuntimeModelConfig unknownOption = new RuntimeModelConfig(
            "openai",
            "model",
            null,
            "env:MODEL_API_KEY",
            Map.of("additionalHeaders", Map.of("Authorization", "stolen"))
        );
        IllegalArgumentException unknown = assertThrows(
            IllegalArgumentException.class,
            () -> factory.create(runRequest("workspace", unknownOption, Map.of()))
        );
        assertTrue(unknown.getMessage().contains("unsupported model options"));
    }

    @Test
    void permitsHttpEndpointOnlyWhenExplicitlyConfigured() {
        RuntimeModelConfig model = new RuntimeModelConfig(
            "openai-compatible",
            "model",
            "http://model.internal/v1",
            "env:MODEL_API_KEY",
            Map.of("nativeStructuredOutput", false)
        );
        DefaultAgentScopeInvocationFactory factory = factory(
            ignored -> "secret",
            request -> RuntimeFixtures.runRequest(),
            mock(AgentStateStore.class),
            true
        );

        AgentScopeInvocation invocation = factory.create(runRequest("workspace", model, Map.of()));

        invocation.close();
    }

    @Test
    void rejectsResumeWhenPersistedIdentityDoesNotMatchApprovalRequest() {
        AgentRunRequestResolver mismatched = ignored -> RuntimeFixtures.runRequest("other-execution");
        DefaultAgentScopeInvocationFactory factory = factory(
            ignored -> "secret",
            mismatched,
            mock(AgentStateStore.class),
            false
        );

        SecurityException exception = assertThrows(
            SecurityException.class,
            () -> factory.createForResume(RuntimeFixtures.resumeRequest(
                group.aitools.nhs.runtime.spi.RuntimeResumeDecision.APPROVE
            ))
        );

        assertTrue(exception.getMessage().contains("does not match"));
    }

    @Test
    void registersFrozenToolsWithRiskRulesAndAppendsFrozenSkillContent() {
        AtomicInteger beginCalls = new AtomicInteger();
        AtomicInteger endCalls = new AtomicInteger();
        RuntimeToolProvider tools = new RuntimeToolProvider() {
            @Override
            public void begin(AgentRunRequest request) {
                beginCalls.incrementAndGet();
            }

            @Override
            public List<RuntimeToolDefinition> resolve(AgentRunRequest request) {
                return List.of(
                    new RuntimeToolDefinition(
                        10L, "platform_tool_10", "read", Map.of("type", "object"),
                        Map.of(), "R1", true
                    ),
                    new RuntimeToolDefinition(
                        11L, "platform_tool_11", "write", Map.of("type", "object"),
                        Map.of(), "R3", false
                    )
                );
            }

            @Override
            public Object invoke(AgentRunRequest request, Long toolId, Map<String, Object> arguments) {
                return Map.of("toolId", toolId);
            }

            @Override
            public void end(AgentRunRequest request) {
                endCalls.incrementAndGet();
            }
        };
        DefaultAgentScopeInvocationFactory factory = new DefaultAgentScopeInvocationFactory(
            ignored -> "secret",
            request -> RuntimeFixtures.runRequest(),
            mock(AgentStateStore.class),
            new AgentScopeWorkspaceResolver(temporaryDirectory.resolve("tool-workspaces")),
            tools,
            new ObjectMapper(),
            10,
            false
        );
        AgentRunRequest source = RuntimeFixtures.runRequest();
        AgentRunRequest request = new AgentRunRequest(
            source.executionKey(), source.userId(), source.conversationId(), source.taskId(),
            source.runId(), source.stepId(), source.agentVersionId(), source.agentName(),
            source.sessionId(), source.input(), source.systemPrompt(), source.model(),
            "tool-workspace", source.maxIterations(), source.authorizationSnapshot(),
            Map.of("resourceBindings", List.of(Map.of(
                "resourceType", "skill",
                "resourceId", 77L,
                "config", Map.of("resourceSnapshot", Map.of(
                    "name", "finance-review",
                    "versionNo", 4,
                    "content", "Always cite the approved ledger."
                ))
            )))
        );

        HarnessAgentInvocation invocation = (HarnessAgentInvocation) factory.create(request);
        List<String> names = invocation.harnessAgent().getDelegate().getToolkit().getToolSchemas()
            .stream().map(ToolSchema::getName).toList();
        PermissionContextState permissions = invocation.harnessAgent().getDelegate()
            .getPermissionContext();

        assertTrue(names.contains("platform_tool_10"));
        assertTrue(names.contains("platform_tool_11"));
        assertTrue(permissions.getAllowRules().containsKey("platform_tool_10"));
        assertTrue(permissions.getAskRules().containsKey("platform_tool_11"));
        assertTrue(invocation.harnessAgent().getDelegate().getSysPrompt()
            .contains("Always cite the approved ledger."));
        assertEquals(1, beginCalls.get());
        assertEquals(0, endCalls.get());
        invocation.close();
        invocation.close();
        assertEquals(1, endCalls.get());
    }

    @Test
    void registersKnowledgeSearchWithAllowAndRecoverableApprovalRules() {
        RuntimeKnowledgeProvider knowledge = new RuntimeKnowledgeProvider() {
            @Override
            public List<RuntimeKnowledgeDefinition> resolve(AgentRunRequest request) {
                return List.of(
                    new RuntimeKnowledgeDefinition(20L, "Public policy", "Approved policy", false),
                    new RuntimeKnowledgeDefinition(21L, "Restricted policy", "Sensitive policy", true)
                );
            }

            @Override
            public Object search(
                AgentRunRequest request, Long knowledgeBaseId, String query, Integer topK
            ) {
                return Map.of("status", "ok", "knowledgeBaseId", knowledgeBaseId);
            }
        };
        DefaultAgentScopeInvocationFactory factory = new DefaultAgentScopeInvocationFactory(
            ignored -> "secret",
            request -> RuntimeFixtures.runRequest(),
            mock(AgentStateStore.class),
            new AgentScopeWorkspaceResolver(temporaryDirectory.resolve("knowledge-workspaces")),
            RuntimeToolProvider.empty(),
            knowledge,
            new ObjectMapper(),
            10,
            false
        );

        HarnessAgentInvocation invocation = (HarnessAgentInvocation) factory.create(
            RuntimeFixtures.runRequest()
        );
        List<String> names = invocation.harnessAgent().getDelegate().getToolkit().getToolSchemas()
            .stream().map(ToolSchema::getName).toList();
        PermissionContextState permissions = invocation.harnessAgent().getDelegate()
            .getPermissionContext();

        assertTrue(names.contains("search_knowledge_20"));
        assertTrue(names.contains("search_knowledge_21"));
        assertTrue(permissions.getAllowRules().containsKey("search_knowledge_20"));
        assertTrue(permissions.getAskRules().containsKey("search_knowledge_21"));
        invocation.close();
    }

    @Test
    void appendsGovernedMemoryAsReadOnlySystemContext() {
        RuntimeMemoryProvider memory = request -> List.of(
            new RuntimeMemoryDefinition(
                31L, "task", 3001L, "fact", "Approved expense policy is version four."
            )
        );
        DefaultAgentScopeInvocationFactory factory = new DefaultAgentScopeInvocationFactory(
            ignored -> "secret",
            request -> RuntimeFixtures.runRequest(),
            mock(AgentStateStore.class),
            new AgentScopeWorkspaceResolver(temporaryDirectory.resolve("memory-workspaces")),
            RuntimeToolProvider.empty(),
            RuntimeKnowledgeProvider.empty(),
            memory,
            new ObjectMapper(),
            10,
            false
        );

        HarnessAgentInvocation invocation = (HarnessAgentInvocation) factory.create(
            RuntimeFixtures.runRequest()
        );
        String prompt = invocation.harnessAgent().getDelegate().getSysPrompt();

        assertTrue(prompt.contains("Approved Platform Memory (read-only)"));
        assertTrue(prompt.contains("[task:3001/fact]"));
        assertTrue(prompt.contains("Approved expense policy is version four."));
        invocation.close();
    }

    private DefaultAgentScopeInvocationFactory factory(
        RuntimeCredentialResolver credentialResolver,
        AgentRunRequestResolver runRequestResolver,
        AgentStateStore stateStore,
        boolean allowInsecureEndpoints
    ) {
        return new DefaultAgentScopeInvocationFactory(
            credentialResolver,
            runRequestResolver,
            stateStore,
            new AgentScopeWorkspaceResolver(temporaryDirectory.resolve("workspaces")),
            new ObjectMapper(),
            10,
            allowInsecureEndpoints
        );
    }

    private AgentRunRequest runRequest(
        String workspaceKey,
        RuntimeModelConfig model,
        Map<String, Object> authorizationSnapshot
    ) {
        AgentRunRequest source = RuntimeFixtures.runRequest();
        return new AgentRunRequest(
            source.executionKey(),
            source.userId(),
            source.conversationId(),
            source.taskId(),
            source.runId(),
            source.stepId(),
            source.agentVersionId(),
            source.agentName(),
            source.sessionId(),
            source.input(),
            source.systemPrompt(),
            model,
            workspaceKey,
            source.maxIterations(),
            authorizationSnapshot,
            source.attributes()
        );
    }
}
