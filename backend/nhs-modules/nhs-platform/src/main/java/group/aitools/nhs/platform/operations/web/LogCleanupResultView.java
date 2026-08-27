package group.aitools.nhs.platform.operations.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封装LogCleanup结果相关的不可变数据。
 */
public record LogCleanupResultView(
    String runId,
    String status,
    String triggerType,
    int retentionDays,
    LocalDateTime cutoffAt,
    List<String> createdPartitions,
    List<String> droppedPartitions,
    long droppedRows,
    long deletedRows,
    boolean remainingExpiredRows,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    String message,
    List<LogCleanupTableResultView> tables
) {
}
