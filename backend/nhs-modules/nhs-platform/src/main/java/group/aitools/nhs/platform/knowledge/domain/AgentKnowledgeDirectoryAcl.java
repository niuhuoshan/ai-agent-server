package group.aitools.nhs.platform.knowledge.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体知识库目录Acl相关的领域对象。
 * User-level directory ACL. A null directory id represents the knowledge-base root. */
@Data
public class AgentKnowledgeDirectoryAcl {
    private Long id;
    private Long knowledgeBaseId;
    private Long directoryId;
    private Long userId;
    private String permission;
    private String effect;
    private Boolean inheritChildren;
    private Long revisionNo;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
