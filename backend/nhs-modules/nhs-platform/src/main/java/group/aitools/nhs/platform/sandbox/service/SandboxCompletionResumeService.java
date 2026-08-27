package group.aitools.nhs.platform.sandbox.service;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeResumeDecision;
import group.aitools.nhs.runtime.spi.RuntimeResumeMode;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentRunRuntimeMapper;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.execution.persistence.row.AgentRunRuntimeRow;
import group.aitools.nhs.platform.sandbox.mapper.SandboxRunnerMapper;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobRow;
import group.aitools.nhs.platform.workflow.mapper.WorkflowRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责沙箱CompletionResume相关的业务编排与领域规则处理。
 */
@Service
public class SandboxCompletionResumeService {

    private static final Set<String> TERMINAL = Set.of(
        "succeeded", "failed", "cancelled", "expired"
    );

    private final SandboxRunnerMapper sandboxMapper;
    private final AgentRunRuntimeMapper runtimeMapper;
    private final TaskRunCommandMapper runMapper;
    private final JsonMapper jsonMapper;
    private final WorkflowRunMapper workflowMapper;

    /**
     * 创建 {@code SandboxCompletionResumeService} 实例并初始化所需依赖。
     *
     * @param sandboxMapper 沙箱Mapper参数
     * @param runtimeMapper 运行时Mapper参数
     * @param runMapper {@code runMapper}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param workflowMapper 工作流Mapper参数
     */
    @Autowired
    public SandboxCompletionResumeService(
        SandboxRunnerMapper sandboxMapper,
        AgentRunRuntimeMapper runtimeMapper,
        TaskRunCommandMapper runMapper,
        JsonMapper jsonMapper,
        WorkflowRunMapper workflowMapper
    ) {
        this.sandboxMapper = sandboxMapper;
        this.runtimeMapper = runtimeMapper;
        this.runMapper = runMapper;
        this.jsonMapper = jsonMapper;
        this.workflowMapper = workflowMapper;
    }

