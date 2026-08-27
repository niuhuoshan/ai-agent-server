package group.aitools.nhs.platform.operations.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封装LogTable存储相关的不可变数据。
 */
public record LogTableStorageView(
    String tableName,
    String displayName,
    String storageMode,
    String partitionKey,
    long estimatedRows,
    long sizeBytes,
    LocalDateTime oldestAt,
    LocalDateTime newestAt,
    long expiredRows,
    List<LogPartitionView> partitions
) {
}
