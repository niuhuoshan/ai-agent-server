package group.aitools.nhs.platform.report.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体报表Subscription相关的领域对象。
 * Report-owned delivery subscription and its durable schedule cursor. */
@Data
public class AgentReportSubscription {

    private Long id;
    private Long reportId;
    private String scheduleType;
    private String cronExpr;
    private Integer intervalMinutes;
    private String timezone;
    private String paramsJson;
    private String notifyPolicyJson;
    private String status;
    private Integer maxAttempts;
    private Long revisionNo;
    private LocalDateTime lastRunAt;
    private LocalDateTime nextRunAt;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
    private String extraJson;
}
