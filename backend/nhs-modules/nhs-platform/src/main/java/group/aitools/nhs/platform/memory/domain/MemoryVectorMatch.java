package group.aitools.nhs.platform.memory.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示记忆VectorMatch相关的领域对象。
 * Safe memory projection returned by pgvector similarity queries. */
@Data
public class MemoryVectorMatch {
    private Long id;
    private String memoryKey;
    private String memoryType;
    private String content;
    private String sourceType;
    private Long sourceId;
    private Double confidence;
    private String sensitiveLevel;
    private String metadataJson;
    private LocalDateTime expiresAt;
    private LocalDateTime updatedAt;
    private Double vectorScore;
}
