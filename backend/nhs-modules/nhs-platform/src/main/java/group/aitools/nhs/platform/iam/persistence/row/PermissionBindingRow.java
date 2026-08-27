package group.aitools.nhs.platform.iam.persistence.row;

import lombok.Data;

/**
 * 表示权限Binding相关的领域对象。
 * Active user binding fields needed by permission resolution. */
@Data
public class PermissionBindingRow {

    private Long id;
    private String bindingType;
    private Long profileId;
    private Integer profileVersion;
    private String snapshotJson;
}
