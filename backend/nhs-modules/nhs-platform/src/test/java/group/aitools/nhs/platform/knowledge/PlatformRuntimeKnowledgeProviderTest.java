package group.aitools.nhs.platform.knowledge;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.platform.execution.service.FrozenRuntimePrincipalResolver;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeBase;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeCatalogMapper;
import group.aitools.nhs.platform.knowledge.service.KnowledgeAuthorizationContextFactory;
import group.aitools.nhs.platform.knowledge.service.KnowledgeRetrievalService;
import group.aitools.nhs.platform.knowledge.service.PlatformRuntimeKnowledgeProvider;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PlatformRuntimeKnowledgeProviderTest {

    private KnowledgeCatalogMapper mapper;
    private AuthorizationEnforcer authorization;
    private KnowledgeRetrievalService retrieval;
    private PlatformRuntimeKnowledgeProvider provider;

    @BeforeEach
    void setUp() {
        mapper = mock(KnowledgeCatalogMapper.class);
        authorization = mock(AuthorizationEnforcer.class);
        retrieval = mock(KnowledgeRetrievalService.class);
        provider = new PlatformRuntimeKnowledgeProvider(
            new FrozenRuntimePrincipalResolver(),
            mapper,
            authorization,
            mock(KnowledgeAuthorizationContextFactory.class),
            retrieval
        );
        AgentKnowledgeBase base = new AgentKnowledgeBase();
        base.setId(500L);
        base.setKnowledgeKey("finance-policy");
        base.setProviderType("postgres_pgvector");
        base.setStatus("active");
        when(mapper.selectBaseById(500L)).thenReturn(base);
    }

    @Test
    void resolvesOnlyFrozenTaskGrantedKnowledgeAndPreservesApprovalRequirement() {
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.APPROVAL_REQUIRED));

        var definitions = provider.resolve(request(true));

        assertEquals(1, definitions.size());
        assertEquals(500L, definitions.getFirst().id());
        assertTrue(definitions.getFirst().requiresApproval());
    }

    @Test
    void currentDenyImmediatelyRemovesAndRejectsFrozenKnowledge() {
        when(authorization.decide(any(), any())).thenReturn(decision(PermissionEffect.DENY));

        assertTrue(provider.resolve(request(true)).isEmpty());
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> provider.search(request(true), 500L, "expense", 5)
        );

        assertEquals(403, exception.getCode());
        verify(retrieval, never()).retrieve(any(), any(), any(), any(), any(), any(),
            org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void agentBindingCannotBypassMissingTaskResourceGrant() {
        assertThrows(SecurityException.class, () -> provider.resolve(request(false)));
    }

    private AgentRunRequest request(boolean taskGrant) {
        Map<String, Object> resourceSnapshot = Map.of(
            "knowledgeKey", "finance-policy",
            "name", "Finance policy",
            "description", "Approved finance rules",
            "providerType", "postgres_pgvector",
            "config", Map.of("topK", 5, "vectorWeight", 0)
        );
        List<Map<String, Object>> taskResources = taskGrant
            ? List.of(Map.of(
                "resourceType", "knowledge_base", "resourceId", 500L, "permission", "read"
            ))
            : List.of();
        return new AgentRunRequest(
            new RuntimeExecutionKey("run-1", "trace-1"),
            101L, null, 10L, 20L, 30L, 100L, "agent", "session", "input", "system",
            new RuntimeModelConfig("openai", "model", null, "env:MODEL_KEY", Map.of()),
            "workspace", 10,
            Map.of("principalId", 101L, "principalType", "human", "roles", List.of("member")),
            Map.of(
                "taskResourceSnapshot", Map.of(
                    "agentVersionId", 100L, "resources", taskResources
                ),
                "resourceBindings", List.of(Map.of(
                    "resourceType", "knowledge_base",
                    "resourceId", 500L,
                    "permission", "read",
                    "config", Map.of("resourceSnapshot", resourceSnapshot)
                ))
            )
        );
    }

    private AuthorizationDecision decision(PermissionEffect effect) {
        return new AuthorizationDecision(effect, effect.name(), effect.name(), List.of());
    }
}
