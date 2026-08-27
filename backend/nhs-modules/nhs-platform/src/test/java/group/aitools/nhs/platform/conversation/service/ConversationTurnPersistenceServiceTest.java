package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.domain.AgentConversationTurn;
import group.aitools.nhs.platform.conversation.mapper.ConversationTurnMapper;
import group.aitools.nhs.platform.conversation.web.CreateConversationTurnRequest;
import group.aitools.nhs.platform.embed.service.EmbedRuntimeSnapshotFactory;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.memory.service.MemoryRuntimeSnapshotService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ConversationTurnPersistenceServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private ConversationTurnMapper mapper;
    private ConversationAgentRoutingService routing;
    private ConversationAttachmentService attachments;
    private PlatformIdGenerator ids;
    private JsonMapper jsonMapper;
    private ConversationTurnPersistenceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ConversationTurnMapper.class);
        routing = mock(ConversationAgentRoutingService.class);
        attachments = mock(ConversationAttachmentService.class);
        ids = mock(PlatformIdGenerator.class);
        jsonMapper = JsonMapper.builder().build();
        service = new ConversationTurnPersistenceService(
            ids, mapper, routing, attachments,
            mock(EmbedRuntimeSnapshotFactory.class), mock(MemoryRuntimeSnapshotService.class),
            jsonMapper
        );
        when(mapper.lockOwnedActiveConversation(7L, 101L)).thenReturn(conversation());
    }

    @Test
    void exactIdempotentReplayReturnsExistingTurnWithoutReauthorizingRoute() {
        CreateConversationTurnRequest request = request("same input");
        AgentConversationTurn existing = turn(requestHash(request));
        when(mapper.selectTurnByKey(7L, ContentHashing.sha256("idem"))).thenReturn(existing);

        var result = service.begin(MEMBER, 7L, request);

        assertTrue(result.replayed());
        assertEquals(90L, result.turn().getId());
        assertNull(result.runtimeRequest());
        verify(routing, never()).route(any(), any(), any());
    }

    @Test
    void sameIdempotencyKeyWithDifferentInputIsRejected() {
        AgentConversationTurn existing = turn(requestHash(request("first")));
        when(mapper.selectTurnByKey(7L, ContentHashing.sha256("idem"))).thenReturn(existing);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.begin(MEMBER, 7L, request("second"))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(routing, never()).route(any(), any(), any());
    }

    @Test
    void parallelNonIdempotentTurnIsRejectedBeforeAgentResolution() {
        when(mapper.selectTurnByKey(any(), any())).thenReturn(null);
        AgentConversationTurn active = new AgentConversationTurn();
        active.setId(91L);
        active.setStatus("running");
        when(mapper.selectActiveTurn(7L)).thenReturn(active);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.begin(MEMBER, 7L, request("second"))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(routing, never()).route(any(), any(), any());
    }

    @Test
    void activeTurnLookupIsOwnerScopedAndCanReturnNoActiveTurn() {
        when(mapper.selectOwnedActiveConversation(7L, 101L)).thenReturn(conversation());
        assertNull(service.ownedActiveTurn(MEMBER, 7L));

        AgentConversationTurn active = runningTurn();
        when(mapper.selectActiveTurn(7L)).thenReturn(active);
        assertEquals(90L, service.ownedActiveTurn(MEMBER, 7L).id());

        when(mapper.selectOwnedActiveConversation(7L, 101L)).thenReturn(null);
        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.ownedActiveTurn(MEMBER, 7L)
        );
        assertEquals(HttpStatus.NOT_FOUND, exception.getCode());
    }

    @Test
    void assistantResponseByteLimitDoesNotSplitUnicodeSurrogatePair() {
        when(ids.nextId()).thenReturn(800L);
        when(mapper.lockTurn(90L)).thenReturn(runningTurn());
        when(mapper.lockOwnedActiveConversation(7L, 101L)).thenReturn(conversation());
        when(mapper.nextMessageSequence(7L)).thenReturn(2);
        when(mapper.insertMessage(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(1);
        when(mapper.finishTurn(any(), any(), isNull(), any())).thenReturn(1);
        String response = "a".repeat(1_048_575) + "\uD83D\uDE00";

        service.finish(90L, "succeeded", response, null);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertMessage(
            any(), any(), anyInt(), any(), any(), content.capture(), any(), any(), any(), any(), any()
        );
        assertEquals(1_048_575, content.getValue().length());
        assertFalse(Character.isSurrogate(content.getValue().charAt(content.getValue().length() - 1)));
    }

    @Test
    void rollingSummaryTailDoesNotStartInsideUnicodeSurrogatePair() {
        when(ids.nextId()).thenReturn(800L);
        when(mapper.lockTurn(90L)).thenReturn(runningTurn());
        AgentConversation conversation = conversation();
        String suffix = "最近一次助手回复(succeeded): ok";
        int previousLength = 12_001 - 1 - suffix.length();
        conversation.setSummary("\uD83D\uDE00" + "a".repeat(previousLength - 2));
        when(mapper.lockOwnedActiveConversation(7L, 101L)).thenReturn(conversation);
        when(mapper.nextMessageSequence(7L)).thenReturn(2);
        when(mapper.insertMessage(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(1);
        when(mapper.finishTurn(any(), any(), isNull(), any())).thenReturn(1);

        service.finish(90L, "succeeded", "ok", null);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateConversationSummary(any(), any(), summary.capture(), any());
        assertFalse(Character.isSurrogate(summary.getValue().charAt(0)));
        assertTrue(summary.getValue().length() <= 12_000);
    }

    private String requestHash(CreateConversationTurnRequest request) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("input", request.input());
        value.put("agentId", null);
        value.put("agentVersionId", null);
        value.put("attachmentIds", List.of());
        return ContentHashing.sha256(jsonMapper.writeValueAsString(value));
    }

    private CreateConversationTurnRequest request(String input) {
        return new CreateConversationTurnRequest("idem", input, null, null, List.of());
    }

    private AgentConversationTurn turn(String hash) {
        AgentConversationTurn turn = new AgentConversationTurn();
        turn.setId(90L);
        turn.setConversationId(7L);
        turn.setUserId(101L);
        turn.setRequestHash(hash);
        turn.setStatus("succeeded");
        return turn;
    }

    private AgentConversationTurn runningTurn() {
        AgentConversationTurn turn = new AgentConversationTurn();
        turn.setId(90L);
        turn.setConversationId(7L);
        turn.setUserId(101L);
        turn.setTraceId("c".repeat(64));
        turn.setAgentId(301L);
        turn.setAgentVersionId(401L);
        turn.setStatus("running");
        return turn;
    }

    private AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(7L);
        conversation.setUserId(101L);
        conversation.setSessionKey("conv-session");
        conversation.setStatus("active");
        return conversation;
    }
}
