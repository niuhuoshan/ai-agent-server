package group.aitools.nhs.platform.project.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体项目Member相关的领域对象。
 * Explicit project operation relation; it is not a platform role. */
@Data
public class AgentProjectMember {

    private Long id;
    private Long projectId;
    private Long userId;
    private String memberRole;
    private String permissionJson;
    private String status;
    private LocalDateTime joinedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
