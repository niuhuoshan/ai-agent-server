package group.aitools.nhs.platform.automation.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示自动化作业相关的领域对象。
 */
@Data
public class AutomationJobRow {
    private Long id;
    private Long fireId;
    private String jobType;
    private String bizKey;
    private String payloadJson;
    private String status;
    private Integer attemptNo;
    private Integer maxAttempts;
    private LocalDateTime leaseUntil;
    private String workerId;
    private String leaseToken;
}
