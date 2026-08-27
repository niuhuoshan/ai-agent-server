package group.aitools.nhs.platform.knowledge.persistence.row;

import lombok.Data;

/**
 * 表示知识库Retrieval相关的领域对象。
 */
@Data
public class KnowledgeRetrievalRow {
    private Long chunkId;
    private Long knowledgeBaseId;
    private Long documentId;
    private String documentName;
    private Integer chunkNo;
    private String content;
    private String metadataJson;
    private Double score;
}
