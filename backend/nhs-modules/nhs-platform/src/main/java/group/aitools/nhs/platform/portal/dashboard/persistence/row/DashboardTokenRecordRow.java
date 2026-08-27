package group.aitools.nhs.platform.portal.dashboard.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示Dashboard令牌Record相关的领域对象。
 * Token usage record with only non-sensitive catalog labels. */
@Data
public class DashboardTokenRecordRow {

    private String source;
    private Long id;
    private Long userId;
    private String username;
    private String displayName;
    private LocalDateTime createdAt;
    private Long agentId;
    private String agentName;
    private Long modelId;
    private String modelName;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private String status;
}
