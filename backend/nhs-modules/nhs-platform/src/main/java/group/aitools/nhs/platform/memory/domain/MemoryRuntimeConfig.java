package group.aitools.nhs.platform.memory.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置记忆运行时相关组件及其运行参数。
 * Singleton configuration for memory embedding, retrieval and maintenance. */
@Data
public class MemoryRuntimeConfig {
    private Short id;
    private Boolean enabled;
    private Boolean summaryEnabled;
    private Long embeddingModelId;
    private Integer embeddingDimension;
    private Integer searchKnnTopK;
    private Double vectorWeight;
    private Double consolidationThreshold;
    private Double baseHalfLifeDays;
    private Integer summaryTtlDays;
    private Long revisionNo;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
