package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeKnowledgeDefinition;
import group.aitools.nhs.runtime.spi.RuntimeKnowledgeProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 表示平台运行时知识库工具相关的领域对象。
 * Explicit per-knowledge-base retrieval tool with structured citations. */
final class PlatformRuntimeKnowledgeTool implements AgentTool {

    private final AgentRunRequest request;
    private final RuntimeKnowledgeDefinition definition;
    private final RuntimeKnowledgeProvider provider;
    private final ObjectMapper objectMapper;

    PlatformRuntimeKnowledgeTool(
        AgentRunRequest request,
        RuntimeKnowledgeDefinition definition,
        RuntimeKnowledgeProvider provider,
        ObjectMapper objectMapper
    ) {
        this.request = request;
        this.definition = definition;
        this.provider = provider;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取{@code Name}。
     *
     * @return 处理结果
     */
    @Override
    public String getName() {
        return "search_knowledge_" + definition.id();
    }

    /**
     * 获取{@code Description}。
     *
     * @return 处理结果
     */
    @Override
    public String getDescription() {
        return "Search the approved knowledge base '" + definition.name()
            + "' and return grounded passages with citation IDs. " + definition.description();
    }

    /**
     * 获取{@code Parameters}。
     *
     * @return 处理结果
     */
    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of("type", "string", "minLength", 1, "maxLength", 4000),
                "topK", Map.of("type", "integer", "minimum", 1, "maximum", 20)
            ),
            "required", List.of("query"),
            "additionalProperties", false
        );
    }

    /**
     * 判断{@code ReadOnly}是否满足要求。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Override
    public boolean isReadOnly() {
        return true;
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
        Object rawQuery = input.get("query");
        if (!(rawQuery instanceof String query)) {
            return Mono.error(new IllegalArgumentException("knowledge query must be text"));
        }
        Integer topK = null;
        Object rawTopK = input.get("topK");
        if (rawTopK != null) {
            if (!(rawTopK instanceof Number number) || number.doubleValue() != number.intValue()) {
                return Mono.error(new IllegalArgumentException("topK must be an integer"));
            }
            topK = number.intValue();
        }
        Integer finalTopK = topK;
        return Mono.fromCallable(() -> provider.search(request, definition.id(), query, finalTopK))
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
            throw new IllegalStateException("knowledge result is not JSON serializable", exception);
        }
    }
}
