package group.aitools.nhs.platform.operations.web;

import java.time.LocalDateTime;

/**
 * 封装{@code LogRetentionConfig}相关的不可变数据。
 */
public record LogRetentionConfigView(
    int retentionDays,
    int minRetentionDays,
    int maxRetentionDays,
    int revisionNo,
    String updatedBy,
    LocalDateTime updatedAt,
    String changeReason,
    String automaticSchedule
) {
}
