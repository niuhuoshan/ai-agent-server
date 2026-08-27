package group.aitools.nhs.platform.model.web;

/**
 * 封装模型Connection相关的不可变数据。
 * Sanitized outcome of a provider connectivity check. */
public record ModelConnectionView(
    boolean success,
    String message,
    String responseSummary,
    long latencyMs
) {

    /**
     * 处理{@code failure}并返回对应结果。
     *
     * @param message 待处理内容
     * @param latencyMs {@code latencyMs}参数
     * @return 处理结果
     */
    public static ModelConnectionView failure(String message, long latencyMs) {
        return new ModelConnectionView(false, message, null, latencyMs);
    }
}
