package group.aitools.nhs.platform.audit.mapper;

import lombok.Data;

/**
 * 表示审计统计相关的领域对象。
 * Aggregate values returned by the bounded audit statistics query. */
@Data
public class AuditStatisticsRow {

    private long total;
    private long allowCount;
    private long denyCount;
    private long approvalRequiredCount;
    private long successCount;
    private long failureCount;
    private long distinctActors;
    private long distinctTraces;
}
