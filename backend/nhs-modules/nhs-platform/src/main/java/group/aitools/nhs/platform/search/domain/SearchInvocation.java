package group.aitools.nhs.platform.search.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示Search调用相关的领域对象。
 * Content-free invocation audit; raw queries and provider responses are never stored here. */
@Data
public class SearchInvocation {
    private Long id;
    private Long connectorId;
    private Long actorId;
    private String runId;
    private String traceId;
    private String querySha256;
    private Integer resultCount;
    private String status;
    private Integer latencyMs;
    private String errorCode;
    private LocalDateTime occurredAt;
}
