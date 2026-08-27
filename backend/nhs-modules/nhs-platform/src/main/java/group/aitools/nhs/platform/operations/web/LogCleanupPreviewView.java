package group.aitools.nhs.platform.operations.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封装{@code LogCleanupPreview}相关的不可变数据。
 */
public record LogCleanupPreviewView(
    String runId,
    String confirmationToken,
    LocalDateTime confirmationExpiresAt,
    int retentionDays,
    int policyRevision,
    LocalDateTime cutoffAt,
    long expiredRows,
    long removablePartitions,
    int maxRowsPerTablePerRun,
    boolean mayRequireMultipleRuns,
    List<LogTableStorageView> tables,
    List<String> warnings
) {
}
