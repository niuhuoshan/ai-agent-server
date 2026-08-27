package group.aitools.nhs.platform.execution;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.AgentRuntime;
import group.aitools.nhs.runtime.spi.RuntimeCancellationResult;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventStatus;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.runtime.spi.RuntimeResumeDecision;
import group.aitools.nhs.runtime.spi.RuntimeSensitiveLevel;
import group.aitools.nhs.platform.execution.service.AgentRuntimeExecutionService;
import group.aitools.nhs.platform.execution.service.ExecutionEventPersistenceService;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class AgentRuntimeExecutionServiceTest {

    private AgentRuntime runtime;
    private ExecutionEventPersistenceService persistenceService;
    private AgentRuntimeExecutionService service;

    @BeforeEach
    void setUp() {
        runtime = mock(AgentRuntime.class);
        persistenceService = mock(ExecutionEventPersistenceService.class);
        service = new AgentRuntimeExecutionService(runtime, persistenceService);
    }

    @Test
    void runAndResumeCannotBypassEventPersistence() {
        AgentRunRequest runRequest = runRequest();
        AgentResumeRequest resumeRequest = resumeRequest();
        RuntimeEvent runtimeEvent = runtimeEvent();
        ExecutionEventView persisted = persistedEvent();
        when(runtime.stream(runRequest)).thenReturn(Flux.just(runtimeEvent));
        when(runtime.resume(resumeRequest)).thenReturn(Flux.just(runtimeEvent));
        when(persistenceService.persist(org.mockito.ArgumentMatchers.any())).thenReturn(
            Flux.just(persisted)
        );

        StepVerifier.create(service.run(runRequest)).expectNext(persisted).verifyComplete();
        StepVerifier.create(service.resume(resumeRequest)).expectNext(persisted).verifyComplete();

        verify(runtime).stream(runRequest);
        verify(runtime).resume(resumeRequest);
        verify(persistenceService, org.mockito.Mockito.times(2)).persist(
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void cancellationDelegatesToTheSameRuntimeExecutionKey() {
        RuntimeExecutionKey key = new RuntimeExecutionKey("execution-1", "trace-1");
        when(runtime.cancel(key, "stop")).thenReturn(Mono.just(
            new RuntimeCancellationResult(true, true)
        ));

        StepVerifier.create(service.cancel(key, "stop"))
            .expectNext(new RuntimeCancellationResult(true, true))
            .verifyComplete();
    }

    private AgentRunRequest runRequest() {
        return new AgentRunRequest(
            new RuntimeExecutionKey("execution-1", "trace-1"),
            101L, 201L, 301L, 401L, 501L, 601L,
            "agent", "session-1", "input", "prompt",
            new RuntimeModelConfig(
                "openai", "model", null, "env:MODEL_API_KEY", Map.of()
            ),
            "run-401", 10, Map.of(), Map.of()
        );
    }

    private AgentResumeRequest resumeRequest() {
        return new AgentResumeRequest(
            new RuntimeExecutionKey("execution-1", "trace-1"),
            101L, 201L, 301L, 401L, 501L,
            "session-1", "reply-1", RuntimeResumeDecision.APPROVE,
            Map.of("id", "tool-1", "name", "write_file", "input", Map.of()),
            Map.of()
        );
    }

    private RuntimeEvent runtimeEvent() {
        return new RuntimeEvent(
            "source-1",
            new RuntimeExecutionKey("execution-1", "trace-1"),
            201L, 401L, 501L,
            RuntimeEventType.TEXT_DELTA,
            RuntimeEventStatus.SUCCESS,
            Instant.parse("2026-08-14T04:00:00Z"),
            "hello", Map.of("delta", "hello"), RuntimeSensitiveLevel.PUBLIC
        );
    }

    private ExecutionEventView persistedEvent() {
        return new ExecutionEventView(
            "event-1", "trace-1", 201L, 401L, 501L, 1L,
            "text_delta", "success", "hello", Map.of("delta", "hello"),
            "public", LocalDateTime.of(2026, 8, 14, 4, 0)
        );
    }
}
