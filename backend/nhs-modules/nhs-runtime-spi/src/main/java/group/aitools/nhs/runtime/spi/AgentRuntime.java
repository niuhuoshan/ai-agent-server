package group.aitools.nhs.runtime.spi;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 处理{@code stream}并返回对应结果。
 *
 * 定义智能体运行时相关能力的服务契约。
 * Runtime boundary used by platform services without exposing provider-specific classes. */
public interface AgentRuntime {

    Flux<RuntimeEvent> stream(AgentRunRequest request);

    /**
     * 处理{@code resume}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    Flux<RuntimeEvent> resume(AgentResumeRequest request);

    /**
     * 判断{@code cel}是否满足要求。
     *
     * @param key {@code key}参数
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    Mono<RuntimeCancellationResult> cancel(RuntimeExecutionKey key, String reason);
}
