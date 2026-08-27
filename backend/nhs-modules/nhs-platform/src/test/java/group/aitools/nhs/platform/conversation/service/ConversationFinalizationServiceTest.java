package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.domain.AgentConversationTurn;
import group.aitools.nhs.platform.conversation.mapper.ConversationTurnMapper;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.nhs.portal.memory.PortalMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ConversationFinalizationServiceTest {

    private final CurrentPrincipal principal = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private ConversationTurnMapper mapper;
    private PortalMemoryService memoryService;
    private ConversationFinalizationService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        mapper = mock(ConversationTurnMapper.class);
        memoryService = mock(PortalMemoryService.class);
        when(principalProvider.currentPrincipal()).thenReturn(principal);
        when(mapper.lockOwnedActiveConversation(7L, 101L)).thenReturn(conversation());
        service = new ConversationFinalizationService(
            principalProvider, authorizationEnforcer, mapper
        );
    }

    @Test
    void runningTurnIsReportedWithoutMutatingSummary() {
        AgentConversationTurn turn = new AgentConversationTurn();
        turn.setStatus("running");
        when(mapper.selectActiveTurn(7L)).thenReturn(turn);

        var result = service.finalizeConversation(7L);

        assertFalse(result.finalized());
        assertEquals("active_turn_running", result.reason());
        verify(mapper, never()).updateConversationSummary(any(), any(), any(), any());
    }

    @Test
    void summaryIsRebuiltAndSecondCallIsAnIdempotentNoOp() {
        when(mapper.selectRecentMessages(7L, 48)).thenReturn(List.of(
            message(2, "assistant", "结论"), message(1, "user", "问题")
        ));
        when(mapper.updateConversationSummary(eq(7L), eq(101L), eq("用户: 问题\n助手: 结论"), any()))
            .thenReturn(1);

        var first = service.finalizeConversation(7L);
        assertTrue(first.finalized());
        assertEquals("summary_refreshed", first.reason());
        verify(mapper).updateConversationSummary(
            eq(7L), eq(101L), eq("用户: 问题\n助手: 结论"), any()
        );

        AgentConversation conversation = conversation();
        conversation.setSummary("用户: 问题\n助手: 结论");
        when(mapper.lockOwnedActiveConversation(7L, 101L)).thenReturn(conversation);
        var second = service.finalizeConversation(7L);
        assertTrue(second.finalized());
        assertEquals("already_finalized", second.reason());
    }

    @Test
    void emptyConversationHasAnExplicitReason() {
        when(mapper.selectRecentMessages(7L, 48)).thenReturn(List.of());

        var result = service.finalizeConversation(7L);

        assertFalse(result.finalized());
        assertEquals("no_messages", result.reason());
    }

    @Test
    void finalizeProjectsDurableMemoryEvenWhenConversationSummaryAlreadyMatches() {
        AgentConversation conversation = conversation();
        conversation.setSummary("用户: 问题\n助手: 结论");
        when(mapper.lockOwnedActiveConversation(7L, 101L)).thenReturn(conversation);
        when(mapper.selectRecentMessages(7L, 48)).thenReturn(List.of(
            message(1, "user", "问题"), message(2, "assistant", "结论")
        ));
        ConversationFinalizationService projected = new ConversationFinalizationService(
            principalProvider, authorizationEnforcer, mapper, memoryService
        );

        var result = projected.finalizeConversation(7L);

        assertTrue(result.finalized());
        assertEquals("already_finalized", result.reason());
        verify(memoryService).finalizeConversationSummary(
            eq(101L), eq(7L), eq("用户: 问题\n助手: 结论"), any()
        );
        verify(mapper, never()).updateConversationSummary(any(), any(), any(), any());
    }

    private AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(7L);
        conversation.setUserId(101L);
        conversation.setStatus("active");
        conversation.setSummary("");
        return conversation;
    }

    private ConversationMessageRow message(int sequence, String role, String content) {
        ConversationMessageRow row = new ConversationMessageRow();
        row.setSequenceNo(sequence);
        row.setRole(role);
        row.setContent(content);
        return row;
    }
}
