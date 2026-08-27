package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentChatResourceScope;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.conversation.mapper.ConversationGovernanceMapper;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.conversation.web.ConversationFeedbackRequest;
import group.aitools.nhs.platform.conversation.web.ConversationResourceScopeRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ConversationGovernanceServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private ConversationGovernanceMapper mapper;
    private AuthorizationEnforcer enforcer;
    private PlatformIdGenerator ids;
    private CurrentPrincipalProvider principalProvider;
    private AgentConversationMapper conversationMapper;
    private ConversationFeedbackCandidateRecorder feedbackCandidateRecorder;
    private ConversationGovernanceService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
        enforcer = mock(AuthorizationEnforcer.class);
        ids = mock(PlatformIdGenerator.class);
        conversationMapper = mock(AgentConversationMapper.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setId(7L);
        conversation.setUserId(101L);
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(conversation);
        mapper = mock(ConversationGovernanceMapper.class);
        feedbackCandidateRecorder = mock(ConversationFeedbackCandidateRecorder.class);
        service = new ConversationGovernanceService(
            principalProvider, enforcer, ids, conversationMapper, mapper, JsonMapper.builder().build(),
            feedbackCandidateRecorder
        );
    }

    @Test
    void activeConversationIsOwnerValidatedAndCanBeAbsent() {
        when(mapper.selectActiveConversationId(101L)).thenReturn(7L).thenReturn((Long) null);

        assertEquals(7L, service.activeConversation());
        assertNull(service.activeConversation());

        verify(enforcer, org.mockito.Mockito.times(3)).requireAllowed(any(), any());
    }

    @Test
    void settingActiveConversationRejectsCrossUserIdsBeforePersistence() {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.setActiveConversation(8L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getCode());
        verify(mapper, never()).upsertActiveConversation(any(), any(), any());
    }

    @Test
    void settingActiveConversationUpsertsOnlyAfterCapabilityAndOwnerChecks() {
        when(mapper.upsertActiveConversation(any(), any(), any())).thenReturn(1);

        service.setActiveConversation(7L);

        verify(enforcer, org.mockito.Mockito.times(2)).requireAllowed(any(), any());
        verify(mapper).upsertActiveConversation(
            org.mockito.ArgumentMatchers.eq(101L),
            org.mockito.ArgumentMatchers.eq(7L),
            any()
        );
    }

    @Test
    void serviceAccountDenialHappensBeforeActiveStateLookup() {
        CurrentPrincipal serviceAccount = new CurrentPrincipal(
            101L, "worker", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
        );
        when(principalProvider.currentPrincipal()).thenReturn(serviceAccount);
        doThrow(new ServiceException("服务账号不能使用人员会话", HttpStatus.FORBIDDEN))
            .when(enforcer).requireAllowed(any(), any());

        ServiceException exception = assertThrows(ServiceException.class, service::activeConversation);

        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
        verify(mapper, never()).selectActiveConversationId(any());
    }

    @Test
    void deletingConversationClearsMatchingActivePointer() {
        when(mapper.deleteConversation(7L, 101L)).thenReturn(1);

        service.deleteConversation(7L);

        verify(mapper).clearActiveConversation(101L, 7L);
    }

    @Test
    void feedbackIsOwnerBoundAndLikeAliasIsNormalized() {
        ConversationMessageRow message = new ConversationMessageRow();
        message.setId(9L);
        message.setConversationId(7L);
        message.setSequenceNo(2);
        message.setRole("assistant");
        message.setTraceId("trace-1");
        ConversationMessageRow question = new ConversationMessageRow();
        question.setId(8L);
        question.setConversationId(7L);
        question.setSequenceNo(1);
        question.setRole("user");
        when(mapper.selectMessage(7L, 9L)).thenReturn(message);
        when(mapper.selectPreviousUserMessage(7L, 2)).thenReturn(question);
        when(ids.nextId()).thenReturn(55L);
        when(mapper.insertFeedback(any())).thenReturn(1);

        var result = service.saveFeedback(7L, new ConversationFeedbackRequest(
            9L, 11L, "like", "accurate", "helpful", "trace-1"
        ));

        assertEquals("up", result.rating());
        assertEquals(55L, result.id());
        verify(enforcer).requireAllowed(any(), any());
        verify(feedbackCandidateRecorder).record(MEMBER, message, question, "up");
    }

    @Test
    void feedbackRejectsClientTraceThatDoesNotBelongToTheMessage() {
        ConversationMessageRow message = new ConversationMessageRow();
        message.setId(9L);
        message.setConversationId(7L);
        message.setSequenceNo(2);
        message.setRole("assistant");
        message.setTraceId("server-trace");
        when(mapper.selectMessage(7L, 9L)).thenReturn(message);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.saveFeedback(7L, new ConversationFeedbackRequest(
                9L, null, "up", null, null, "another-trace"
            ))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(mapper, never()).insertFeedback(any());
        verify(feedbackCandidateRecorder, never()).record(any(), any(), any(), any());
    }

    @Test
    void feedbackRejectsUserMessages() {
        ConversationMessageRow message = new ConversationMessageRow();
        message.setId(9L);
        message.setConversationId(7L);
        message.setSequenceNo(1);
        message.setRole("user");
        message.setTraceId("trace-1");
        when(mapper.selectMessage(7L, 9L)).thenReturn(message);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.saveFeedback(7L, new ConversationFeedbackRequest(
                9L, null, "down", null, null, "trace-1"
            ))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(mapper, never()).insertFeedback(any());
    }

    @Test
    void resourceScopeChecksEachResourceAndUsesOptimisticRevision() {
        AgentChatResourceScope stored = new AgentChatResourceScope();
        stored.setConversationId(7L);
        stored.setUserId(101L);
        stored.setScopeJson("{\"dataset_ids\":[9]}");
        stored.setRevision(1);
        stored.setCreatedAt(LocalDateTime.now());
        stored.setUpdatedAt(LocalDateTime.now());
        when(mapper.selectResourceScope(7L, 101L)).thenReturn(null, stored);
        when(mapper.insertResourceScope(any(), any(), any(), any())).thenReturn(1);

        var result = service.updateResourceScope(7L, new ConversationResourceScopeRequest(
            0, Map.of("datasetIds", List.of(9L, 9L))
        ));

        assertEquals(1, result.revision());
        assertEquals(List.of(9L), result.resources().get("dataset_ids"));
        verify(enforcer, org.mockito.Mockito.times(2)).requireAllowed(any(), any());
    }

    @Test
    void unsupportedScopeKeyIsRejectedBeforePersistence() {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.updateResourceScope(7L, new ConversationResourceScopeRequest(
                0, Map.of("unknownIds", List.of(9L))
            ))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(mapper, never()).insertResourceScope(any(), any(), any(), any());
    }

    @Test
    void traceIdLongerThanDatabaseContractIsRejectedBeforePersistence() {
        ConversationMessageRow message = new ConversationMessageRow();
        message.setId(9L);
        message.setConversationId(7L);
        message.setRole("assistant");
        message.setTraceId("trace-1");
        when(mapper.selectMessage(7L, 9L)).thenReturn(message);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.saveFeedback(7L, new ConversationFeedbackRequest(
                9L, null, "up", null, null, "t".repeat(65)
            ))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(mapper, never()).insertFeedback(any());
    }
}
