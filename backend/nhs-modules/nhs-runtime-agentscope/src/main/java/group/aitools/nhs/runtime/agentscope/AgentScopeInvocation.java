package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import io.agentscope.core.event.AgentEvent;
import reactor.core.publisher.Flux;

/**
 * 处理{@code stream}并返回对应结果。
 *
 * 定义智能体范围调用相关能力的服务契约。
 * One materialized AgentScope agent invocation with explicit lifecycle ownership. */
public interface AgentScopeInvocation extends AutoCloseable {

    Flux<AgentEvent> stream(AgentRunRequest request);

    /**
     * 处理{@code resume}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    Flux<AgentEvent> resume(AgentResumeRequest request);

    /**
 * 处理{@code frozenRunRequest}并返回对应结果。
 * Frozen run configuration used to materialize this invocation, when available. */
    default AgentRunRequest frozenRunRequest() {
        return null;
    }

    /**
     * 处理{@code interrupt}相关逻辑。
     *
     * @param reason {@code reason}参数
     */
    void interrupt(String reason);

    /**
     * 处理{@code close}相关逻辑。
     */
    @Override
    void close();
}
