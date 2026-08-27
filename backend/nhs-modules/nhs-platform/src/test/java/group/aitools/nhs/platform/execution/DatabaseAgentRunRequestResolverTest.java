package group.aitools.nhs.platform.execution;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.runtime.spi.RuntimeResumeDecision;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentRunRuntimeMapper;
import group.aitools.nhs.platform.execution.persistence.row.AgentRunRuntimeRow;
import group.aitools.nhs.platform.execution.service.DatabaseAgentRunRequestResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class DatabaseAgentRunRequestResolverTest {

    private final AgentRunRuntimeMapper mapper = mock(AgentRunRuntimeMapper.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final DatabaseAgentRunRequestResolver resolver = new DatabaseAgentRunRequestResolver(
        mapper, jsonMapper
    );

    @Test
    void restoresFrozenRequestOnlyFromMatchingResumableRun() {
        AgentRunRequest frozen = runRequest();
        AgentRunRuntimeRow row = row("waiting_approval", jsonMapper.writeValueAsString(frozen));
        when(mapper.selectRuntimeSnapshot(401L, "trace-1")).thenReturn(row);

        AgentRunRequest restored = resolver.resolveForResume(resumeRequest());

        assertEquals(frozen, restored);
    }

    @Test
    void rejectsMissingCorruptAndOversizedSnapshots() {
        when(mapper.selectRuntimeSnapshot(401L, "trace-1"))
            .thenReturn(null, row("waiting_approval", "{bad"), row(
                "waiting_approval", "中".repeat(90_000)
            ));

        assertThrows(IllegalStateException.class, () -> resolver.resolveForResume(resumeRequest()));
        assertThrows(IllegalStateException.class, () -> resolver.resolveForResume(resumeRequest()));
        assertThrows(IllegalStateException.class, () -> resolver.resolveForResume(resumeRequest()));
    }

    @Test
    void rejectsNonResumableStateAndCrossRunSnapshot() {
        when(mapper.selectRuntimeSnapshot(401L, "trace-1"))
            .thenReturn(
                row("running", jsonMapper.writeValueAsString(runRequest())),
                row("waiting_approval", jsonMapper.writeValueAsString(runRequest(999L)))
            );

        assertThrows(IllegalStateException.class, () -> resolver.resolveForResume(resumeRequest()));
        assertThrows(SecurityException.class, () -> resolver.resolveForResume(resumeRequest()));
    }

    private AgentRunRuntimeRow row(String status, String snapshot) {
        AgentRunRuntimeRow row = new AgentRunRuntimeRow();
        row.setId(401L);
        row.setTaskId(301L);
        row.setTraceId("trace-1");
        row.setStatus(status);
        row.setRuntimeSnapshotJson(snapshot);
        return row;
    }

    private AgentRunRequest runRequest() {
        return runRequest(401L);
    }

    private AgentRunRequest runRequest(Long runId) {
        return new AgentRunRequest(
            new RuntimeExecutionKey("execution-1", "trace-1"),
            101L,
            201L,
            301L,
            runId,
            501L,
            601L,
            "research-agent",
            "session-1",
            "prepare report",
            "Follow the task scope.",
            new RuntimeModelConfig(
                "openai-compatible",
                "model",
                "https://model.example/v1",
                "env:MODEL_API_KEY",
                Map.of("temperature", 0.1)
            ),
            "run-401",
            12,
            Map.of("workspaceAccess", "read_write"),
            Map.of("source", "task")
        );
    }

    private AgentResumeRequest resumeRequest() {
        return new AgentResumeRequest(
            new RuntimeExecutionKey("execution-1", "trace-1"),
            101L,
            201L,
            301L,
            401L,
            501L,
            "session-1",
            "reply-1",
            RuntimeResumeDecision.APPROVE,
            Map.of("id", "tool-1", "name", "write_file", "input", Map.of()),
            Map.of("approvedBy", 101L)
        );
    }
}
