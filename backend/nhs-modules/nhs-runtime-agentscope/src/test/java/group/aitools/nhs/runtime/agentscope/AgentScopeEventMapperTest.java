package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventStatus;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.runtime.spi.RuntimeSensitiveLevel;
import group.aitools.nhs.runtime.spi.RuntimeResumeDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class AgentScopeEventMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentScopeEventMapper mapper = new AgentScopeEventMapper(objectMapper);

    @Test
    void redactsSecretFieldsAtEveryPayloadDepth() throws Exception {
        CustomEvent source = new CustomEvent("credentials", Map.of(
            "apiKey", "sk-top-secret",
            "nested", Map.of("password", "password-value"),
            "items", List.of(Map.of("authorization", "Bearer hidden")),
            "safe", "visible"
        ));

        RuntimeEvent event = mapper.map(RuntimeFixtures.runRequest(), source);
        String json = objectMapper.writeValueAsString(event.payload());

        assertFalse(json.contains("top-secret"));
        assertFalse(json.contains("password-value"));
        assertFalse(json.contains("Bearer hidden"));
        assertTrue(json.contains("[REDACTED]"));
        assertTrue(json.contains("visible"));
    }

    @Test
    void replacesOversizedPayloadWithBoundedMetadata() throws Exception {
        CustomEvent source = new CustomEvent("large", Map.of("content", "中".repeat(30_000)));

        RuntimeEvent event = mapper.map(RuntimeFixtures.runRequest(), source);
        byte[] encoded = objectMapper.writeValueAsBytes(event.payload());

        assertTrue(encoded.length < 64 * 1024);
        assertEquals(true, event.payload().get("truncated"));
        assertEquals("CUSTOM", event.payload().get("sourceType"));
    }

    @Test
    void marksReasoningAsSensitiveWhileKeepingOwnerOnlyReasoningPayload() {
        String hiddenReasoning = "private chain of thought";

        RuntimeEvent event = mapper.map(
            RuntimeFixtures.runRequest(),
            new ThinkingBlockDeltaEvent("reply-1", "thinking-1", hiddenReasoning)
        );

        assertEquals(RuntimeEventType.THINKING_DELTA, event.type());
        assertEquals(RuntimeSensitiveLevel.SENSITIVE, event.sensitiveLevel());
        assertFalse(event.summary().contains(hiddenReasoning));
        assertEquals(hiddenReasoning, event.payload().get("delta"));
        assertEquals(Map.of(), event.queryProjection());
    }

    @Test
    void projectsAllowlistedModelIdentityUsageAndDuration() {
        RuntimeEvent started = mapper.map(
            RuntimeFixtures.runRequest(), new ModelCallStartEvent("reply-1")
        );
        RuntimeEvent finished = mapper.map(
            RuntimeFixtures.runRequest(),
            new ModelCallEndEvent("reply-1", new ChatUsage(11, 7, 3, 0.25))
        );

        assertEquals(RuntimeSensitiveLevel.INTERNAL, started.sensitiveLevel());
        assertEquals(Map.of(
            "agentName", "research-agent",
            "model", "test-model",
            "temperature", 0.1,
            "replyId", "reply-1"
        ), started.queryProjection());
        assertEquals(11, finished.queryProjection().get("promptTokens"));
        assertEquals(7, finished.queryProjection().get("completionTokens"));
        assertEquals(3, finished.queryProjection().get("cachedTokens"));
        assertEquals(18, finished.queryProjection().get("totalTokens"));
        assertEquals(250D, finished.queryProjection().get("durationMs"));
    }

    @Test
    void projectsFrozenRuntimeContextForResumeWhenInvocationCannotExposeIt() {
        AgentResumeRequest request = RuntimeFixtures.resumeRequest(RuntimeResumeDecision.APPROVE)
            .withRuntimeContext(RuntimeFixtures.runRequest());

        RuntimeEvent event = mapper.map(request, new ModelCallStartEvent("reply-1"));

        assertEquals(Map.of(
            "agentName", "research-agent",
            "model", "test-model",
            "temperature", 0.1,
            "replyId", "reply-1"
        ), event.queryProjection());
    }

    @Test
    void projectsOnlySafeToolFieldsAndScrubsSecretsEmbeddedInStrings() throws Exception {
        RuntimeEvent started = mapper.map(
            RuntimeFixtures.runRequest(),
            new ToolCallStartEvent("reply-1", "tool-1", "fetch_url")
        );
        RuntimeEvent input = mapper.map(
            RuntimeFixtures.runRequest(),
            new ToolCallDeltaEvent(
                "reply-1", "tool-1", "fetch_url",
                "{\"url\":\"https://example.test/data?token=hidden-token\","
                    + "\"authorization\":\"Bearer hidden-bearer\"}"
            )
        );
        RuntimeEvent output = mapper.map(
            RuntimeFixtures.runRequest(),
            new ToolResultTextDeltaEvent(
                "reply-1", "tool-1", "fetch_url",
                "result password=hidden-password sk-1234567890"
            )
        );
        RuntimeEvent finished = mapper.map(
            RuntimeFixtures.runRequest(),
            new ToolResultEndEvent(
                "reply-1", "tool-1", "fetch_url", ToolResultState.SUCCESS
            )
        );

        assertEquals(RuntimeSensitiveLevel.INTERNAL, started.sensitiveLevel());
        assertEquals("fetch_url", started.queryProjection().get("toolName"));
        assertEquals("tool-1", started.queryProjection().get("toolCallId"));
        String projectionJson = objectMapper.writeValueAsString(List.of(
            input.queryProjection(), output.queryProjection()
        ));
        assertFalse(projectionJson.contains("hidden-token"));
        assertFalse(projectionJson.contains("hidden-bearer"));
        assertFalse(projectionJson.contains("hidden-password"));
        assertFalse(projectionJson.contains("sk-1234567890"));
        assertTrue(projectionJson.contains("[REDACTED]"));
        assertEquals("success", finished.queryProjection().get("toolState"));
    }

    @Test
    void projectsBusinessConfirmationFromPlatformJsonTextResult() throws Exception {
        String result = objectMapper.writeValueAsString(Map.of(
            "ok", true,
            "data", Map.of(
                "status", "awaiting_user",
                "confirmation_id", "bc-42",
                "ui", Map.of(
                    "title", "确认变更",
                    "fields", List.of(Map.of(
                        "key", "version", "label", "版本", "value", "1.2.0",
                        "editable", true, "value_type", "string"
                    ))
                )
            )
        ));

        RuntimeEvent event = mapper.map(
            RuntimeFixtures.runRequest(),
            new ToolResultTextDeltaEvent(
                "reply-1", "tool-confirm", "request_user_confirmation", result
            )
        );

        assertEquals("awaiting_user", event.queryProjection().get("resultStatus"));
        assertEquals("bc-42", event.queryProjection().get("confirmationId"));
        Map<?, ?> confirmation = (Map<?, ?>) event.queryProjection().get("businessConfirmation");
        assertEquals("bc-42", confirmation.get("confirmationId"));
        Map<?, ?> ui = (Map<?, ?>) confirmation.get("ui");
        assertEquals("确认变更", ui.get("title"));
    }

    @Test
    void signsSuccessfulBuiltinResultsAndRejectsFailedResultsAsEvidence() throws Exception {
        String success = objectMapper.writeValueAsString(Map.of(
            "ok", true, "status", "success", "data", Map.of("rowCount", 2)
        ));
        RuntimeEvent verified = mapper.map(
            RuntimeFixtures.runRequest(),
            new ToolResultTextDeltaEvent("reply-1", "tool-sql", "execute_sql_query", success)
        );

        assertEquals("internal_data", verified.queryProjection().get("evidenceType"));
        assertEquals("verified", verified.queryProjection().get("evidenceStatus"));

        String failure = objectMapper.writeValueAsString(Map.of(
            "ok", false, "status", "query_error", "error", "数据源不可用"
        ));
        RuntimeEvent rejected = mapper.map(
            RuntimeFixtures.runRequest(),
            new ToolResultTextDeltaEvent("reply-1", "tool-sql-2", "execute_sql_query", failure)
        );

        assertEquals("failed", rejected.queryProjection().get("evidenceStatus"));
        assertFalse(rejected.queryProjection().containsKey("evidenceType"));
    }

    @Test
    void doesNotProjectArbitraryInternalCustomPayloads() {
        RuntimeEvent event = mapper.map(
            RuntimeFixtures.runRequest(),
            new CustomEvent("private", Map.of("safeLookingField", "must-not-project"))
        );

        assertEquals(RuntimeSensitiveLevel.INTERNAL, event.sensitiveLevel());
        assertEquals(Map.of(), event.queryProjection());
    }

    @Test
    void sanitizesSecretsInFailureMessages() throws Exception {
        RuntimeEvent event = mapper.failure(
            RuntimeFixtures.runRequest(),
            new IllegalStateException("authorization=Bearer-secret apiKey=sk-1234567890")
        );
        String json = objectMapper.writeValueAsString(event.payload());

        assertEquals(RuntimeEventType.FAILED, event.type());
        assertEquals(RuntimeEventStatus.FAILED, event.status());
        assertFalse(event.summary().contains("Bearer-secret"));
        assertFalse(event.summary().contains("sk-1234567890"));
        assertFalse(json.contains("Bearer-secret"));
        assertFalse(json.contains("sk-1234567890"));
        assertTrue(event.summary().getBytes(StandardCharsets.UTF_8).length <= 512);
    }
}
