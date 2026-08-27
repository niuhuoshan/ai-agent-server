package group.aitools.nhs.platform.operations.web;

import java.time.LocalDateTime;

/**
 * 封装{@code LogPartition}相关的不可变数据。
 */
public record LogPartitionView(
    String partitionName,
    String boundExpression,
    boolean defaultPartition,
    long estimatedRows,
    long sizeBytes,
    LocalDateTime oldestAt,
    LocalDateTime newestAt,
    long expiredRows,
    boolean removableCandidate
) {
}
