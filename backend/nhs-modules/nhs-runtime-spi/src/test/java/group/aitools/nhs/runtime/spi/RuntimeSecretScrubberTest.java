package group.aitools.nhs.runtime.spi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class RuntimeSecretScrubberTest {

    @Test
    void masksAnUnterminatedQuotedSecretFromAStreamingDelta() {
        String scrubbed = RuntimeSecretScrubber.scrubText(
            "{\"api_key\":\"plain-sensitive-value"
        );

        assertFalse(scrubbed.contains("plain-sensitive-value"));
        assertTrue(scrubbed.contains("[REDACTED]"));
    }

    @Test
    void keepsTokenCountersAvailableForTraceMetrics() {
        assertTrue(RuntimeSecretScrubber.sanitizeValue("total_tokens", 42).equals(42));
    }

    @Test
    void preservesClosingQuoteWhenMaskingCompleteJsonValue() {
        assertEquals(
            "{\"authorization\":\"[REDACTED]\"}",
            RuntimeSecretScrubber.scrubText("{\"authorization\":\"Bearer hidden\"}")
        );
    }
}
