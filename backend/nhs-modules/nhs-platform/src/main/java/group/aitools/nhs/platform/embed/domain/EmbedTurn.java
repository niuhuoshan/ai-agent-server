package group.aitools.nhs.platform.embed.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示嵌入式会话会话回合相关的领域对象。
 */
@Data
public class EmbedTurn {
    private Long id;
    private Long sessionId;
    private String idempotencyHash;
    private String requestHash;
    private String traceId;
    private String status;
    private String errorSummary;
    private LocalDateTime stopRequestedAt;
    private String executionOwner;
    private LocalDateTime heartbeatAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
