package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeToolDefinition;
import group.aitools.nhs.runtime.spi.RuntimeToolProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Objects;

/**
 * 表示平台运行时智能体工具相关的领域对象。
 * AgentScope adapter for one platform-governed frozen runtime tool. */
final class PlatformRuntimeAgentTool implements AgentTool {

    private final AgentRunRequest request;
    private final RuntimeToolDefinition definition;
    private final RuntimeToolProvider provider;
    private final ObjectMapper objectMapper;

    PlatformRuntimeAgentTool(
        AgentRunRequest request,
        RuntimeToolDefinition definition,
        RuntimeToolProvider provider,
        ObjectMapper objectMapper
    ) {
        this.request = Objects.requireNonNull(request, "request must not be null");
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 获取{@code Name}。
     *
     * @return 处理结果
     */
    @Override
    public String getName() {
        return definition.name();
    }

    /**
     * 获取{@code Description}。
     *
     * @return 处理结果
     */
    @Override
    public String getDescription() {
        return definition.description();
    }

    /**
     * 获取{@code Parameters}。
     *
     * @return 处理结果
     */
    @Override
    public Map<String, Object> getParameters() {
        return definition.inputSchema();
    }

    /**
     * 获取{@code OutputSchema}。
     *
     * @return 处理结果
     */
    @Override
    public Map<String, Object> getOutputSchema() {
        return definition.outputSchema().isEmpty() ? null : definition.outputSchema();
    }

    /**
     * 判断{@code ReadOnly}是否满足要求。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Override
    public boolean isReadOnly() {
        return definition.readOnly();
    }

    /**
     * 处理{@code callAsync}并返回对应结果。
     *
     * @param param {@code param}参数
     * @return 处理结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param == null ? Map.of() : param.getInput();
        return Mono.fromCallable(() -> provider.invoke(request, definition.id(), input))
            .subscribeOn(Schedulers.boundedElastic())
            .map(this::result);
    }

    /**
     * 处理结果并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private ToolResultBlock result(Object value) {
        try {
            return ToolResultBlock.text(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("platform tool result is not JSON serializable", exception);
        }
    }
}
