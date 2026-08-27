package group.aitools.nhs.platform.workflow;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.execution.service.TaskRunExecutionCoordinator;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.platform.workflow.mapper.WorkflowRunMapper;
import group.aitools.nhs.platform.workflow.persistence.row.WorkflowRunStepRow;
import group.aitools.nhs.platform.workflow.service.WorkflowRunCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class WorkflowRunCoordinatorTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private WorkflowRunMapper workflowMapper;
    private TaskRunCommandMapper runMapper;
    private TaskRunExecutionCoordinator executionCoordinator;
    private WorkflowRunCoordinator coordinator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        workflowMapper = mock(WorkflowRunMapper.class);
        runMapper = mock(TaskRunCommandMapper.class);
        executionCoordinator = mock(TaskRunExecutionCoordinator.class);
        ObjectProvider<TaskRunExecutionCoordinator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(executionCoordinator);
        coordinator = new WorkflowRunCoordinator(
            workflowMapper, runMapper, mock(NotificationApplicationService.class),
            provider, jsonMapper
        );
        when(workflowMapper.materializeAndStart(
            anyLong(), anyLong(), anyString(), anyString(), anyString()
        )).thenReturn(1);
    }

    @Test
    void deliveryTemplateLaunchesThreeBranchesExactlyOnce() {
        List<WorkflowRunStepRow> steps = new ArrayList<>(List.of(
            root(11L, "backend", "backend"),
            root(12L, "frontend", "frontend"),
            root(13L, "test", "test"),
            aggregate(14L, "summary", List.of("backend", "frontend", "test"))
        ));
        when(workflowMapper.lockSteps(100L)).thenReturn(steps);

        coordinator.startReadyAfterCommit(100L, 200L, "worker-1");
        coordinator.startReadyAfterCommit(100L, 200L, "worker-1");

        verify(workflowMapper, times(3)).materializeAndStart(
            anyLong(), anyLong(), anyString(), anyString(), anyString()
        );
        verify(executionCoordinator, times(3)).launchOrMarkFailed(any(AgentRunRequest.class));
        assertEquals(3, steps.stream().filter(step -> "running".equals(step.getStatus())).count());
        assertEquals("pending", steps.get(3).getStatus());
    }

    @Test
    void dependentStepReceivesOnlyBoundedPersistedOutput() {
        WorkflowRunStepRow plan = completed(
            11L, "supervisor_plan", "{\"plan\":\"approved\"}"
        );
        WorkflowRunStepRow executor = step(12L, "executor", "executor", List.of("supervisor_plan"));
        List<WorkflowRunStepRow> steps = new ArrayList<>(List.of(plan, executor));
        when(workflowMapper.lockSteps(100L)).thenReturn(steps);

        coordinator.startReadyAfterCommit(100L, 200L, "worker-1");

        ArgumentCaptor<String> runtime = ArgumentCaptor.forClass(String.class);
        verify(workflowMapper).materializeAndStart(
            anyLong(), anyLong(), runtime.capture(), anyString(), anyString()
        );
        AgentRunRequest materialized = jsonMapper.readValue(
            runtime.getValue(), AgentRunRequest.class
        );
        assertTrue(materialized.input().contains("approved"));
        assertEquals(
            List.of("supervisor_plan"),
            materialized.attributes().get("workflowDependencyStepKeys")
        );
    }

    @Test
    void oneBranchFailureFailsClosedAndCancelsOtherBranches() {
        AgentRunRequest failed = request(11L, "backend", "backend");
        WorkflowRunStepRow active = root(12L, "frontend", "frontend");
        active.setStatus("running");
        active.setRuntimeSnapshotJson(jsonMapper.writeValueAsString(
            request(12L, "frontend", "frontend")
        ));
        when(workflowMapper.selectActiveSteps(100L)).thenReturn(List.of(active));
        when(workflowMapper.failRunningStep(
            100L, 11L, "RUNTIME_FAILED", "failed"
        )).thenReturn(1);
        when(runMapper.failRun(100L, "RUNTIME_FAILED", "failed")).thenReturn(1);

        coordinator.onFailure(failed, "RUNTIME_FAILED", "failed");

        verify(runMapper).cancelSteps(100L);
        verify(runMapper).markTaskBlocked(200L, 100L);
        verify(executionCoordinator).requestCancellation(any(AgentRunRequest.class), anyString());
    }

    private WorkflowRunStepRow root(Long id, String key, String role) {
        return step(id, key, role, List.of());
    }

    private WorkflowRunStepRow step(Long id, String key, String role, List<String> dependencies) {
        WorkflowRunStepRow step = new WorkflowRunStepRow();
        step.setId(id);
        step.setRunId(100L);
        step.setStepKey(key);
        step.setStepType("agent");
        step.setRoleKey(role);
        step.setSequenceNo(id.intValue());
        step.setStatus("pending");
        step.setAgentVersionId(1000L + id);
        step.setDependsOnJson(jsonMapper.writeValueAsString(dependencies));
        step.setRuntimeTemplateJson(jsonMapper.writeValueAsString(request(id, key, role)));
        return step;
    }

    private WorkflowRunStepRow aggregate(Long id, String key, List<String> dependencies) {
        WorkflowRunStepRow step = new WorkflowRunStepRow();
        step.setId(id);
        step.setRunId(100L);
        step.setStepKey(key);
        step.setStepType("aggregate");
        step.setSequenceNo(id.intValue());
        step.setStatus("pending");
        step.setDependsOnJson(jsonMapper.writeValueAsString(dependencies));
        return step;
    }

    private WorkflowRunStepRow completed(Long id, String key, String outputJson) {
        WorkflowRunStepRow step = new WorkflowRunStepRow();
        step.setId(id);
        step.setRunId(100L);
        step.setStepKey(key);
        step.setStepType("agent");
        step.setRoleKey("supervisor");
        step.setSequenceNo(1);
        step.setStatus("succeeded");
        step.setAgentVersionId(1000L + id);
        step.setDependsOnJson("[]");
        step.setOutputSummary("approved plan");
        step.setOutputJson(outputJson);
        return step;
    }

    private AgentRunRequest request(Long stepId, String key, String role) {
        return new AgentRunRequest(
            new RuntimeExecutionKey(
                "task-run-100-step-" + stepId,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            ),
            300L, null, 200L, 100L, stepId, 1000L + stepId, role,
            "session-" + stepId, "task input", "system prompt",
            new RuntimeModelConfig(
                "openai-compatible", "model", "https://model.example/v1", "env:KEY", Map.of()
            ),
            "run-100", 8, Map.of("principalId", 300L), Map.of(
                "taskVersionId", 400L,
                "workflowVersionId", 500L,
                "workflowContentHash", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "workflowNodeKey", key,
                "workflowRole", role,
                "workflowMaxParallelism", 3,
                "workflowMaxDependencyBytes", 65536
            )
        );
    }
}
