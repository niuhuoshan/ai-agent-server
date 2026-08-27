package group.aitools.nhs.platform.sandbox.service;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.platform.connector.service.PlatformRuntimeToolProvider;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentRunRuntimeMapper;
import group.aitools.nhs.platform.execution.persistence.row.AgentRunRuntimeRow;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 表示沙箱External执行相关的领域对象。
 */
@Service
public class SandboxExternalExecutionDispatcher {

    private final PlatformRuntimeToolProvider toolProvider;
    private final AgentRunRuntimeMapper runtimeMapper;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code SandboxExternalExecutionDispatcher} 实例并初始化所需依赖。
     *
     * @param toolProvider 工具提供方参数
     * @param runtimeMapper 运行时Mapper参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public SandboxExternalExecutionDispatcher(
        PlatformRuntimeToolProvider toolProvider,
        AgentRunRuntimeMapper runtimeMapper,
        JsonMapper jsonMapper
    ) {
        this.toolProvider = toolProvider;
        this.runtimeMapper = runtimeMapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 执行{@code dispatch}相关的处理流程。
     *
     * @param request 请求参数
     * @param source 数据源参数
     */
    public void dispatch(AgentRunRequest request, RuntimeEvent source) {
        toolProvider.enqueueExternalExecution(request, source);
    }

    /**
     * 执行{@code dispatch}相关的处理流程。
     *
     * @param request 请求参数
     * @param source 数据源参数
     */
    public void dispatch(AgentResumeRequest request, RuntimeEvent source) {
        AgentRunRuntimeRow row = runtimeMapper.selectRuntimeSnapshotByRunAndStep(
            request.runId(), request.stepId()
        );
        if (row == null || row.getRuntimeSnapshotJson() == null) {
            throw new IllegalStateException("沙箱外部执行缺少持久化运行快照");
        }
        AgentRunRequest frozen = jsonMapper.readValue(row.getRuntimeSnapshotJson(), AgentRunRequest.class);
        if (!request.runId().equals(frozen.runId())
            || !request.taskId().equals(frozen.taskId())
            || !request.stepId().equals(frozen.stepId())
            || !request.executionKey().equals(frozen.executionKey())) {
            throw new SecurityException("沙箱外部执行运行身份不一致");
        }
        toolProvider.enqueueExternalExecution(frozen, source);
    }
}
