package group.aitools.nhs.platform.memory.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体记忆相关的领域对象。
 */
@Data
public class AgentMemory {
    private Long id;
    private String memoryKey;
    private String scopeType;
    private Long scopeId;
    private String memoryType;
    private String content;
    private String contentHash;
    private String sourceType;
    private Long sourceId;
    private Double confidence;
    private String sensitiveLevel;
    private String reviewStatus;
    private Long embeddingModelId;
    private Integer embeddingDimension;
    private LocalDateTime expiresAt;
    private String metadataJson;
    private Long revisionNo;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewComment;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String delFlag;
}
