package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;

/**
 * 创建并保存{@code create}。
 *
 * 定义智能体范围调用相关的处理能力契约。
 * Materializes a frozen Agent version for a new call or a persisted recovery. */
public interface AgentScopeInvocationFactory {

    AgentScopeInvocation create(AgentRunRequest request);

    /**
     * 创建并保存{@code ForResume}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    AgentScopeInvocation createForResume(AgentResumeRequest request);
}
