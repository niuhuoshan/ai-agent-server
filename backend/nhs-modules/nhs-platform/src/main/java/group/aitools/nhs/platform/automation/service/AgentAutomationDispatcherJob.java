package group.aitools.nhs.platform.automation.service;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 表示智能体自动化Dispatcher作业相关的领域对象。
 * Single SnailJob entrypoint; database locks provide cluster-wide dispatch exclusivity. */
@Component
@JobExecutor(name = "agentAutomationDispatcher")
@ConditionalOnProperty(
    prefix = "agent.platform.automation",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class AgentAutomationDispatcherJob {

    private final AutomationCronDispatcher cronDispatcher;
    private final AutomationJobWorker jobWorker;

    /**
     * 创建 {@code AgentAutomationDispatcherJob} 实例并初始化所需依赖。
     *
     * @param cronDispatcher {@code cronDispatcher}参数
     * @param jobWorker 作业工作进程参数
     */
    public AgentAutomationDispatcherJob(
        AutomationCronDispatcher cronDispatcher,
        AutomationJobWorker jobWorker
    ) {
        this.cronDispatcher = cronDispatcher;
        this.jobWorker = jobWorker;
    }

    /**
     * 处理作业Execute并返回对应结果。
     *
     * @param ignored {@code ignored}参数
     * @return 处理结果
     */
    public ExecuteResult jobExecute(JobArgs ignored) {
        try {
            cronDispatcher.dispatchDue();
            jobWorker.poll();
            return ExecuteResult.success("agent automation dispatch completed");
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }
            message = message.replace('\0', ' ');
            return ExecuteResult.failure(message.length() <= 500 ? message : message.substring(0, 500));
        }
    }
}
