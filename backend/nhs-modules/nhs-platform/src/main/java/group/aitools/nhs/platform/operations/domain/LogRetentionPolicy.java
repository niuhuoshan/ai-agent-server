package group.aitools.nhs.platform.operations.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示LogRetention策略相关的领域对象。
 * Singleton retention policy for append-only audit and execution logs. */
@Data
public class LogRetentionPolicy {

    private Integer id;
    private Integer retentionDays;
    private Integer revisionNo;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    private String changeReason;
}
