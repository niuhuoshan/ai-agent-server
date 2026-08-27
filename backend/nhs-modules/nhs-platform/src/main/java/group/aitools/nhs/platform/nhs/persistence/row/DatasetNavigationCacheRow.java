package group.aitools.nhs.platform.nhs.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示数据集Navigation缓存相关的领域对象。
 * Persisted per-user navigation payload for one authorized catalog hash. */
@Data
public class DatasetNavigationCacheRow {
    private Long userId;
    private String menuHash;
    private String payloadJson;
    private LocalDateTime generatedAt;
    private LocalDateTime expiresAt;
}
