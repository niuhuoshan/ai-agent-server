package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeCancellationResult;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventStatus;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.runtime.spi.RuntimeSensitiveLevel;
import group.aitools.nhs.platform.embed.domain.EmbedSession;
import group.aitools.nhs.platform.embed.domain.EmbedTurn;
import group.aitools.nhs.platform.embed.service.EmbedChatPersistenceService.TurnStart;
import group.aitools.nhs.platform.execution.service.AgentRuntimeExecutionService;
import group.aitools.nhs.platform.execution.service.PersistedRuntimeEvent;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class EmbedChatExecutionCoordinatorTest {

    private AgentRuntimeExecutionService runtime;
    private EmbedRuntimeSnapshotFactory snapshots;
    private EmbedChatPersistenceService persistence;
    private EmbedChatExecutionCoordinator coordinator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runtime = mock(AgentRuntimeExecutionService.class);
        snapshots = mock(EmbedRuntimeSnapshotFactory.class);
        persistence = mock(EmbedChatPersistenceService.class);
        ObjectProvider<AgentRuntimeExecutionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(runtime);
        when(persistence.claimExecution(eq(80L), anyString())).thenReturn(true);
        when(persistence.heartbeatExecution(eq(80L), anyString())).thenReturn(true);
        coordinator = new EmbedChatExecutionCoordinator(provider, snapshots, persistence, Runnable::run);
    }

    @Test
    void executionFinishesWithoutAnyBrowserSubscriber() {
        TurnStart start = start();
        AgentRunRequest request = request();
        when(snapshots.build(authenticated().principal(), start.session(), start.turn(), "hello"))
            .thenReturn(request);
        when(runtime.runInternal(request)).thenReturn(Flux.just(
            persisted(request, RuntimeEventType.TEXT_DELTA, "answer"),
            persisted(request, RuntimeEventType.RUN_FINISHED, "done")
        ));

        coordinator.launch(authenticated(), start);

        verify(persistence).finishOwned(
            eq(start.session()), eq(start.turn()), anyString(), eq("succeeded"), eq("answer"), eq(null)
        );
    }

    @Test
    void durableStopFactWinsEvenWhenProviderOmitsCancelledEvent() {
        TurnStart start = start();
        AgentRunRequest request = request();
        when(snapshots.build(authenticated().principal(), start.session(), start.turn(), "hello"))
            .thenReturn(request);
        when(runtime.runInternal(request)).thenReturn(Flux.empty());
        when(persistence.stopRequested(start.turn().getId())).thenReturn(true);

        coordinator.launch(authenticated(), start);

        verify(persistence).finishOwned(
            eq(start.session()), eq(start.turn()), anyString(), eq("cancelled"), eq(""), eq(null)
        );
    }

    @Test
    void retractionRemovesPreviouslyStreamedTextBeforePersistence() {
        TurnStart start = start();
        AgentRunRequest request = request();
        when(snapshots.build(authenticated().principal(), start.session(), start.turn(), "hello"))
            .thenReturn(request);
        when(runtime.runInternal(request)).thenReturn(Flux.just(
            persisted(request, RuntimeEventType.TEXT_DELTA, "sensitive output"),
            persisted(
                request, RuntimeEventType.CUSTOM, "输出触发安全策略，已撤回",
                Map.of("retraction", true)
            ),
            persisted(request, RuntimeEventType.FAILED, "blocked")
        ));

        coordinator.launch(authenticated(), start);

        verify(persistence).finishOwned(
            eq(start.session()), eq(start.turn()), anyString(), eq("failed"),
            eq("输出触发安全策略，已撤回"),
            org.mockito.ArgumentMatchers.any(IllegalStateException.class)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitStopTargetsOnlyTheExactEmbedExecution() {
        TurnStart start = start();
        AgentRunRequest request = request();
        ObjectProvider<AgentRuntimeExecutionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(runtime);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        coordinator = new EmbedChatExecutionCoordinator(provider, snapshots, persistence, queued::set);
        when(snapshots.build(authenticated().principal(), start.session(), start.turn(), "hello"))
            .thenReturn(request);
        when(runtime.cancel(eq(request.executionKey()), eq("stop")))
            .thenReturn(Mono.just(new RuntimeCancellationResult(true, true)));

        coordinator.launch(authenticated(), start);

        assertTrue(coordinator.requestStop(start.turn().getId(), "stop"));
        verify(runtime).cancel(request.executionKey(), "stop");
    }

    private TurnStart start() {
        EmbedSession session = new EmbedSession();
        session.setId(50L);
        session.setConversationId(70L);
        session.setAgentVersionId(40L);
        session.setSessionKey("embed-session");
        EmbedTurn turn = new EmbedTurn();
        turn.setId(80L);
        turn.setTraceId("trace-embed");
        return new TurnStart(session, turn, false, "hello");
    }

    private AuthenticatedServiceAccount authenticated() {
        return new AuthenticatedServiceAccount(
            new CurrentPrincipal(
                20L, "embed", PrincipalType.SERVICE_ACCOUNT,
                Set.of(PlatformRole.SERVICE_ACCOUNT)
            ),
            10L, "embed-app", "embed", 30L, Set.of("chat:invoke")
        );
    }

    private AgentRunRequest request() {
        return new AgentRunRequest(
            new RuntimeExecutionKey("embed-turn-80", "trace-embed"),
            20L, 70L, null, null, null, 40L, "assistant", "embed-session", "hello",
            "system", new RuntimeModelConfig("openai", "model", null, "ref", Map.of()),
            null, 12, Map.of(), Map.of()
        );
    }

    private PersistedRuntimeEvent persisted(
        AgentRunRequest request,
        RuntimeEventType type,
        String summary
    ) {
        return persisted(request, type, summary, Map.of());
    }

    private PersistedRuntimeEvent persisted(
        AgentRunRequest request,
        RuntimeEventType type,
        String summary,
        Map<String, Object> payload
    ) {
        RuntimeEvent source = new RuntimeEvent(
            type.name() + "-1", request.executionKey(), 70L, null, null, type,
            RuntimeEventStatus.SUCCESS, Instant.now(), summary, payload, RuntimeSensitiveLevel.PUBLIC
        );
        ExecutionEventView view = new ExecutionEventView(
            source.sourceEventId(), request.executionKey().traceId(), 70L, null, null,
            1L, type.name(), "success", summary, Map.of(), "public", LocalDateTime.now()
        );
        return new PersistedRuntimeEvent(source, view);
    }
}
