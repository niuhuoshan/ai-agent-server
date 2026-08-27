package group.aitools.nhs.platform.migration.web;

import group.aitools.nhs.platform.migration.domain.LegacyExecutionArchive;

import java.time.LocalDateTime;

/**
 * 封装Legacy执行Archive相关的不可变数据。
 * Read-only archive metadata. Raw legacy payloads are never exposed through the UI API. */
public record LegacyExecutionArchiveView(
    Long id,
    Long migrationRunId,
    String sourceSystem,
    String sourceTraceId,
    String sourceExecutionId,
    String sourceAgentId,
    String sourceUserId,
    String sourceConversationId,
    String sourceStatus,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    String summary,
    String contentHash,
    LocalDateTime createdAt
) {
    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    public static LegacyExecutionArchiveView from(LegacyExecutionArchive row) {
        return new LegacyExecutionArchiveView(
            row.getId(), row.getMigrationRunId(), row.getSourceSystem(), row.getSourceTraceId(),
            row.getSourceExecutionId(), row.getSourceAgentId(), row.getSourceUserId(),
            row.getSourceConversationId(), row.getSourceStatus(), row.getStartedAt(),
            row.getFinishedAt(), row.getSummary(), row.getContentHash(), row.getCreatedAt()
        );
    }
}
