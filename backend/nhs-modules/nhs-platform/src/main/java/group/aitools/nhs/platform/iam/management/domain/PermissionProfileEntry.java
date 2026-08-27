package group.aitools.nhs.platform.iam.management.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示权限配置档案Entry相关的领域对象。
 * One capability rule stored in a permission profile version. */
@Data
public class PermissionProfileEntry {

    private Long id;
    private Long profileId;
    private String resourceType;
    private Long resourceId;
    private String resourceKey;
    private String action;
    private String effect;
    private String policyJson;
    private LocalDateTime createdAt;
}
