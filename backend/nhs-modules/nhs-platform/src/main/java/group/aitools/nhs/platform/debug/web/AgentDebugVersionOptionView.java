package group.aitools.nhs.platform.debug.web;

import java.time.LocalDateTime;

/**
 * 封装智能体Debug版本Option相关的不可变数据。
 * One immutable, previously published Agent version available to the current user. */
public record AgentDebugVersionOptionView(
    Long id,
    int versionNo,
    String status,
    Long modelId,
    String contentHash,
    LocalDateTime publishedAt
) {
}
