package group.aitools.nhs.runtime.spi;

import java.util.Map;
import java.util.Objects;

/**
 * 封装运行时工具定义相关的不可变数据。
 * Frozen tool schema exposed to one AgentScope run. */
public record RuntimeToolDefinition(
    Long id,
    String name,
    String description,
    Map<String, Object> inputSchema,
    Map<String, Object> outputSchema,
    String riskLevel,
    boolean readOnly,
    boolean externalExecution
) {
    /**
     * 创建 {@code RuntimeToolDefinition} 实例并初始化所需依赖。
     *
     * @param id 资源标识
     * @param name 名称
     * @param description {@code description}参数
     * @param inputSchema {@code inputSchema}参数
     * @param outputSchema {@code outputSchema}参数
     * @param riskLevel 风险Level参数
     * @param readOnly {@code readOnly}参数
     */
    public RuntimeToolDefinition(
        Long id,
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        String riskLevel,
        boolean readOnly
    ) {
        this(id, name, description, inputSchema, outputSchema, riskLevel, readOnly, false);
    }

    /**
     * 创建 {@code RuntimeToolDefinition} 实例并初始化所需依赖。
     *
     * @param id 资源标识
     * @param name 名称
     * @param description {@code description}参数
     * @param inputSchema {@code inputSchema}参数
     * @param outputSchema {@code outputSchema}参数
     * @param riskLevel 风险Level参数
     * @param readOnly {@code readOnly}参数
     * @param externalExecution external执行参数
     */
    public RuntimeToolDefinition {
        Objects.requireNonNull(id, "tool id must not be null");
        if (id <= 0) {
            throw new IllegalArgumentException("tool id must be positive");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        description = description == null ? "" : description;
        inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema, "input schema must not be null"));
        outputSchema = Map.copyOf(Objects.requireNonNull(outputSchema, "output schema must not be null"));
        if (!java.util.Set.of("R0", "R1", "R2", "R3").contains(riskLevel)) {
            throw new IllegalArgumentException("unsupported tool risk level");
        }
    }
}
