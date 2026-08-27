package group.aitools.nhs.platform.knowledge.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体知识库目录相关的领域对象。
 * A logical, database-backed directory inside one local knowledge base. */
@Data
public class AgentKnowledgeDirectory {
    private Long id;
    private Long knowledgeBaseId;
    private Long parentId;
    private String directoryKey;
    private String name;
    private Long revisionNo;
    private Long documentCount;
    private Long childDirectoryCount;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    private String delFlag;
}
