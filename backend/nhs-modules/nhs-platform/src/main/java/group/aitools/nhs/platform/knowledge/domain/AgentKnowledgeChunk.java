package group.aitools.nhs.platform.knowledge.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体知识库Chunk相关的领域对象。
 */
@Data
public class AgentKnowledgeChunk {
    private Long id;
    private Long knowledgeBaseId;
    private Long documentId;
    private Integer chunkNo;
    private String content;
    private String contentHash;
    private Integer tokenCount;
    private Long embeddingModelId;
    private Integer embeddingDimension;
    private String embedding;
    private String metadataJson;
    private String status;
    private LocalDateTime createdAt;
}
