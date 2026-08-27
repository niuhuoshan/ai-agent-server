package group.aitools.nhs.platform.iam.persistence.row;

import lombok.Data;

/**
 * 表示权限Rule相关的领域对象。
 * Relational permission rule projected from profile, override or temporary-grant tables. */
@Data
public class PermissionRuleRow {

    private String resourceType;
    private Long resourceId;
    private String resourceKey;
    private String action;
    private String effect;
    private String source;
    private String sourceReference;
    private String reason;
}
