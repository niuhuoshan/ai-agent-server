package group.aitools.nhs.platform.sandbox.service;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.platform.execution.service.TaskRunExecutionCoordinator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 表示沙箱Completion相关的领域对象。
 */
@Component
public class SandboxCompletionListener {

    private final SandboxCompletionResumeService resumeService;
    private final TaskRunExecutionCoordinator coordinator;

    /**
     * 创建 {@code SandboxCompletionListener} 实例并初始化所需依赖。
     *
     * @param resumeService {@code resumeService}参数
     * @param coordinator {@code coordinator}参数
     */
    public SandboxCompletionListener(
        SandboxCompletionResumeService resumeService,
        TaskRunExecutionCoordinator coordinator
    ) {
        this.resumeService = resumeService;
        this.coordinator = coordinator;
    }

    /**
     * 处理{@code onCompleted}相关逻辑。
     *
     * @param event 事件参数
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompleted(SandboxJobCompletedEvent event) {
        if (!coordinator.available()) {
            return;
        }
        AgentResumeRequest request = resumeService.prepare(event.jobId(), coordinator.workerId());
        if (request != null) {
            coordinator.launchResumeOrMarkFailed(request);
        }
    }
}
