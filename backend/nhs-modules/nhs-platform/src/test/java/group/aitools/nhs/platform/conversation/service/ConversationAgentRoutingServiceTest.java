package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.web.CreateConversationTurnRequest;
import group.aitools.nhs.platform.embed.mapper.EmbedChatMapper;
import group.aitools.nhs.platform.embed.persistence.row.EmbedAgentRuntimeRow;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ConversationAgentRoutingServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private EmbedChatMapper mapper;
    private AuthorizationEnforcer authorization;
    private ConversationAgentRoutingService service;

    @BeforeEach
    void setUp() {
        mapper = mock(EmbedChatMapper.class);
        authorization = mock(AuthorizationEnforcer.class);
        service = new ConversationAgentRoutingService(mapper, authorization);
    }

    @Test
    void leadingMentionRoutesExactlyAndRemovesOnlyRoutingPrefix() {
        EmbedAgentRuntimeRow agent = agent(20L, 21L, "finance", "财务助手");
        when(mapper.selectAgentRuntimeByRouteToken("财务助手")).thenReturn(agent);

        var result = service.route(
            MEMBER, conversation(), request("@财务助手 查询本月收入", null, null)
        );

        assertEquals(21L, result.definition().getAgentVersionId());
        assertEquals("查询本月收入", result.input());
        assertEquals("财务助手", result.mentionToken());
        verify(mapper, never()).selectDefaultAgentRuntime();
        verify(authorization).requireAllowed(eq(MEMBER), any());
    }

    @Test
    void unauthorizedMentionDoesNotFallBackToDefaultAgent() {
        EmbedAgentRuntimeRow agent = agent(20L, 21L, "finance", "财务助手");
        when(mapper.selectAgentRuntimeByRouteToken("财务助手")).thenReturn(agent);
        when(authorization.requireAllowed(eq(MEMBER), any()))
            .thenThrow(new ServiceException("无权使用", HttpStatus.FORBIDDEN));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.route(MEMBER, conversation(), request("@财务助手 查询", null, null))
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
        verify(mapper, never()).selectDefaultAgentRuntime();
    }

    @Test
    void explicitAgentAndVersionMustBelongTogether() {
        when(mapper.selectAgentRuntime(21L)).thenReturn(agent(20L, 21L, "finance", "财务助手"));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.route(MEMBER, conversation(), request("查询", 999L, 21L))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(authorization, never()).requireAllowed(any(), any());
    }

    @Test
    void conversationWithoutBindingUsesPublishedDefault() {
        EmbedAgentRuntimeRow agent = agent(30L, 31L, "default", "默认助手");
        when(mapper.selectDefaultAgentRuntime()).thenReturn(agent);

        var result = service.route(MEMBER, conversation(), request("普通问题", null, null));

        assertEquals(31L, result.definition().getAgentVersionId());
        assertEquals("普通问题", result.input());
    }

    @Test
    void firstUnboundTurnUsesAuthorizedUniqueRoutingTag() {
        EmbedAgentRuntimeRow defaultAgent = agent(30L, 31L, "default", "默认助手");
        EmbedAgentRuntimeRow dataAgent = agent(40L, 41L, "data", "数据助手");
        dataAgent.setRoutingTagsJson("[\"chatbi\",\"sales\"]");
        when(mapper.selectPublishedAgentRuntimes()).thenReturn(List.of(dataAgent, defaultAgent));
        when(mapper.selectDefaultAgentRuntime()).thenReturn(defaultAgent);
        when(authorization.decide(eq(MEMBER), any())).thenReturn(
            new AuthorizationDecision(PermissionEffect.ALLOW, "test", "", List.of())
        );

        var result = service.route(
            MEMBER, conversation(), request("请用 chatbi 查 sales", null, null)
        );

        assertEquals(41L, result.definition().getAgentVersionId());
        verify(authorization).requireAllowed(eq(MEMBER), any());
    }

    private CreateConversationTurnRequest request(
        String input,
        Long agentId,
        Long versionId
    ) {
        return new CreateConversationTurnRequest("idem", input, agentId, versionId, List.of());
    }

    private AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(7L);
        conversation.setUserId(101L);
        conversation.setSessionKey("conv-session");
        conversation.setStatus("active");
        return conversation;
    }

    private EmbedAgentRuntimeRow agent(Long agentId, Long versionId, String key, String name) {
        EmbedAgentRuntimeRow row = new EmbedAgentRuntimeRow();
        row.setAgentId(agentId);
        row.setAgentVersionId(versionId);
        row.setAgentKey(key);
        row.setAgentName(name);
        row.setAgentStatus("active");
        row.setVersionStatus("published");
        row.setPublishedAt(LocalDateTime.now());
        return row;
    }
}
