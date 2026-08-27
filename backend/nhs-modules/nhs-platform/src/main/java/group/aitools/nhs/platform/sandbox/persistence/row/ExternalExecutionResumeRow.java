package group.aitools.nhs.platform.sandbox.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示External执行Resume相关的领域对象。
 * Durable idempotency fact for one user-submitted external execution result. */
@Data
public class ExternalExecutionResumeRow {

    private Long id;
    private Long userId;
    private String replyId;
    private Long taskId;
    private Long runId;
    private Long stepId;
    private String traceId;
    private String resultsHash;
    private String resultsJson;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime dispatchedAt;
}
