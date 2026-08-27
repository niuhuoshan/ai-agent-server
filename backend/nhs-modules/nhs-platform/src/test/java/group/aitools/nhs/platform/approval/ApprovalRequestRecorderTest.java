package group.aitools.nhs.platform.approval;

import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventStatus;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeSensitiveLevel;
import group.aitools.nhs.platform.approval.domain.AgentApprovalRequest;
import group.aitools.nhs.platform.approval.mapper.ApprovalRequestMapper;
import group.aitools.nhs.platform.approval.service.ApprovalRequestRecorder;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ApprovalRequestRecorderTest {

    private ApprovalRequestMapper mapper;
    private ApprovalRequestRecorder recorder;

    @BeforeEach
    void setUp() {
        mapper = mock(ApprovalRequestMapper.class);
        PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        when(idGenerator.nextId()).thenReturn(900L);
        when(mapper.insertRequest(any())).thenReturn(1);
        recorder = new ApprovalRequestRecorder(
            mapper, idGenerator, JsonMapper.builder().build(), mock(NotificationApplicationService.class)
        );
    }

    @Test
    void persistsEveryPendingToolFromOneConfirmationWithoutRawSecrets() {
        Map<String, Object> nullableInput = new LinkedHashMap<>();
        nullableInput.put("destination", null);
        nullableInput.put("apiKey", "[REDACTED]");
        List<Map<String, Object>> actions = List.of(
            action("call-1", "send_report", nullableInput, "R3"),
            action("call-2", "update_ticket", Map.of("ticket", "T-9"), "R2")
        );

        AgentApprovalRequest result = recorder.record(
            101L, 10L, 500L, 501L, source("reply-7", actions), persisted()
        );

        assertEquals(900L, result.getId());
        assertEquals("R3", result.getRiskLevel());
        assertTrue(result.getActionSummary().contains("send_report"));
        assertTrue(result.getActionSummary().contains("update_ticket"));
        assertTrue(result.getPendingActionsJson().contains("call-1"));
        assertTrue(result.getPendingActionsJson().contains("call-2"));
        assertFalse(result.getPendingActionsJson().contains("raw-secret"));
        assertTrue(result.getPendingActionsJson().contains("[REDACTED]"));
    }

    @Test
    void rejectsMissingReplyIdBeforeWritingAnything() {
        RuntimeEvent malformed = source(null, List.of(action(
            "call-1", "send_report", Map.of(), "R2"
        )));

        assertThrows(
            IllegalStateException.class,
            () -> recorder.record(101L, 10L, 500L, 501L, malformed, persisted())
        );

        verify(mapper, never()).insertRequest(any());
    }

    @Test
    void rejectsMismatchedRunStepOrTraceIdentity() {
        RuntimeEvent source = source("reply-7", List.of(action(
            "call-1", "send_report", Map.of(), "R2"
        )));
        ExecutionEventView wrong = new ExecutionEventView(
            "event-1", "b".repeat(64), 20L, 999L, 501L, 4L,
            "approval_required", "pending", "approval", Map.of(), "sensitive",
            LocalDateTime.now()
        );

        assertThrows(
            SecurityException.class,
            () -> recorder.record(101L, 10L, 500L, 501L, source, wrong)
        );
    }

    @Test
    void rejectsOversizedServerSnapshot() {
        String large = "x".repeat(70 * 1024);
        RuntimeEvent source = source("reply-7", List.of(action(
            "call-1", "send_report", Map.of("content", large), "R2"
        )));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> recorder.record(101L, 10L, 500L, 501L, source, persisted())
        );

        assertTrue(exception.getMessage().contains("64KB"));
        verify(mapper, never()).insertRequest(any());
    }

    @Test
    void duplicateEventReturnsExistingDurableRequest() {
        AgentApprovalRequest existing = new AgentApprovalRequest();
        existing.setId(777L);
        when(mapper.selectByEventId("event-1")).thenReturn(existing);
        RuntimeEvent source = source("reply-7", List.of(action(
            "call-1", "send_report", Map.of(), "R2"
        )));

        AgentApprovalRequest result = recorder.record(
            101L, 10L, 500L, 501L, source, persisted()
        );

        assertEquals(777L, result.getId());
        verify(mapper, never()).insertRequest(any());
    }

    @Test
    void concurrentInsertConflictReplaysTheWinningRequest() {
        AgentApprovalRequest winner = new AgentApprovalRequest();
        winner.setId(778L);
        when(mapper.selectByEventId("event-1")).thenReturn(null, winner);
        when(mapper.insertRequest(any())).thenReturn(0);

        AgentApprovalRequest result = recorder.record(
            101L,
            10L,
            500L,
            501L,
            source("reply-7", List.of(action("call-1", "send_report", Map.of(), "R2"))),
            persisted()
        );

        assertEquals(778L, result.getId());
        ArgumentCaptor<AgentApprovalRequest> inserted = ArgumentCaptor.forClass(AgentApprovalRequest.class);
        verify(mapper).insertRequest(inserted.capture());
        assertEquals("reply-7", inserted.getValue().getReplyId());
    }

    private RuntimeEvent source(String replyId, List<Map<String, Object>> actions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (replyId != null) {
            payload.put("replyId", replyId);
        }
        payload.put("toolCalls", actions);
        return new RuntimeEvent(
            "source-event",
            new RuntimeExecutionKey("task-run-500", "a".repeat(64)),
            20L,
            500L,
            501L,
            RuntimeEventType.APPROVAL_REQUIRED,
            RuntimeEventStatus.PENDING,
            Instant.now(),
            "approval",
            payload,
            RuntimeSensitiveLevel.SENSITIVE
        );
    }

    private ExecutionEventView persisted() {
        return new ExecutionEventView(
            "event-1", "a".repeat(64), 20L, 500L, 501L, 4L,
            "approval_required", "pending", "approval", Map.of(), "sensitive",
            LocalDateTime.now()
        );
    }

    private Map<String, Object> action(
        String id,
        String name,
        Map<String, Object> input,
        String risk
    ) {
        return Map.of(
            "id", id,
            "name", name,
            "input", input,
            "metadata", Map.of("riskLevel", risk, "impactScope", "external-system")
        );
    }
}
