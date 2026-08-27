package group.aitools.nhs.runtime.spi;

/**
 * 封装运行时执行Key相关的不可变数据。
 * Stable identifiers used for deduplication, cancellation and cross-process recovery. */
public record RuntimeExecutionKey(String executionId, String traceId) {

    public RuntimeExecutionKey {
        executionId = requireBounded(executionId, "executionId", 128);
        traceId = requireBounded(traceId, "traceId", 64);
    }

    /**
     * 校验{@code Bounded}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param name 名称
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private static String requireBounded(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }
}
