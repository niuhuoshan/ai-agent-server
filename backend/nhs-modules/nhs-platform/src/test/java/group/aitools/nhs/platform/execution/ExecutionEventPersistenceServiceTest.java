package group.aitools.nhs.platform.execution;

import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventStatus;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeSensitiveLevel;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.domain.AgentExecutionEvent;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentExecutionEventMapper;
import group.aitools.nhs.platform.execution.service.ExecutionEventPersistenceService;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ExecutionEventPersistenceServiceTest {

    private PlatformIdGenerator idGenerator;
    private AgentExecutionEventMapper eventMapper;
    private ExecutionEventPersistenceService service;

    @BeforeEach
    void setUp() {
        idGenerator = mock(PlatformIdGenerator.class);
        eventMapper = mock(AgentExecutionEventMapper.class);
        service = new ExecutionEventPersistenceService(
            idGenerator, eventMapper, JsonMapper.builder().build()
        );
    }

    @Test
    void persistsDeterministicEventWithDatabaseCursor() {
        when(eventMapper.selectByEventId(anyString())).thenReturn(null);
        when(eventMapper.nextCursor()).thenReturn(77L);
        when(idGenerator.nextId()).thenReturn(88L);
        when(eventMapper.insertEvent(any())).thenReturn(1);

        ExecutionEventView view = service.append(runtimeEvent(
            "source-1", RuntimeSensitiveLevel.PUBLIC, Map.of("delta", "hello")
        ));

        ArgumentCaptor<AgentExecutionEvent> captor = ArgumentCaptor.forClass(
            AgentExecutionEvent.class
        );
        verify(eventMapper).insertEvent(captor.capture());
        AgentExecutionEvent inserted = captor.getValue();
        assertEquals(64, inserted.getEventId().length());
        assertEquals(77L, inserted.getCursor());
        assertEquals("text_delta", inserted.getEventType());
        assertEquals("success", inserted.getEventStatus());
        assertEquals(Map.of("delta", "hello"), view.payload());
    }

    @Test
    void returnsExistingEventWithoutAllocatingAnotherCursor() {
        AgentExecutionEvent existing = persistedEvent("existing", 9L, "public", "{\"ok\":true}");
        when(eventMapper.selectByEventId(anyString())).thenReturn(existing);

        ExecutionEventView result = service.append(runtimeEvent(
            "source-1", RuntimeSensitiveLevel.PUBLIC, Map.of("ok", true)
        ));

        assertEquals(9L, result.cursor());
        verify(eventMapper, never()).nextCursor();
        verify(eventMapper, never()).insertEvent(any());
    }

    @Test
    void secretPayloadIsNeverWrittenAndOversizedPayloadIsRejected() {
        when(eventMapper.selectByEventId(anyString())).thenReturn(null);
        when(eventMapper.nextCursor()).thenReturn(10L, 11L);
        when(idGenerator.nextId()).thenReturn(20L);
        when(eventMapper.insertEvent(any())).thenReturn(1);

        service.append(runtimeEvent(
            "secret", RuntimeEventType.MODEL_CALL_FINISHED, RuntimeSensitiveLevel.SECRET,
            Map.of("apiKey", "sk-should-not-persist"),
            Map.of("model", "must-not-project", "totalTokens", 9)
        ));

        ArgumentCaptor<AgentExecutionEvent> captor = ArgumentCaptor.forClass(
            AgentExecutionEvent.class
        );
        verify(eventMapper).insertEvent(captor.capture());
        assertFalse(captor.getValue().getPayloadJson().contains("should-not-persist"));
        assertEquals("{}", captor.getValue().getQueryProjectionJson());

        assertThrows(IllegalArgumentException.class, () -> service.append(runtimeEvent(
            "large",
            RuntimeSensitiveLevel.PUBLIC,
            Map.of("content", "中".repeat(30_000))
        )));
    }

    @Test
    void persistsSafeProjectionWithoutExposingInternalToolPayload() {
        when(eventMapper.selectByEventId(anyString())).thenReturn(null);
        when(eventMapper.nextCursor()).thenReturn(12L);
        when(idGenerator.nextId()).thenReturn(22L);
        when(eventMapper.insertEvent(any())).thenReturn(1);
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("replyId", "reply-1");
        projection.put("toolCallId", "tool-1");
        projection.put("toolName", "execute_sql_query");
        projection.put("inputDelta", "{\"authorization\":\"Bearer hidden\"}");
        projection.put("arbitraryInternalField", "must-not-project");
        Map<String, Object> expectedProjection = Map.of(
            "replyId", "reply-1",
            "toolCallId", "tool-1",
            "toolName", "execute_sql_query",
            "inputDelta", "{\"authorization\":\"[REDACTED]\"}"
        );

        ExecutionEventView view = service.append(runtimeEvent(
            "tool-delta", RuntimeEventType.TOOL_CALL_DELTA, RuntimeSensitiveLevel.INTERNAL,
            Map.of("privateRuntimePayload", "must-not-be-visible"), projection
        ));

        ArgumentCaptor<AgentExecutionEvent> captor = ArgumentCaptor.forClass(
            AgentExecutionEvent.class
        );
        verify(eventMapper).insertEvent(captor.capture());
        assertEquals(expectedProjection, view.projection());
        assertEquals(Map.of("redacted", true), view.payload());
        assertEquals(expectedProjection, JsonMapper.builder().build().readValue(
            captor.getValue().getQueryProjectionJson(), Map.class
        ));
    }

    @Test
    void discardsProjectionForSensitiveAndNonProjectableEvents() {
        when(eventMapper.selectByEventId(anyString())).thenReturn(null);
        when(eventMapper.nextCursor()).thenReturn(13L, 14L);
        when(idGenerator.nextId()).thenReturn(23L, 24L);
        when(eventMapper.insertEvent(any())).thenReturn(1);

        ExecutionEventView sensitive = service.append(runtimeEvent(
            "sensitive", RuntimeEventType.MODEL_CALL_STARTED, RuntimeSensitiveLevel.SENSITIVE,
            Map.of("redacted", true), Map.of("model", "must-not-project")
        ));
        ExecutionEventView custom = service.append(runtimeEvent(
            "custom", RuntimeEventType.CUSTOM, RuntimeSensitiveLevel.INTERNAL,
            Map.of("internal", "payload"), Map.of("arbitrary", "must-not-project")
        ));

        ArgumentCaptor<AgentExecutionEvent> captor = ArgumentCaptor.forClass(
            AgentExecutionEvent.class
        );
        verify(eventMapper, org.mockito.Mockito.times(2)).insertEvent(captor.capture());
        assertEquals(List.of("{}", "{}"), captor.getAllValues().stream()
            .map(AgentExecutionEvent::getQueryProjectionJson).toList());
        assertEquals(Map.of(), sensitive.projection());
        assertEquals(Map.of(), custom.projection());
        assertEquals(Map.of("redacted", true), custom.payload());
    }

    @Test
    void retractionScrubsPreviouslyPersistedTextDeltas() {
        when(eventMapper.selectByEventId(anyString())).thenReturn(null);
        when(eventMapper.nextCursor()).thenReturn(15L);
        when(idGenerator.nextId()).thenReturn(25L);
        when(eventMapper.insertEvent(any())).thenReturn(1);

        service.append(runtimeEvent(
            "retraction", RuntimeEventType.CUSTOM, RuntimeSensitiveLevel.INTERNAL,
            Map.of("retraction", true, "code", "output_retracted"), Map.of()
        ));

        verify(eventMapper).redactTextDeltasForRetraction("trace-1");
    }

    private RuntimeEvent runtimeEvent(
        String sourceEventId,
        RuntimeSensitiveLevel level,
        Map<String, Object> payload
    ) {
        return runtimeEvent(
            sourceEventId, RuntimeEventType.TEXT_DELTA, level, payload, Map.of()
        );
    }

    private RuntimeEvent runtimeEvent(
        String sourceEventId,
        RuntimeEventType type,
        RuntimeSensitiveLevel level,
        Map<String, Object> payload,
        Map<String, Object> queryProjection
    ) {
        return new RuntimeEvent(
            sourceEventId,
            new RuntimeExecutionKey("execution-1", "trace-1"),
            201L,
            401L,
            501L,
            type,
            RuntimeEventStatus.SUCCESS,
            Instant.parse("2026-08-14T04:00:00Z"),
            "delta",
            payload,
            level,
            queryProjection
        );
    }

    private AgentExecutionEvent persistedEvent(
        String eventId,
        Long cursor,
        String level,
        String payload
    ) {
        AgentExecutionEvent event = new AgentExecutionEvent();
        event.setEventId(eventId);
        event.setTraceId("trace-1");
        event.setConversationId(201L);
        event.setRunId(401L);
        event.setStepId(501L);
        event.setCursor(cursor);
        event.setEventType("text_delta");
        event.setEventStatus("success");
        event.setSummary("delta");
        event.setPayloadJson(payload);
        event.setQueryProjectionJson("{}");
        event.setSensitiveLevel(level);
        event.setOccurredAt(java.time.LocalDateTime.now());
        return event;
    }
}
