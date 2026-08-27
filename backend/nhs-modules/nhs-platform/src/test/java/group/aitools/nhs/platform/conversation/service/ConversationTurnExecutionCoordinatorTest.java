package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeCancellationResult;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventStatus;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.runtime.spi.RuntimeSensitiveLevel;
import group.aitools.nhs.platform.execution.service.AgentRuntimeExecutionService;
import group.aitools.nhs.platform.execution.service.PersistedRuntimeEvent;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ConversationTurnExecutionCoordinatorTest {

    private AgentRuntimeExecutionService runtime;
    private ConversationTurnPersistenceService persistence;
    private ConversationTurnExecutionCoordinator coordinator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runtime = mock(AgentRuntimeExecutionService.class);
        persistence = mock(ConversationTurnPersistenceService.class);
        ObjectProvider<AgentRuntimeExecutionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(runtime);
        coordinator = new ConversationTurnExecutionCoordinator(provider, persistence, Runnable::run);
    }

    @Test
    void backgroundExecutionPersistsAssistantTextOnlyAfterDurableEvents() {
        AgentRunRequest request = request();
        when(runtime.runInternal(request)).thenReturn(Flux.just(
            persisted(request, RuntimeEventType.TEXT_DELTA, "hello"),
            persisted(request, RuntimeEventType.RUN_FINISHED, "done")
        ));

        coordinator.launch(90L, request);

        verify(persistence).finish(90L, "succeeded", "hello", null);
    }

    @Test
    void responseLimitDoesNotSplitUnicodeSurrogatePair() {
        AgentRunRequest request = request();
        String prefix = "a".repeat(1024 * 1024 - 1);
        when(runtime.runInternal(request)).thenReturn(Flux.just(
            persisted(request, RuntimeEventType.TEXT_DELTA, prefix + "\uD83D\uDE00")
        ));

        coordinator.launch(90L, request);

        verify(persistence).finish(90L, "succeeded", prefix, null);
    }

    @Test
    void retractionRemovesPreviouslyStreamedTextBeforePersistence() {
        AgentRunRequest request = request();
        when(runtime.runInternal(request)).thenReturn(Flux.just(
            persisted(request, RuntimeEventType.TEXT_DELTA, "sensitive output"),
            persisted(
                request, RuntimeEventType.CUSTOM, "输出触发安全策略，已撤回",
                Map.of("retraction", true)
            ),
            persisted(request, RuntimeEventType.FAILED, "blocked")
        ));

        coordinator.launch(90L, request);

        verify(persistence).finish(
            eq(90L), eq("failed"), eq("输出触发安全策略，已撤回"),
            org.mockito.ArgumentMatchers.any(IllegalStateException.class)
        );
    }

    @Test
    void explicitStopTargetsTheExactRuntimeExecutionKey() {
        AgentRunRequest request = request();
        java.util.concurrent.atomic.AtomicReference<Runnable> work = new java.util.concurrent.atomic.AtomicReference<>();
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRuntimeExecutionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(runtime);
        coordinator = new ConversationTurnExecutionCoordinator(provider, persistence, work::set);
        when(runtime.cancel(eq(request.executionKey()), eq("stop")))
            .thenReturn(Mono.just(new RuntimeCancellationResult(true, true)));

        coordinator.launch(90L, request);

        assertTrue(coordinator.requestStop(90L, "stop"));
        verify(runtime).cancel(request.executionKey(), "stop");
    }

    private AgentRunRequest request() {
        return new AgentRunRequest(
            new RuntimeExecutionKey("conversation-turn-90", "trace-90"),
            101L, 7L, null, null, null, 21L, "assistant", "conv-session", "input",
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
            type.name() + "-1", request.executionKey(), 7L, null, null, type,
            RuntimeEventStatus.SUCCESS, Instant.now(), summary, payload, RuntimeSensitiveLevel.PUBLIC
        );
        ExecutionEventView view = new ExecutionEventView(
            source.sourceEventId(), request.executionKey().traceId(), 7L, null, null,
            1L, type.name(), "success", summary, Map.of(), "public", LocalDateTime.now()
        );
        return new PersistedRuntimeEvent(source, view);
    }
}
