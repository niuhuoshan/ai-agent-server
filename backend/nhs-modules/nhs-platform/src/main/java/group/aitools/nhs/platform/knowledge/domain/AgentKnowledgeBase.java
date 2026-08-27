package group.aitools.nhs.platform.knowledge.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体知识库Base相关的领域对象。
 */
@Data
public class AgentKnowledgeBase {
    private Long id;
    private String knowledgeKey;
    private String name;
    private String description;
    private String providerType;
    private Long connectorId;
    private String externalId;
    private String visibility;
    private String status;
    private String configJson;
    private Long ownerId;
    private Long revisionNo;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
    private String extraJson;
}
