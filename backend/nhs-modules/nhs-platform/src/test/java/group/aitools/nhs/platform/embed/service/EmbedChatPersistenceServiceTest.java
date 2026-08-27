package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.embed.domain.EmbedSession;
import group.aitools.nhs.platform.embed.domain.EmbedTurn;
import group.aitools.nhs.platform.embed.mapper.EmbedChatMapper;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class EmbedChatPersistenceServiceTest {

    private EmbedChatMapper mapper;
    private PlatformIdGenerator ids;
    private EmbedRuntimeSnapshotFactory snapshots;
    private EmbedChatPersistenceService service;
    private AuthenticatedServiceAccount authenticated;

    @BeforeEach
    void setUp() {
        mapper = mock(EmbedChatMapper.class);
        ids = mock(PlatformIdGenerator.class);
        snapshots = mock(EmbedRuntimeSnapshotFactory.class);
        CurrentPrincipal principal = new CurrentPrincipal(
            20L, "embed-worker", PrincipalType.SERVICE_ACCOUNT,
            Set.of(PlatformRole.SERVICE_ACCOUNT)
        );
        authenticated = new AuthenticatedServiceAccount(
            principal, 10L, "embed-app", "embed", 30L, Set.of("chat:invoke")
        );
        service = new EmbedChatPersistenceService(
            mapper, ids, snapshots, JsonMapper.builder().build()
        );
    }

    @Test
    void sessionStoresMachinePrincipalTypeAndOnlyExternalUserHash() {
        when(ids.nextId()).thenReturn(100L, 101L);
        when(ids.nextUuid()).thenReturn("session-random");
        when(mapper.insertConversation(any())).thenReturn(1);
        when(mapper.insertSession(any())).thenReturn(1);

        var result = service.createSession(authenticated, 40L, "customer@example.com", 60);

        ArgumentCaptor<AgentConversation> conversation = ArgumentCaptor.forClass(AgentConversation.class);
        ArgumentCaptor<EmbedSession> session = ArgumentCaptor.forClass(EmbedSession.class);
        verify(mapper).insertConversation(conversation.capture());
        verify(mapper).insertSession(session.capture());
        assertEquals("service_account", conversation.getValue().getPrincipalType());
        assertEquals(20L, conversation.getValue().getUserId());
        assertEquals(ContentHashing.sha256("customer@example.com"), session.getValue().getExternalUserHash());
        assertNotEquals("customer@example.com", session.getValue().getExternalUserHash());
        assertEquals(101L, result.id());
    }

    @Test
    void completedDuplicateTurnReplaysAndDifferentPayloadIsRejected() {
        EmbedSession session = session(10L, 20L);
        when(mapper.lockSession(50L)).thenReturn(session);
        EmbedTurn existing = turn("hello", "succeeded");
        when(mapper.selectTurnByKey(50L, ContentHashing.sha256("request-1")))
            .thenReturn(existing);

        var replay = service.beginTurn(authenticated, 50L, "request-1", "hello");
        ServiceException mismatch = assertThrows(ServiceException.class, () ->
            service.beginTurn(authenticated, 50L, "request-1", "different")
        );

        assertTrue(replay.replayed());
        assertEquals(HttpStatus.CONFLICT, mismatch.getCode());
        verify(mapper, never()).insertTurn(any());
        verify(mapper, never()).insertUserMessage(
            anyLong(), anyLong(), anyInt(), anyString(), anyString(), anyString(),
            any(), anyLong(), any()
        );
    }

    @Test
    void newTurnPersistsOneUserMessageWithHashedIdempotency() {
        EmbedSession session = session(10L, 20L);
        when(mapper.lockSession(50L)).thenReturn(session);
        when(ids.nextId()).thenReturn(60L, 61L);
        when(mapper.insertTurn(any())).thenReturn(1);
        when(mapper.nextMessageSequence(70L)).thenReturn(1);
        when(mapper.insertUserMessage(
            anyLong(), anyLong(), anyInt(), anyString(), anyString(), anyString(),
            any(), anyLong(), any()
        )).thenReturn(1);
        when(mapper.touchSession(eq(50L), any())).thenReturn(1);
        when(mapper.touchConversation(eq(70L), any())).thenReturn(1);

        var started = service.beginTurn(authenticated, 50L, "request-1", "hello");

        ArgumentCaptor<EmbedTurn> turn = ArgumentCaptor.forClass(EmbedTurn.class);
        verify(mapper).insertTurn(turn.capture());
        assertFalse(started.replayed());
        assertEquals(ContentHashing.sha256("request-1"), turn.getValue().getIdempotencyHash());
        assertFalse(turn.getValue().getTraceId().contains("request-1"));
        verify(mapper).insertUserMessage(
            eq(61L), eq(70L), eq(1), eq(turn.getValue().getTraceId()), eq("hello"),
            anyString(), eq(null), eq(40L), any()
        );
    }

    @Test
    void applicationOrServiceAccountMismatchCannotOpenSession() {
        when(mapper.lockSession(50L)).thenReturn(session(999L, 20L));
        ServiceException application = assertThrows(ServiceException.class, () ->
            service.beginTurn(authenticated, 50L, "request-1", "hello")
        );
        when(mapper.lockSession(50L)).thenReturn(session(10L, 999L));
        ServiceException account = assertThrows(ServiceException.class, () ->
            service.beginTurn(authenticated, 50L, "request-1", "hello")
        );

        assertEquals(HttpStatus.NOT_FOUND, application.getCode());
        assertEquals(HttpStatus.NOT_FOUND, account.getCode());
    }

    @Test
    void widgetRetryReplaysBeforeRequiringAlreadyAttachedFilesToBeReady() {
        EmbedAttachmentService attachments = mock(EmbedAttachmentService.class);
        service = new EmbedChatPersistenceService(
            mapper, ids, snapshots, JsonMapper.builder().build(), attachments
        );
        EmbedSession session = session(10L, 20L);
        var prepared = new EmbedAttachmentService.PreparedRequest(
            "hello", Map.of("page", "orders"), List.of(90L),
            "canonical-widget-request"
        );
        EmbedTurn existing = turn("unused", "running");
        existing.setRequestHash(ContentHashing.sha256(prepared.requestMaterial()));
        when(mapper.lockSession(50L)).thenReturn(session);
        when(attachments.prepareRequest(
            "hello", List.of(90L), Map.of("page", "orders")
        )).thenReturn(prepared);
        when(mapper.selectTurnByKey(50L, ContentHashing.sha256("request-1")))
            .thenReturn(existing);

        var replay = service.beginWidgetTurn(
            authenticated, 50L, "request-1", "hello", List.of(90L), Map.of("page", "orders")
        );

        assertTrue(replay.replayed());
        verify(attachments, never()).prepare(any(), any(), any());
    }

    @Test
    void aggregateRuntimeInputIsRejectedBeforeAnyTurnOrMessageIsPersisted() {
        EmbedSession session = session(10L, 20L);
        when(mapper.lockSession(50L)).thenReturn(session);
        doThrow(new ServiceException("消息超过128KB", HttpStatus.BAD_REQUEST))
            .when(snapshots).validateInput("oversized-runtime-input");
        EmbedAttachmentService.PreparedMessage prepared = new EmbedAttachmentService.PreparedMessage(
            "hello", "oversized-runtime-input", "{}", "canonical", List.of()
        );

        ServiceException error = assertThrows(ServiceException.class, () ->
            invokeBeginPrepared(session, prepared)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getCode());
        verify(mapper, never()).insertTurn(any());
        verify(mapper, never()).insertUserMessage(
            anyLong(), anyLong(), anyInt(), anyString(), anyString(), anyString(),
            any(), anyLong(), any()
        );
    }

    private EmbedChatPersistenceService.TurnStart invokeBeginPrepared(
        EmbedSession session,
        EmbedAttachmentService.PreparedMessage prepared
    ) {
        EmbedAttachmentService attachments = mock(EmbedAttachmentService.class);
        service = new EmbedChatPersistenceService(
            mapper, ids, snapshots, JsonMapper.builder().build(), attachments
        );
        var request = new EmbedAttachmentService.PreparedRequest(
            prepared.input(), Map.of(), List.of(), prepared.requestMaterial()
        );
        when(attachments.prepareRequest(prepared.input(), List.of(), Map.of())).thenReturn(request);
        when(attachments.prepare(authenticated, session, request)).thenReturn(prepared);
        return service.beginWidgetTurn(
            authenticated, session.getId(), "request-oversized", prepared.input(), List.of(), Map.of()
        );
    }

    private EmbedSession session(Long applicationId, Long accountId) {
        EmbedSession session = new EmbedSession();
        session.setId(50L);
        session.setApplicationId(applicationId);
        session.setServiceAccountId(accountId);
        session.setAgentVersionId(40L);
        session.setConversationId(70L);
        session.setStatus("active");
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        return session;
    }

    private EmbedTurn turn(String input, String status) {
        EmbedTurn turn = new EmbedTurn();
        turn.setId(60L);
        turn.setSessionId(50L);
        turn.setIdempotencyHash(ContentHashing.sha256("request-1"));
        turn.setRequestHash(ContentHashing.sha256(input));
        turn.setTraceId("a".repeat(64));
        turn.setStatus(status);
        return turn;
    }
}
