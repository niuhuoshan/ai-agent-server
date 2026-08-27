package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeCancellationResult;
import group.aitools.nhs.runtime.spi.RuntimeEventStatus;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.TextBlockDeltaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class AgentScopeRuntimeAdapterTest {

    private AgentScopeInvocationFactory invocationFactory;
    private AgentScopeRuntimeAdapter adapter;

    @BeforeEach
    void setUp() {
        invocationFactory = mock(AgentScopeInvocationFactory.class);
        adapter = new AgentScopeRuntimeAdapter(
            invocationFactory,
            new AgentScopeEventMapper(new ObjectMapper())
        );
    }

    @Test
    void convertsFactoryFailureToFailedEventAndReleasesReservation() {
        AgentRunRequest request = RuntimeFixtures.runRequest();
        when(invocationFactory.create(request))
            .thenThrow(new IllegalStateException("apiKey=sk-1234567890"));

        StepVerifier.create(adapter.stream(request))
            .assertNext(event -> {
                assertEquals(RuntimeEventType.FAILED, event.type());
                assertEquals(RuntimeEventStatus.FAILED, event.status());
            })
            .verifyComplete();

        assertEquals(0, adapter.activeInvocationCount());
    }

    @Test
    void convertsStreamFailureToFailedEventAndClosesInvocation() {
        AgentRunRequest request = RuntimeFixtures.runRequest();
        AgentScopeInvocation invocation = mock(AgentScopeInvocation.class);
        when(invocationFactory.create(request)).thenReturn(invocation);
        when(invocation.stream(request)).thenReturn(Flux.error(new IllegalArgumentException("boom")));

        StepVerifier.create(adapter.stream(request))
            .assertNext(event -> assertEquals(RuntimeEventType.FAILED, event.type()))
            .verifyComplete();

        verify(invocation).close();
        assertEquals(0, adapter.activeInvocationCount());
    }

    @Test
    void rejectsDuplicateExecutionWithoutMaterializingSecondInvocation() {
        AgentRunRequest request = RuntimeFixtures.runRequest();
        AgentScopeInvocation invocation = mock(AgentScopeInvocation.class);
        when(invocationFactory.create(request)).thenReturn(invocation);
        when(invocation.stream(request)).thenReturn(Flux.never());

        Disposable first = adapter.stream(request).subscribe();
        assertEquals(1, adapter.activeInvocationCount());

        StepVerifier.create(adapter.stream(request))
            .assertNext(event -> {
                assertEquals(RuntimeEventType.FAILED, event.type());
                assertTrueMessageContains(event.summary(), "already active");
            })
            .verifyComplete();

        verify(invocationFactory, times(1)).create(request);
        first.dispose();
        verify(invocation).close();
        assertEquals(0, adapter.activeInvocationCount());
    }

    @Test
    void cancellationIsIdempotentWhileExecutionRemainsActive() {
        AgentRunRequest request = RuntimeFixtures.runRequest();
        AgentScopeInvocation invocation = mock(AgentScopeInvocation.class);
        when(invocationFactory.create(request)).thenReturn(invocation);
        when(invocation.stream(request)).thenReturn(Flux.never());
        Disposable subscription = adapter.stream(request).subscribe();

        StepVerifier.create(adapter.cancel(request.executionKey(), "  stop now  "))
            .expectNext(new RuntimeCancellationResult(true, true))
            .verifyComplete();
        StepVerifier.create(adapter.cancel(request.executionKey(), "again"))
            .expectNext(new RuntimeCancellationResult(true, false))
            .verifyComplete();

        verify(invocation, times(1)).interrupt("stop now");
        subscription.dispose();
    }

    @Test
    void closesInvocationOnNormalCompletionAndSubscriberCancellation() {
        AgentRunRequest completedRequest = RuntimeFixtures.runRequest("execution-complete");
        AgentScopeInvocation completed = mock(AgentScopeInvocation.class);
        when(invocationFactory.create(completedRequest)).thenReturn(completed);
        when(completed.stream(completedRequest)).thenReturn(Flux.just(
            new TextBlockDeltaEvent("reply-1", "text-1", "done")
        ));

        StepVerifier.create(adapter.stream(completedRequest))
            .expectNextCount(1)
            .verifyComplete();
        verify(completed).close();

        AgentRunRequest cancelledRequest = RuntimeFixtures.runRequest("execution-cancelled");
        AgentScopeInvocation cancelled = mock(AgentScopeInvocation.class);
        when(invocationFactory.create(cancelledRequest)).thenReturn(cancelled);
        when(cancelled.stream(cancelledRequest)).thenReturn(Flux.never());

        StepVerifier.create(adapter.stream(cancelledRequest))
            .thenCancel()
            .verify();
        verify(cancelled).close();
        assertEquals(0, adapter.activeInvocationCount());
    }

    @Test
    void cancellationForUnknownExecutionDoesNothing() {
        AgentRunRequest request = RuntimeFixtures.runRequest();

        StepVerifier.create(adapter.cancel(request.executionKey(), null))
            .expectNext(new RuntimeCancellationResult(false, false))
            .verifyComplete();

        verify(invocationFactory, never()).create(any());
    }

    private void assertTrueMessageContains(String value, String expected) {
        org.junit.jupiter.api.Assertions.assertTrue(value.contains(expected));
    }
}
