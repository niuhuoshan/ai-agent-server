package group.aitools.nhs.platform.provider;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 处理提供方Type并返回对应结果。
 *
 * 定义External智能体相关的处理能力契约。
 * Optional boundary for externally hosted agent engines. */
public interface ExternalAgentProvider {

    String providerType();

    /**
     * 执行{@code invoke}相关的处理流程。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    Flux<ExternalAgentEvent> invoke(ExternalAgentRequest request);

    /**
     * 判断{@code cel}是否满足要求。
     *
     * @param executionId 资源标识
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    default Mono<Boolean> cancel(String executionId, String reason) {
        return Mono.just(false);
    }

    /**
     * 封装External智能体相关的不可变数据。
     */
    record ExternalAgentRequest(
        String executionId,
        String sessionId,
        Long principalId,
        Long conversationId,
        String input,
        Map<String, Object> frozenConfig
    ) {
        /**
         * 创建 {@code ExternalAgentRequest} 实例并初始化所需依赖。
         *
         * @param executionId 资源标识
         * @param sessionId 资源标识
         * @param principalId 资源标识
         * @param conversationId 资源标识
         * @param input {@code input}参数
         * @param frozenConfig {@code frozenConfig}参数
         */
        public ExternalAgentRequest {
            frozenConfig = frozenConfig == null ? Map.of() : Map.copyOf(frozenConfig);
        }
    }

    /**
     * 封装External智能体相关的不可变数据。
     */
    record ExternalAgentEvent(String eventId, String type, String text, Map<String, Object> payload) {
        /**
         * 创建 {@code ExternalAgentEvent} 实例并初始化所需依赖。
         *
         * @param eventId 资源标识
         * @param type 业务类型
         * @param text 待处理内容
         * @param payload {@code payload}参数
         */
        public ExternalAgentEvent {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }
}
