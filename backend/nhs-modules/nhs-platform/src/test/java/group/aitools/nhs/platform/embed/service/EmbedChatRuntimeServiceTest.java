package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.platform.embed.domain.EmbedSession;
import group.aitools.nhs.platform.embed.domain.EmbedTurn;
import group.aitools.nhs.platform.embed.service.EmbedChatPersistenceService.TurnStart;
import group.aitools.nhs.platform.execution.service.AgentRuntimeExecutionService;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class EmbedChatRuntimeServiceTest {

    @Test
    void replayReturnsPersistedEventsWithoutCallingAgentRuntime() {
        EmbedChatPersistenceService persistence = mock(EmbedChatPersistenceService.class);
        EmbedRuntimeSnapshotFactory snapshots = mock(EmbedRuntimeSnapshotFactory.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRuntimeExecutionService> provider = mock(ObjectProvider.class);
        EmbedSession session = new EmbedSession();
        EmbedTurn turn = new EmbedTurn();
        turn.setId(70L);
        when(persistence.beginTurn(any(), any(), any(), any()))
            .thenReturn(new TurnStart(session, turn, true, "hello"));
        when(persistence.replayEvents(session, turn)).thenReturn(List.of());
        EmbedChatRuntimeService service = new EmbedChatRuntimeService(persistence, snapshots, provider);

        var result = service.invoke(authenticated(), 50L, "request-1", "hello");

        assertTrue(result.replayed());
        assertEquals(List.of(), result.events().collectList().block());
        verify(provider, never()).getIfAvailable();
        verify(snapshots, never()).build(any(), any(), any(), any());
    }

    @Test
    void unavailableRuntimeMarksNewTurnFailed() {
        EmbedChatPersistenceService persistence = mock(EmbedChatPersistenceService.class);
        EmbedRuntimeSnapshotFactory snapshots = mock(EmbedRuntimeSnapshotFactory.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRuntimeExecutionService> provider = mock(ObjectProvider.class);
        EmbedSession session = new EmbedSession();
        EmbedTurn turn = new EmbedTurn();
        turn.setId(70L);
        when(persistence.beginTurn(any(), any(), any(), any()))
            .thenReturn(new TurnStart(session, turn, false, "hello"));
        when(provider.getIfAvailable()).thenReturn(null);
        EmbedChatRuntimeService service = new EmbedChatRuntimeService(persistence, snapshots, provider);

        ServiceException exception = assertThrows(ServiceException.class, () ->
            service.invoke(authenticated(), 50L, "request-1", "hello")
        );

        assertEquals(503, exception.getCode());
        verify(persistence).fail(any(), any());
    }

    @Test
    void anotherNodesExecutionLeaseIsStreamedWithoutUnownedFailure() {
        EmbedChatPersistenceService persistence = mock(EmbedChatPersistenceService.class);
        EmbedRuntimeSnapshotFactory snapshots = mock(EmbedRuntimeSnapshotFactory.class);
        EmbedChatExecutionCoordinator coordinator = mock(EmbedChatExecutionCoordinator.class);
        EmbedEventStreamService eventStream = mock(EmbedEventStreamService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRuntimeExecutionService> provider = mock(ObjectProvider.class);
        EmbedSession session = new EmbedSession();
        EmbedTurn turn = new EmbedTurn();
        turn.setId(70L);
        TurnStart start = new TurnStart(session, turn, false, "hello");
        when(persistence.beginWidgetTurn(any(), any(), any(), any(), any(), any()))
            .thenReturn(start);
        when(coordinator.launch(any(), any())).thenReturn(false);
        when(eventStream.stream(session, turn, 0)).thenReturn(Flux.empty());
        EmbedChatRuntimeService service = new EmbedChatRuntimeService(
            persistence, snapshots, provider, coordinator, eventStream
        );

        var invocation = service.invokeWidget(
            authenticated(), 50L, "request-1", "hello", List.of(), java.util.Map.of(), 0
        );

        assertEquals(List.of(), invocation.events().collectList().block());
        verify(persistence, never()).fail(any(), any());
    }

    private AuthenticatedServiceAccount authenticated() {
        CurrentPrincipal principal = new CurrentPrincipal(
            20L, "embed", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
        );
        return new AuthenticatedServiceAccount(
            principal, 10L, "embed-app", "embed", 30L, Set.of("chat:invoke")
        );
    }
}
