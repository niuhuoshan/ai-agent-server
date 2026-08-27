package group.aitools.nhs.platform.project.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体项目相关的领域对象。
 * Project control-plane record and its reusable execution policies. */
@Data
public class AgentProject {

    private Long id;
    private String projectKey;
    private String name;
    private String description;
    private String status;
    private Long ownerId;
    private Long defaultAgentVersionId;
    private String workspacePolicyJson;
    private String notificationPolicyJson;
    private String tagsJson;
    private LocalDateTime archivedAt;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
    private String extraJson;
}
