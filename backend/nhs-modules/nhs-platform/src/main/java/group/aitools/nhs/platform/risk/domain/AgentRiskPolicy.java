package group.aitools.nhs.platform.risk.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体风险策略相关的领域对象。
 * Structured policy used to classify and handle risky resource actions. */
@Data
public class AgentRiskPolicy {

    private Long id;
    private String policyKey;
    private String name;
    private String resourceType;
    private String action;
    private String riskLevel;
    private String disposition;
    private String approvalRole;
    private Boolean notifyEnabled;
    private Integer priority;
    private String description;
    private String status;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
}
