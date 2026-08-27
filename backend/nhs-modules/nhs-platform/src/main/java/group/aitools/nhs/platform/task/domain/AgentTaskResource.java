package group.aitools.nhs.platform.task.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体任务资源相关的领域对象。
 * Current task resource binding; immutable historical copies live in task versions. */
@Data
public class AgentTaskResource {

    private Long id;
    private Long taskId;
    private String resourceType;
    private Long resourceId;
    private String permission;
    private Boolean required;
    private String grantSource;
    private String grantSnapshotJson;
    private Long createdBy;
    private LocalDateTime createdAt;
}
