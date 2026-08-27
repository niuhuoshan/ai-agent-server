package group.aitools.nhs.platform.debug.web;

/**
 * 封装智能体DebugMetrics相关的不可变数据。
 * Metrics calculated from persisted model/tool events and durable run timestamps. */
public record AgentDebugMetricsView(
    long promptTokens,
    long completionTokens,
    long cachedTokens,
    long totalTokens,
    long elapsedMs,
    long modelDurationMs,
    int modelCalls,
    int toolCalls,
    int eventCount,
    boolean truncated
) {
}
