package group.aitools.nhs.runtime.spi;

import java.util.Objects;

/**
 * 封装运行时知识库定义相关的不可变数据。
 * One frozen knowledge base exposed to a runtime as an explicit retrieval tool. */
public record RuntimeKnowledgeDefinition(
    Long id,
    String name,
    String description,
    boolean requiresApproval
) {
    /**
     * 创建 {@code RuntimeKnowledgeDefinition} 实例并初始化所需依赖。
     *
     * @param id 资源标识
     * @param name 名称
     * @param description {@code description}参数
     * @param requiresApproval requires审批参数
     */
    public RuntimeKnowledgeDefinition {
        Objects.requireNonNull(id, "knowledge id must not be null");
        if (id <= 0) {
            throw new IllegalArgumentException("knowledge id must be positive");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("knowledge name must not be blank");
        }
        description = description == null ? "" : description;
    }
}
