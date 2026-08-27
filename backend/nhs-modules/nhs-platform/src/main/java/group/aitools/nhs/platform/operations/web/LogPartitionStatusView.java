package group.aitools.nhs.platform.operations.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封装{@code LogPartitionStatus}相关的不可变数据。
 */
public record LogPartitionStatusView(
    String databaseType,
    LocalDateTime checkedAt,
    int retentionDays,
    LocalDateTime cutoffAt,
    int futureMonthsPrepared,
    int batchSize,
    int maxRowsPerTablePerRun,
    List<LogTableStorageView> tables
) {
}
