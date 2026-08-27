package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequestResolver;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentRunRuntimeMapper;
import group.aitools.nhs.platform.execution.persistence.row.AgentRunRuntimeRow;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/**
 * 负责Database智能体RunRequest相关的转换、解析或处理逻辑。
 * Restores the exact runtime definition frozen on a durable task run. */
@Component
public final class DatabaseAgentRunRequestResolver implements AgentRunRequestResolver {

    private static final int MAX_RUNTIME_SNAPSHOT_BYTES = 256 * 1024;
    private static final Set<String> RESUMABLE_STATUSES = Set.of(
        "waiting_approval", "waiting_input", "paused", "blocked"
    );

    private final AgentRunRuntimeMapper runtimeMapper;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code DatabaseAgentRunRequestResolver} 实例并初始化所需依赖。
     *
     * @param runtimeMapper 运行时Mapper参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public DatabaseAgentRunRequestResolver(
        AgentRunRuntimeMapper runtimeMapper,
        JsonMapper jsonMapper
    ) {
        this.runtimeMapper = Objects.requireNonNull(runtimeMapper, "runtimeMapper must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
    }

    /**
     * 获取{@code ForResume}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Override
    public AgentRunRequest resolveForResume(AgentResumeRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Objects.requireNonNull(request, "request must not be null");
        if (request.runId() == null) {
            throw new IllegalStateException("durable runtime recovery requires runId");
        }
        AgentRunRuntimeRow row = runtimeMapper.selectRuntimeSnapshotForStep(
            request.runId(), request.stepId(), request.executionKey().traceId()
        );
        if (row == null) {
            row = runtimeMapper.selectRuntimeSnapshot(
                request.runId(), request.executionKey().traceId()
            );
        }
        if (row == null) {
            throw new IllegalStateException("persisted runtime definition was not found");
        }
        boolean workflowResume = row.getWorkflowVersionId() != null
            && "waiting".equals(row.getStepStatus());
        boolean atomicallyClaimedResume = "running".equals(row.getStatus())
            && "running".equals(row.getStepStatus());
        if (!workflowResume && !atomicallyClaimedResume
            && !RESUMABLE_STATUSES.contains(row.getStatus())) {
            throw new IllegalStateException("task run is not in a resumable state");
        }
        String snapshot = row.getRuntimeSnapshotJson();
        if (snapshot == null || snapshot.isBlank()) {
            throw new IllegalStateException("persisted runtime definition is empty");
        }
        if (snapshot.getBytes(StandardCharsets.UTF_8).length > MAX_RUNTIME_SNAPSHOT_BYTES) {
            throw new IllegalStateException("persisted runtime definition exceeds 256KB");
        }
        AgentRunRequest frozen;
        try {
            frozen = jsonMapper.readValue(snapshot, AgentRunRequest.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("persisted runtime definition is invalid", exception);
        }
        if (frozen == null
            || !row.getId().equals(frozen.runId())
            || !row.getTaskId().equals(frozen.taskId())
            || !row.getTraceId().equals(frozen.executionKey().traceId())
            || !request.stepId().equals(frozen.stepId())
            || !request.executionKey().equals(frozen.executionKey())) {
            throw new SecurityException("persisted runtime definition identity is inconsistent");
        }
        return frozen;
    }
}
