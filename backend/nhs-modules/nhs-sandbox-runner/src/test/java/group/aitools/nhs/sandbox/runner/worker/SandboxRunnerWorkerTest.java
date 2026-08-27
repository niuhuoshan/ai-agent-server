package group.aitools.nhs.sandbox.runner.worker;

import group.aitools.nhs.sandbox.runner.client.SandboxPlatformClient;
import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.ClaimedJob;
import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.OutputChunk;
import group.aitools.nhs.sandbox.runner.config.SandboxRunnerProperties;
import group.aitools.nhs.sandbox.runner.execution.ContainerExecutor;
import group.aitools.nhs.sandbox.runner.execution.ContainerExecutor.ExecutionResult;
import group.aitools.nhs.sandbox.runner.execution.ContainerExecutor.OutputChunkConsumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class SandboxRunnerWorkerTest {

    @Test
    void coordinatorUsesAPlatformThreadThatKeepsTheProcessAlive() throws Exception {
        SandboxRunnerProperties properties = new SandboxRunnerProperties();
        properties.setMaxConcurrency(1);
        properties.setPollIntervalMs(100);
        properties.setHeartbeatIntervalSeconds(5);
        SandboxPlatformClient client = mock(SandboxPlatformClient.class);
        ContainerExecutor executor = mock(ContainerExecutor.class);
        CountDownLatch registered = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertFalse(Thread.currentThread().isVirtual());
            assertFalse(Thread.currentThread().isDaemon());
            registered.countDown();
            return null;
        }).when(client).ensureRegistered();
        when(executor.healthy()).thenReturn(true);
        when(client.claim()).thenReturn(null);

        SandboxRunnerWorker worker = new SandboxRunnerWorker(properties, client, executor);
        try {
            worker.run(mock(ApplicationArguments.class));
            assertTrue(registered.await(5, TimeUnit.SECONDS));
        } finally {
            worker.stop();
        }
    }

    @Test
    void streamsGloballyOrderedOutputAndInvalidatesLeaseWhenPlatformRejectsAChunk() throws Exception {
        SandboxRunnerProperties properties = new SandboxRunnerProperties();
        properties.setMaxConcurrency(1);
        properties.setPollIntervalMs(25);
        properties.setHeartbeatIntervalSeconds(5);
        SandboxPlatformClient client = mock(SandboxPlatformClient.class);
        ContainerExecutor executor = mock(ContainerExecutor.class);
        ClaimedJob job = chatJob();
        List<OutputChunk> outputs = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean claimed = new AtomicBoolean();
        when(executor.healthy()).thenReturn(true);
        when(client.claim()).thenAnswer(
            invocation -> claimed.compareAndSet(false, true) ? job : null
        );
        doAnswer(invocation -> {
            OutputChunk output = invocation.getArgument(1);
            outputs.add(output);
            if (output.sequenceNo() == 2L) {
                throw new IllegalStateException("stale job");
            }
            return null;
        }).when(client).appendOutput(eq(job), any(OutputChunk.class));
        when(executor.execute(
            eq(job), any(BooleanSupplier.class), any(OutputChunkConsumer.class)
        )).thenAnswer(invocation -> {
            BooleanSupplier leaseValid = invocation.getArgument(1);
            OutputChunkConsumer consumer = invocation.getArgument(2);
            assertTrue(consumer.accept("stdout", "first"));
            assertFalse(consumer.accept("stderr", "second"));
            assertFalse(leaseValid.getAsBoolean());
            return new ExecutionResult(
                false, -1, "first", "second", "LEASE_LOST", "cancelled", Map.of()
            );
        });
        doAnswer(invocation -> {
            completed.countDown();
            return null;
        }).when(client).complete(eq(job), any());

        SandboxRunnerWorker worker = new SandboxRunnerWorker(properties, client, executor);
        try {
            worker.run(mock(ApplicationArguments.class));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
        } finally {
            worker.stop();
        }

        assertEquals(List.of(
            new OutputChunk(1L, "stdout", "first"),
            new OutputChunk(2L, "stderr", "second")
        ), outputs);
    }

    private ClaimedJob chatJob() {
        return new ClaimedJob(
            10L, null, null, null, null, "a".repeat(64), "token",
            "python-3.11", null, ".", "read_write", "none", List.of(),
            30, 128, 500, 32, 4096, null, 1,
            "chat_code", 20L, 30L, "python", "print('safe')"
        );
    }
}
