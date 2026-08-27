package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

/**
 * 表示Dashboard令牌用户相关的领域对象。
 * Token totals grouped by NHS user. */
@Data
public class DashboardTokenUserRow {

    private Long userId;
    private String username;
    private String displayName;
    private Long calls;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
}
