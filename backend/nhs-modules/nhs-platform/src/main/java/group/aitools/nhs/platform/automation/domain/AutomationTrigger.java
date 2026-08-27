package group.aitools.nhs.platform.automation.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示自动化Trigger相关的领域对象。
 */
@Data
public class AutomationTrigger {
    private Long id;
    private String triggerKey;
    private String name;
    private String triggerType;
    private Long taskId;
    private Long taskVersionId;
    private Long taskRevisionNo;
    private Long serviceAccountId;
    private String cronExpr;
    private String timezone;
    private String status;
    private String misfirePolicy;
    private Integer maxCatchupCount;
    private Integer maxAttempts;
    private String inputTemplate;
    private LocalDateTime lastRunAt;
    private LocalDateTime nextRunAt;
    private Long revisionNo;
    private String configJson;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
}
