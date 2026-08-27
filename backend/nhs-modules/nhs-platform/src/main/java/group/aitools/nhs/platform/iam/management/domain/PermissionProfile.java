package group.aitools.nhs.platform.iam.management.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示权限配置档案相关的领域对象。
 * One immutable version of a reusable permission profile. */
@Data
public class PermissionProfile {

    private Long id;
    private String profileKey;
    private String name;
    private String description;
    private String profileType;
    private Integer versionNo;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String delFlag;
}
