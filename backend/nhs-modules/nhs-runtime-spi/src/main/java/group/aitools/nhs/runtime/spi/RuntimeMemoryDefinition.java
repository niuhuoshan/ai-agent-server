package group.aitools.nhs.runtime.spi;

/**
 * 封装运行时记忆定义相关的不可变数据。
 * One approved, frozen and currently authorized platform memory entry. */
public record RuntimeMemoryDefinition(
    Long id,
    String scopeType,
    Long scopeId,
    String memoryType,
    String content
) {

    /**
     * 创建 {@code RuntimeMemoryDefinition} 实例并初始化所需依赖。
     *
     * @param id 资源标识
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param memoryType 业务类型
     * @param content 待处理内容
     */
    public RuntimeMemoryDefinition {
        if (id == null || id <= 0 || scopeId == null || scopeId <= 0) {
            throw new IllegalArgumentException("runtime memory identifiers must be positive");
        }
        scopeType = requireText(scopeType, "scopeType");
        memoryType = requireText(memoryType, "memoryType");
        content = requireText(content, "content");
        if (content.length() > 4000) {
            throw new IllegalArgumentException("runtime memory content exceeds 4000 characters");
        }
    }

    /**
     * 校验{@code Text}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }
}
