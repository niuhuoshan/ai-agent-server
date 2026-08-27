package group.aitools.nhs.platform.execution;

import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.domain.AgentConversationTurn;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.conversation.mapper.ConversationTurnMapper;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.execution.domain.AgentExecutionEvent;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentExecutionEventMapper;
import group.aitools.nhs.platform.execution.service.ExecutionEventQueryService;
import group.aitools.nhs.platform.execution.service.ExecutionTraceAggregationService;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ExecutionEventQueryServiceTest {

    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private AgentConversationMapper conversationMapper;
    private ConversationTurnMapper conversationTurnMapper;
    private TaskQueryService taskQueryService;
    private AgentExecutionEventMapper eventMapper;
    private JsonMapper jsonMapper;
    private ExecutionEventQueryService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        conversationMapper = mock(AgentConversationMapper.class);
        conversationTurnMapper = mock(ConversationTurnMapper.class);
        taskQueryService = mock(TaskQueryService.class);
        eventMapper = mock(AgentExecutionEventMapper.class);
        jsonMapper = JsonMapper.builder().build();
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));
        service = new ExecutionEventQueryService(
            principalProvider,
            authorizationEnforcer,
            conversationMapper,
            conversationTurnMapper,
            taskQueryService,
            eventMapper,
            jsonMapper,
            new ExecutionTraceAggregationService(jsonMapper)
        );
    }

    @Test
    void crossUserConversationIsHiddenBeforeEventQuery() {
        when(conversationMapper.selectOwnedConversation(201L, 101L)).thenReturn(null);

        assertThrows(
            ServiceException.class,
            () -> service.listConversation(201L, 0, 100)
        );

        verify(eventMapper, never()).selectConversationEvents(any(), anyLong(), anyInt());
    }

    @Test
    void taskAndRunMustMatchBeforeEventsCanBeRead() {
        when(eventMapper.selectTaskIdForRun(401L)).thenReturn(999L);

        assertThrows(ServiceException.class, () -> service.listTaskRun(301L, 401L, 0, 100));

        verify(taskQueryService).get(301L);
        verify(eventMapper, never()).selectTaskRunEvents(
            any(), any(), anyLong(), anyInt()
        );
    }

    @Test
    void exposesPublicPayloadAndRedactsInternalPayload() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(201L);
        conversation.setUserId(101L);
        when(conversationMapper.selectOwnedConversation(201L, 101L)).thenReturn(conversation);
        when(eventMapper.selectConversationEvents(201L, 0, 100)).thenReturn(List.of(
            event(1L, "public", "{\"delta\":\"hello\"}"),
            event(2L, "internal", "{\"toolInput\":\"private\"}")
        ));

        List<ExecutionEventView> events = service.listConversation(201L, 0, 100);

        assertEquals(Map.of("delta", "hello"), events.get(0).payload());
        assertEquals(Map.of("redacted", true), events.get(1).payload());
    }

    @Test
    void masksSensitiveDeltaFragmentsAtEventApiBoundary() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(201L);
        conversation.setUserId(101L);
        when(conversationMapper.selectOwnedConversation(201L, 101L)).thenReturn(conversation);
        when(eventMapper.selectConversationEvents(201L, 0, 100)).thenReturn(List.of(
            event(1L, "tool_call_delta", "success", "internal", Map.of(
                "replyId", "reply-1", "toolCallId", "tool-1", "inputDelta", "{\"api_"
            ), LocalDateTime.now()),
            event(2L, "tool_call_delta", "success", "internal", Map.of(
                "replyId", "reply-1", "toolCallId", "tool-1", "inputDelta",
                "key\":\"fragment-secret\"}"
            ), LocalDateTime.now())
        ));

        List<ExecutionEventView> events = service.listConversation(201L, 0, 100);

        assertEquals("[REDACTED]", events.get(0).projection().get("inputDelta"));
        assertEquals("[REDACTED]", events.get(1).projection().get("inputDelta"));
        org.junit.jupiter.api.Assertions.assertFalse(events.toString().contains("fragment-secret"));
    }

    @Test
    void masksToolOutputDataAtEventApiBoundary() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(201L);
        conversation.setUserId(101L);
        when(conversationMapper.selectOwnedConversation(201L, 101L)).thenReturn(conversation);
        when(eventMapper.selectConversationEvents(201L, 0, 100)).thenReturn(List.of(
            event(1L, "tool_result_delta", "success", "internal", Map.of(
                "toolCallId", "tool-1", "outputData", Map.of(
                    "authorization", "Bearer should-not-leak", "rows", List.of(1, 2)
                )
            ), LocalDateTime.now())
        ));

        List<ExecutionEventView> events = service.listConversation(201L, 0, 100);

        assertEquals("[REDACTED]", events.getFirst().projection().get("outputData"));
        org.junit.jupiter.api.Assertions.assertFalse(events.toString().contains("should-not-leak"));
    }

    @Test
    void incompleteModelStepIsReportedAsPending() {
        stubOwnedTrace();
        when(eventMapper.selectConversationTraceEvents("trace-1", 201L, Integer.MAX_VALUE))
            .thenReturn(List.of(event(
                1L, "model_call_started", "success", "internal",
                Map.of("replyId", "reply-pending", "model", "test-model"),
                LocalDateTime.now()
            )));

        ExecutionEventView model = service.traceConversation("trace-1").events().getFirst();

        assertEquals("pending", model.eventStatus());
    }

    @Test
    void traceLookupUsesOwnerBoundTurnBeforeReadingEvents() {
        AgentConversationTurn turn = new AgentConversationTurn();
        turn.setId(301L);
        turn.setConversationId(201L);
        turn.setUserId(101L);
        turn.setTraceId("trace-1");
        ConversationMessageRow message = new ConversationMessageRow();
        message.setId(401L);
        message.setConversationId(201L);
        message.setTraceId("trace-1");
        message.setRole("assistant");
        message.setContent("done");
        when(conversationTurnMapper.selectOwnedTurnByTrace("trace-1", 101L)).thenReturn(turn);
        when(conversationTurnMapper.selectTraceMessages(201L, "trace-1")).thenReturn(List.of(message));
        when(eventMapper.selectConversationTraceEvents(
            "trace-1", 201L, Integer.MAX_VALUE
        )).thenReturn(List.of(
            event(1L, "public", "{\"delta\":\"hello\"}"),
            event(2L, "internal", "{\"toolInput\":\"private\"}")
        ));

        var trace = service.traceConversation("trace-1");

        assertEquals(301L, trace.turn().getId());
        assertEquals("done", trace.messages().getFirst().getContent());
        assertEquals(Map.of("redacted", true), trace.events().get(1).payload());
        verify(authorizationEnforcer).requireAllowed(any(), org.mockito.ArgumentMatchers.argThat(
            context -> "conversation".equals(context.resourceType())
                && Long.valueOf(201L).equals(context.resourceId())
        ));
    }

    @Test
    void aggregatesMoreThanOneThousandRawDeltasIntoSemanticModelAndToolSteps() {
        stubOwnedTrace();
        LocalDateTime startedAt = LocalDateTime.of(2026, 8, 14, 4, 0);
        List<AgentExecutionEvent> rawEvents = new ArrayList<>();
        rawEvents.add(event(
            1L, "model_call_started", "success", "internal", Map.of(
                "replyId", "reply-1", "agentName", "research-agent",
                "model", "test-model", "temperature", 0.1
            ), startedAt
        ));
        rawEvents.add(event(
            2L, "model_call_finished", "success", "internal", Map.of(
                "replyId", "reply-1", "agentName", "research-agent",
                "model", "test-model", "temperature", 0.1,
                "promptTokens", 11, "completionTokens", 7,
                "cachedTokens", 3, "totalTokens", 18, "durationMs", 250D
            ), startedAt.plusNanos(250_000_000)
        ));
        Map<String, Object> toolContext = Map.of(
            "replyId", "reply-1", "toolCallId", "tool-1",
            "toolName", "execute_sql_query", "agentName", "research-agent",
            "model", "test-model", "temperature", 0.1
        );
        rawEvents.add(event(
            3L, "tool_call_started", "success", "internal",
            toolContext, startedAt.plusSeconds(1)
        ));
        Map<String, Object> inputDelta = new java.util.LinkedHashMap<>(toolContext);
        inputDelta.put("inputDelta", "{\"api_");
        rawEvents.add(event(
            4L, "tool_call_delta", "success", "internal",
            inputDelta, startedAt.plusSeconds(1)
        ));
        for (int index = 0; index < 1_001; index++) {
            Map<String, Object> delta = new java.util.LinkedHashMap<>();
            delta.put("replyId", "reply-1");
            delta.put("toolCallId", "tool-1");
            if (index == 0) {
                delta.put("inputDelta", "key\":\"supersecret\",\"dataset\":\"sales\"}");
            }
            rawEvents.add(event(
                5L + index, "tool_call_delta", "success", "internal",
                delta,
                startedAt.plusSeconds(1)
            ));
        }
        Map<String, Object> resultContext = new java.util.LinkedHashMap<>(toolContext);
        resultContext.put("replyId", "reply-acting-1");
        rawEvents.add(event(
            1_006L, "tool_result_started", "success", "internal",
            resultContext, startedAt.plusSeconds(2)
        ));
        Map<String, Object> outputDelta = new java.util.LinkedHashMap<>(resultContext);
        outputDelta.put("outputDelta", "{\"author");
        rawEvents.add(event(
            1_007L, "tool_result_delta", "success", "internal",
            outputDelta, startedAt.plusNanos(2_050_000_000)
        ));
        Map<String, Object> outputDeltaEnd = new java.util.LinkedHashMap<>(resultContext);
        outputDeltaEnd.put(
            "outputDelta", "ization\":\"Bearer split-secret\",\"rows\":3}"
        );
        rawEvents.add(event(
            1_008L, "tool_result_delta", "success", "internal",
            outputDeltaEnd, startedAt.plusNanos(2_075_000_000)
        ));
        Map<String, Object> toolFinished = new java.util.LinkedHashMap<>(resultContext);
        toolFinished.put("toolState", "success");
        rawEvents.add(event(
            1_009L, "tool_result_finished", "success", "internal",
            toolFinished, startedAt.plusNanos(2_125_000_000)
        ));
        when(eventMapper.selectConversationTraceEvents(
            "trace-1", 201L, Integer.MAX_VALUE
        )).thenReturn(rawEvents);

        var trace = service.traceConversation("trace-1");

        assertEquals(2, trace.events().size());
        ExecutionEventView model = trace.events().get(0);
        assertEquals("model_call", model.eventType());
        assertEquals("research-agent", model.projection().get("agentName"));
        assertEquals("test-model", model.projection().get("model"));
        assertEquals(0.1, model.projection().get("temperature"));
        assertEquals(11, model.projection().get("promptTokens"));
        assertEquals(7, model.projection().get("completionTokens"));
        assertEquals(18, model.projection().get("totalTokens"));
        assertEquals(250D, model.projection().get("executionTimeMs"));
        ExecutionEventView tool = trace.events().get(1);
        assertEquals("tool_call", tool.eventType());
        assertEquals("execute_sql_query", tool.projection().get("toolName"));
        assertEquals(Map.of(
            "api_key", "[REDACTED]", "dataset", "sales"
        ), tool.projection().get("toolInput"));
        assertEquals(Map.of(
            "authorization", "[REDACTED]", "rows", 3
        ), tool.projection().get("toolOutput"));
        assertEquals(125D, tool.projection().get("executionTimeMs"));
        assertEquals("tool-1", tool.projection().get("spanId"));
        assertEquals("reply-1", tool.projection().get("parentSpanId"));
        assertEquals(Map.of("redacted", true), tool.payload());
    }

    @Test
    void rejectsMoreThanOneThousandSemanticStepsAfterAggregation() {
        stubOwnedTrace();
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 14, 4, 0);
        List<AgentExecutionEvent> rawEvents = new ArrayList<>();
        for (int index = 0; index < 1_001; index++) {
            rawEvents.add(event(
                (long) index + 1, "model_call_started", "success", "internal",
                Map.of("replyId", "reply-" + index, "model", "test-model"), occurredAt
            ));
        }
        when(eventMapper.selectConversationTraceEvents(
            "trace-1", 201L, Integer.MAX_VALUE
        )).thenReturn(rawEvents);

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.traceConversation("trace-1")
        );

        assertEquals(413, exception.getCode());
    }

    @Test
    void boundsAccumulatedToolDataDeltasWithinOneSemanticStep() {
        stubOwnedTrace();
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 14, 4, 0);
        String chunk = "x".repeat(40_000);
        List<AgentExecutionEvent> rawEvents = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            rawEvents.add(event(
                (long) index + 1, "tool_result_delta", "success", "internal",
                Map.of(
                    "replyId", "reply-acting-1", "toolCallId", "tool-1",
                    "toolName", "fetch_data", "outputData", chunk
                ), occurredAt.plusNanos(index * 1_000_000L)
            ));
        }
        when(eventMapper.selectConversationTraceEvents(
            "trace-1", 201L, Integer.MAX_VALUE
        )).thenReturn(rawEvents);

        ExecutionEventView tool = service.traceConversation("trace-1").events().getFirst();

        assertEquals("tool_call", tool.eventType());
        assertEquals(chunk, tool.projection().get("toolOutput"));
        assertEquals(true, tool.projection().get("toolOutputTruncated"));
    }

    @Test
    void unknownOrCrossUserTraceIsHiddenBeforeEventLookup() {
        when(conversationTurnMapper.selectOwnedTurnByTrace("trace-other", 101L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.traceConversation("trace-other"));

        verify(eventMapper, never()).selectConversationTraceEvents(any(), any(), anyInt());
    }

    private AgentExecutionEvent event(Long cursor, String level, String payload) {
        AgentExecutionEvent event = event(
            cursor, "text_delta", "success", level, Map.of(), LocalDateTime.now()
        );
        event.setPayloadJson(payload);
        return event;
    }

    private AgentExecutionEvent event(
        Long cursor,
        String eventType,
        String eventStatus,
        String level,
        Map<String, Object> projection,
        LocalDateTime occurredAt
    ) {
        AgentExecutionEvent event = new AgentExecutionEvent();
        event.setEventId("event-" + cursor);
        event.setTraceId("trace-1");
        event.setConversationId(201L);
        event.setCursor(cursor);
        event.setEventType(eventType);
        event.setEventStatus(eventStatus);
        event.setSummary("summary");
        event.setPayloadJson("{}");
        event.setQueryProjectionJson(jsonMapper.writeValueAsString(projection));
        event.setSensitiveLevel(level);
        event.setOccurredAt(occurredAt);
        return event;
    }

    private void stubOwnedTrace() {
        AgentConversationTurn turn = new AgentConversationTurn();
        turn.setId(301L);
        turn.setConversationId(201L);
        turn.setUserId(101L);
        turn.setTraceId("trace-1");
        when(conversationTurnMapper.selectOwnedTurnByTrace("trace-1", 101L)).thenReturn(turn);
        when(conversationTurnMapper.selectTraceMessages(201L, "trace-1")).thenReturn(List.of());
    }
}
