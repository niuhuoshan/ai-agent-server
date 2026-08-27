package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

/**
 * 表示DashboardOnline用户相关的领域对象。
 * Human identity labels used to enrich an active NHS login session. */
@Data
public class DashboardOnlineUserRow {

    private Long userId;
    private String username;
    private String displayName;
    private String roleKeys;
}
