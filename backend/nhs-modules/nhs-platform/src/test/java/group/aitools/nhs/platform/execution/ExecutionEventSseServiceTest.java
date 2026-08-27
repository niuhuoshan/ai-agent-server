package group.aitools.nhs.platform.execution;

import group.aitools.nhs.platform.execution.service.ExecutionEventSseService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class ExecutionEventSseServiceTest {

    @Test
    void databasePollingStartsStrictlyAfterRequestedCursor() throws Exception {
        ExecutionEventSseService service = new ExecutionEventSseService();
        CountDownLatch called = new CountDownLatch(1);
        AtomicLong observedCursor = new AtomicLong(-1);

        service.stream((afterCursor, limit) -> {
            observedCursor.set(afterCursor);
            called.countDown();
            throw new ExpectedStop();
        }, 42L);

        assertTrue(called.await(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(42L, observedCursor.get());
    }

    private static final class ExpectedStop extends RuntimeException {
    }
}
