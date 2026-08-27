package group.aitools.nhs.platform.portal.quota.persistence.row;

import lombok.Data;

/**
 * 表示Quota角色相关的领域对象。
 * Active NHS role used when resolving a user's effective quota. */
@Data
public class QuotaRoleRow {

    private Long roleId;
    private String roleName;
    private String roleKey;
}
