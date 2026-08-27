package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.platform.agent.service.AgentVersionContentHasher;
import group.aitools.nhs.platform.embed.domain.EmbedSession;
import group.aitools.nhs.platform.embed.domain.EmbedTurn;
import group.aitools.nhs.platform.embed.mapper.EmbedChatMapper;
import group.aitools.nhs.platform.embed.persistence.row.EmbedAgentRuntimeRow;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class EmbedRuntimeSnapshotFactoryTest {

    private EmbedChatMapper mapper;
    private EmbedRuntimeSnapshotFactory factory;
    private CurrentPrincipal principal;

    @BeforeEach
    void setUp() {
        mapper = mock(EmbedChatMapper.class);
        AuthorizationEnforcer authorization = mock(AuthorizationEnforcer.class);
        AgentVersionContentHasher hasher = mock(AgentVersionContentHasher.class);
        principal = new CurrentPrincipal(
            20L, "embed", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
        );
        when(mapper.selectAgentRuntime(40L)).thenReturn(runtimeRow());
        when(mapper.selectBindings(40L)).thenReturn(List.of());
        when(hasher.hash(any(), anyList())).thenReturn("a".repeat(64));
        when(authorization.requireAllowed(any(), any())).thenReturn(new AuthorizationDecision(
            PermissionEffect.ALLOW, "EXPLICIT_ALLOW", "allowed", List.of()
        ));
        factory = new EmbedRuntimeSnapshotFactory(
            mapper, authorization, hasher, JsonMapper.builder().build()
        );
    }

    @Test
    void buildsConversationRuntimeWithoutTaskHumanMemoryOrWorkspace() {
        EmbedSession session = new EmbedSession();
        session.setId(50L);
        session.setAgentVersionId(40L);
        session.setConversationId(60L);
        session.setSessionKey("embed-session");
        EmbedTurn turn = new EmbedTurn();
        turn.setId(70L);
        turn.setTraceId("b".repeat(64));

        var request = factory.build(principal, session, turn, "hello");

        assertEquals(20L, request.userId());
        assertEquals(60L, request.conversationId());
        assertNull(request.taskId());
        assertNull(request.runId());
        assertNull(request.workspaceKey());
        assertEquals("service_account", request.authorizationSnapshot().get("principalType"));
        assertEquals(List.of(), request.attributes().get("memorySnapshot"));
        assertEquals("none", request.attributes().get("workspaceAccess"));
        assertEquals(40L, ((Map<?, ?>) request.attributes().get("taskResourceSnapshot"))
            .get("agentVersionId"));
    }

    @Test
    void highRiskToolBindingIsRejectedBeforeRuntimeStarts() {
        when(mapper.countUnsafeEmbedTools(40L)).thenReturn(1);

        ServiceException exception = assertThrows(ServiceException.class, () ->
            factory.validate(principal, 40L)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
    }

    private EmbedAgentRuntimeRow runtimeRow() {
        EmbedAgentRuntimeRow row = new EmbedAgentRuntimeRow();
        row.setAgentVersionId(40L);
        row.setAgentId(41L);
        row.setAgentKey("assistant");
        row.setAgentName("Assistant");
        row.setAgentStatus("active");
        row.setVersionStatus("published");
        row.setPublishedAt(java.time.LocalDateTime.now());
        row.setSystemPrompt("help");
        row.setRuntimeConfigJson("""
            {"modelSnapshot":{"provider":"openai","modelName":"gpt-test",
            "credentialRef":"env:MODEL_KEY"},"maxIterations":8}
            """);
        row.setWelcomeConfigJson("{}");
        row.setRoutingTagsJson("[]");
        row.setContentHash("a".repeat(64));
        return row;
    }
}
