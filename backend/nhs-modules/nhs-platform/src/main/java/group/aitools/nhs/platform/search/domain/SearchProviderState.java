package group.aitools.nhs.platform.search.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示Search提供方State相关的领域对象。
 * Persisted health and circuit state for one governed web-search provider. */
@Data
public class SearchProviderState {
    private Long connectorId;
    private String circuitState;
    private Integer consecutiveFailures;
    private Long totalRequests;
    private Long totalFailures;
    private Integer lastLatencyMs;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime lastFailureAt;
    private LocalDateTime openedAt;
    private LocalDateTime nextProbeAt;
    private String lastError;
    private LocalDateTime updatedAt;
}
