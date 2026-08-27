package group.aitools.nhs.sandbox.runner.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class BoundedOutputCollectorTest {

    @Test
    void drainsButNeverRetainsMoreThanTheSharedOutputBudget() {
        AtomicInteger remaining = new AtomicInteger(8);
        AtomicBoolean exceeded = new AtomicBoolean();
        BoundedOutputCollector collector = new BoundedOutputCollector(
            new ByteArrayInputStream("0123456789abcdef".getBytes(StandardCharsets.UTF_8)),
            remaining, exceeded
        );

        collector.run();

        assertEquals("01234567", collector.text());
        assertEquals(0, remaining.get());
        assertTrue(exceeded.get());
    }

    @Test
    void callbacksContainOnlyCompleteUtf8TextWithinTheOutputBudget() {
        AtomicInteger remaining = new AtomicInteger(4);
        AtomicBoolean exceeded = new AtomicBoolean();
        List<String> chunks = new ArrayList<>();
        BoundedOutputCollector collector = new BoundedOutputCollector(
            new ByteArrayInputStream("你好".getBytes(StandardCharsets.UTF_8)),
            remaining, exceeded, chunks::add
        );

        collector.run();

        assertEquals("你", collector.text());
        assertEquals(List.of("你"), chunks);
        assertEquals(1, remaining.get());
        assertTrue(exceeded.get());
    }
}
