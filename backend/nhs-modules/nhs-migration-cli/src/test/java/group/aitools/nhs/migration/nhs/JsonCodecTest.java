package group.aitools.nhs.migration.nhs;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@Tag("dev")
class JsonCodecTest {

    private final JsonCodec codec = new JsonCodec();

    @Test
    void recursivelyRemovesCredentialMaterialFromArchivedRows() {
        Map<String, Object> source = Map.of(
            "id", 1,
            "api_key", "top-secret",
            "tool_input", Map.of(
                "query", "safe",
                "password", "nested-secret",
                "items", List.of(Map.of("access_token", "token", "name", "kept")),
                "driver_json", "{\"api_key\":\"driver-secret\",\"safe\":\"value\"}"
            )
        );

        Map<String, Object> sanitized = codec.sanitizeRow(source);
        String serialized = codec.write(sanitized);

        assertFalse(serialized.contains("top-secret"));
        assertFalse(serialized.contains("nested-secret"));
        assertFalse(serialized.contains("token"));
        assertFalse(serialized.contains("driver-secret"));
        assertEquals("safe", ((Map<?, ?>) sanitized.get("tool_input")).get("query"));
    }

    @Test
    void hashesAreStableAcrossMapIterationOrder() {
        String first = codec.sha256(Map.of("a", 1, "b", 2));
        String second = codec.sha256(Map.of("b", 2, "a", 1));
        String changed = codec.sha256(Map.of("a", 1, "b", 3));

        assertEquals(first, second);
        assertNotEquals(first, changed);
    }
}
