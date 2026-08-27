package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示DashboardRecent用户相关的领域对象。
 * User activity projection without credentials or personal content. */
@Data
public class DashboardRecentUserRow {

    private Long userId;
    private String username;
    private String displayName;
    private LocalDateTime lastActive;
}
