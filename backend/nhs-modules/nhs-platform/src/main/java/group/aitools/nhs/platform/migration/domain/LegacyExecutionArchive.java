package group.aitools.nhs.platform.migration.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示Legacy执行Archive相关的领域对象。
 * Secret-free projection of a migrated Nhs execution archive row. */
@Data
public class LegacyExecutionArchive {

    private Long id;
    private Long migrationRunId;
    private String sourceSystem;
    private String sourceTraceId;
    private String sourceExecutionId;
    private String sourceAgentId;
    private String sourceUserId;
    private String sourceConversationId;
    private String sourceStatus;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String summary;
    private String contentHash;
    private LocalDateTime createdAt;
}