    /**
     * 创建 {@code SandboxCompletionResumeService} 实例并初始化所需依赖。
     *
     * @param sandboxMapper 沙箱Mapper参数
     * @param runtimeMapper 运行时Mapper参数
     * @param runMapper {@code runMapper}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public SandboxCompletionResumeService(
        SandboxRunnerMapper sandboxMapper,
        AgentRunRuntimeMapper runtimeMapper,
        TaskRunCommandMapper runMapper,
        JsonMapper jsonMapper
    ) {
        this(sandboxMapper, runtimeMapper, runMapper, jsonMapper, null);
    }

    /**
     * 处理{@code prepare}并返回对应结果。
     *
     * @param completedJobId 资源标识
     * @param workerId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentResumeRequest prepare(Long completedJobId, String workerId) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        SandboxJobRow completed = sandboxMapper.selectJob(completedJobId);
        if (completed == null
            || !TERMINAL.contains(completed.getStatus())
            || completed.getExternalReplyId() == null) {
            return null;
        }
        List<SandboxJobRow> batch = sandboxMapper.selectExternalBatch(
            completed.getRunId(), completed.getExternalReplyId()
        );
        if (batch.isEmpty()
            || batch.stream().anyMatch(job -> !TERMINAL.contains(job.getStatus()))
            || batch.stream().anyMatch(job -> job.getResumeDispatchedAt() != null)) {
            return null;
        }
        AgentRunRuntimeRow runtime = runtimeMapper.selectRuntimeSnapshotByRunAndStep(
            completed.getRunId(), completed.getStepId()
        );
        if (runtime == null) {
            runtime = runtimeMapper.selectRuntimeSnapshotByRunId(completed.getRunId());
        }
        if (runtime == null || runtime.getRuntimeSnapshotJson() == null) {
            throw new IllegalStateException("沙箱完成事件缺少运行快照");
        }
        AgentRunRequest frozen = jsonMapper.readValue(
            runtime.getRuntimeSnapshotJson(), AgentRunRequest.class
        );
        validateIdentity(completed, frozen, runtime);
        if (runtime.getWorkflowVersionId() != null) {
            if (workflowMapper == null) {
                throw new IllegalStateException("工作流恢复服务不可用");
            }
            if (workflowMapper.resumeStep(frozen.runId(), frozen.stepId()) != 1) {
                return null;
            }
            workflowMapper.projectRunStatus(frozen.runId(), "running", null);
        } else {
            if (runMapper.claimResumedRun(frozen.taskId(), frozen.runId(), workerId) != 1) {
                return null;
            }
            if (runMapper.startStep(frozen.runId(), frozen.stepId()) != 1) {
                throw new IllegalStateException("沙箱完成后运行步骤不能恢复");
            }
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (sandboxMapper.markExternalBatchResumeDispatched(
            frozen.runId(), completed.getExternalReplyId(), now
        ) != batch.size()) {
            throw new IllegalStateException("沙箱外部执行批次已被并发恢复");
        }
        List<Map<String, Object>> actions = new ArrayList<>(batch.size());
        for (SandboxJobRow job : batch) {
            actions.add(action(job));
        }
        return new AgentResumeRequest(
            frozen.executionKey(), frozen.userId(), frozen.conversationId(), frozen.taskId(),
            frozen.runId(), frozen.stepId(), frozen.sessionId(), completed.getExternalReplyId(),
            RuntimeResumeDecision.APPROVE, actions,
            Map.of("source", "sandbox_runner", "jobCount", batch.size()),
            RuntimeResumeMode.EXTERNAL_EXECUTION
        ).withRuntimeContext(frozen);
    }

    /**
     * 处理{@code action}并返回对应结果。
     *
     * @param job 作业参数
     * @return 处理结果
     */
    private Map<String, Object> action(SandboxJobRow job) {
        if (job.getToolCallId() == null || job.getToolName() == null) {
            throw new SecurityException("沙箱作业缺少AgentScope工具调用身份");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", job.getStatus());
        putIfPresent(result, "exitCode", job.getExitCode());
        putIfPresent(result, "stdout", job.getStdoutText());
        putIfPresent(result, "stderr", job.getStderrText());
        putIfPresent(result, "failureCode", job.getFailureCode());
        putIfPresent(result, "failureMessage", job.getFailureMessage());
        result.put("outputManifest", parseJson(job.getOutputManifestJson(), List.of()));
        result.put("resourceUsage", parseJson(job.getResourceUsageJson(), Map.of()));
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("id", job.getToolCallId());
        action.put("name", job.getToolName());
        action.put("succeeded", "succeeded".equals(job.getStatus()));
        action.put("result", result);
        return Map.copyOf(action);
    }

    /**
     * 处理{@code parseJson}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param defaultValue {@code defaultValue}参数
     * @return 处理结果
     */
    private Object parseJson(String value, Object defaultValue) {
        return value == null || value.isBlank()
            ? defaultValue : jsonMapper.readValue(value, Object.class);
    }

    /**
     * 处理{@code putIfPresent}相关逻辑。
     *
     * @param target {@code target}参数
     * @param key {@code key}参数
     * @param value {@code value}参数
     */
    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * 校验身份，并在条件不满足时终止处理。
     *
     * @param job 作业参数
     * @param frozen {@code frozen}参数
     * @param runtime 运行时参数
     */
    private void validateIdentity(
        SandboxJobRow job,
        AgentRunRequest frozen,
        AgentRunRuntimeRow runtime
    ) {
        if (!job.getRunId().equals(frozen.runId())
            || !job.getTaskId().equals(frozen.taskId())
            || !job.getStepId().equals(frozen.stepId())
            || !runtime.getId().equals(frozen.runId())
            || !runtime.getTaskId().equals(frozen.taskId())
            || !runtime.getTraceId().equals(frozen.executionKey().traceId())) {
            throw new SecurityException("沙箱作业与运行快照身份不一致");
        }
    }
}
