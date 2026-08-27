package group.aitools.nhs.platform.portal.quota.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体Quota策略相关的领域对象。
 * Monthly Token quota policy scoped to a user, role or the system default. */
@Data
public class AgentQuotaPolicy {

    private Long id;
    private String scopeType;
    private Long scopeId;
    private String period;
    private Long limitTokens;
    private Boolean enabled;
    private String actionOnExceed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
