package group.aitools.nhs.platform.knowledge.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体知识库文档相关的领域对象。
 */
@Data
public class AgentKnowledgeDocument {
    private Long id;
    private Long knowledgeBaseId;
    private String documentKey;
    private String name;
    private Long artifactId;
    private String externalId;
    private String contentHash;
    private String parserType;
    private String status;
    private Integer chunkCount;
    private String metadataJson;
    private String errorSummary;
    private String storageType;
    private String storageRef;
    private String mimeType;
    private Long sizeBytes;
    /** Catalog-only revision; parse revision_no remains independent for worker idempotency. */
    private Long catalogRevisionNo;
    private Long directoryId;
    private String tagsJson;
    private String remark;
    private Long revisionNo;
    private LocalDateTime parseStartedAt;
    private LocalDateTime processedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String delFlag;
}
