package group.aitools.nhs.platform.search.web;

import java.time.LocalDateTime;

/**
 * 封装Search提供方相关的不可变数据。
 */
public record SearchProviderView(
    Long connectorId,
    String connectorKey,
    String name,
    String scope,
    boolean manageable,
    String engine,
    String endpointUrl,
    String status,
    String circuitState,
    int consecutiveFailures,
    long totalRequests,
    long totalFailures,
    Integer lastLatencyMs,
    LocalDateTime lastCheckAt,
    LocalDateTime lastSuccessAt,
    LocalDateTime lastFailureAt,
    LocalDateTime nextProbeAt,
    String lastError,
    int maxResults,
    int rateLimitPerMinute,
    int failureThreshold,
    int cooldownSeconds
) {
}
