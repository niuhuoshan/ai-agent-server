package group.aitools.nhs.platform.operations.web;

import java.util.List;

/**
 * 封装LogCleanupTable结果相关的不可变数据。
 */
public record LogCleanupTableResultView(
    String tableName,
    List<String> droppedPartitions,
    long droppedRows,
    long deletedRows,
    boolean remainingExpiredRows
) {
}
