package group.aitools.nhs.platform.audit.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封装元数据ChangelogStats相关的不可变数据。
 */
public record MetadataChangelogStatsView(
    long total,
    LocalDateTime createdFrom,
    LocalDateTime createdTo,
    List<MetadataChangelogBreakdownView> breakdown
) {
    /**
     * 封装元数据ChangelogBreakdown相关的不可变数据。
     */
    public record MetadataChangelogBreakdownView(
        String resourceType,
        String action,
        long count
    ) {
    }
}
