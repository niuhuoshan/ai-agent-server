package group.aitools.nhs.platform.sandbox;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.runtime.spi.RuntimeResumeMode;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentRunRuntimeMapper;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.execution.persistence.row.AgentRunRuntimeRow;
import group.aitools.nhs.platform.sandbox.mapper.SandboxRunnerMapper;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobRow;
import group.aitools.nhs.platform.sandbox.service.SandboxCompletionResumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class SandboxCompletionResumeServiceTest {

    private SandboxRunnerMapper sandboxMapper;
    private AgentRunRuntimeMapper runtimeMapper;
    private TaskRunCommandMapper runMapper;
    private JsonMapper jsonMapper;
    private SandboxCompletionResumeService service;

    @BeforeEach
    void setUp() {
        sandboxMapper = mock(SandboxRunnerMapper.class);
        runtimeMapper = mock(AgentRunRuntimeMapper.class);
        runMapper = mock(TaskRunCommandMapper.class);
        jsonMapper = JsonMapper.builder().findAndAddModules().build();
        service = new SandboxCompletionResumeService(
            sandboxMapper, runtimeMapper, runMapper, jsonMapper
        );
    }

    @Test
    void waitsForEveryJobInExternalBatchBeforeResume() {
        SandboxJobRow completed = job(1L, "succeeded", "call-1");
        SandboxJobRow pending = job(2L, "running", "call-2");
        when(sandboxMapper.selectJob(1L)).thenReturn(completed);
        when(sandboxMapper.selectExternalBatch(11L, "reply-1"))
            .thenReturn(List.of(completed, pending));

        assertNull(service.prepare(1L, "worker-a"));
    }

    @Test
    void atomicallyClaimsRunAndBuildsSuccessAndFailureToolResults() {
        SandboxJobRow success = job(1L, "succeeded", "call-1");
        success.setExitCode(0);
        success.setStdoutText("ok");
        SandboxJobRow failure = job(2L, "failed", "call-2");
        failure.setFailureCode("NON_ZERO_EXIT");
        failure.setFailureMessage("failed");
        when(sandboxMapper.selectJob(1L)).thenReturn(success);
        when(sandboxMapper.selectExternalBatch(11L, "reply-1"))
            .thenReturn(List.of(success, failure));
        AgentRunRequest frozen = frozen();
        AgentRunRuntimeRow runtime = new AgentRunRuntimeRow();
        runtime.setId(11L);
        runtime.setTaskId(10L);
        runtime.setTraceId("trace-10");
        runtime.setStatus("waiting_input");
        runtime.setRuntimeSnapshotJson(jsonMapper.writeValueAsString(frozen));
        when(runtimeMapper.selectRuntimeSnapshotByRunId(11L)).thenReturn(runtime);
        when(runMapper.claimResumedRun(10L, 11L, "worker-a")).thenReturn(1);
        when(runMapper.startStep(11L, 12L)).thenReturn(1);
        when(sandboxMapper.markExternalBatchResumeDispatched(
            org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.eq("reply-1"),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(2);

        var request = service.prepare(1L, "worker-a");

        assertEquals(RuntimeResumeMode.EXTERNAL_EXECUTION, request.mode());
        assertEquals(2, request.pendingActions().size());
        assertEquals(Boolean.TRUE, request.pendingActions().get(0).get("succeeded"));
        assertEquals(Boolean.FALSE, request.pendingActions().get(1).get("succeeded"));
        assertTrue(request.pendingActions().get(0).get("result") instanceof Map<?, ?>);
        verify(runMapper).claimResumedRun(10L, 11L, "worker-a");
        verify(runMapper).startStep(11L, 12L);
    }

    private SandboxJobRow job(Long id, String status, String callId) {
        SandboxJobRow row = new SandboxJobRow();
        row.setId(id);
        row.setTaskId(10L);
        row.setRunId(11L);
        row.setStepId(12L);
        row.setToolId(500L);
        row.setExternalReplyId("reply-1");
        row.setToolCallId(callId);
        row.setToolName("platform_tool_500");
        row.setStatus(status);
        row.setOutputManifestJson("[]");
        row.setResourceUsageJson("{}");
        return row;
    }

    private AgentRunRequest frozen() {
        return new AgentRunRequest(
            new RuntimeExecutionKey("run-10", "trace-10"),
            9L, null, 10L, 11L, 12L, 100L, "agent", "session", "input", "system",
            new RuntimeModelConfig("openai", "model", null, "env:MODEL_KEY", Map.of()),
            "run-11", 10, Map.of(), Map.of()
        );
    }
}
