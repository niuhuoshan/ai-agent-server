package group.aitools.nhs.platform.embed.web;

import java.time.LocalDateTime;

/**
 * 封装嵌入式会话会话相关的不可变数据。
 */
public record EmbedSessionView(
    Long id,
    Long agentVersionId,
    String status,
    LocalDateTime expiresAt,
    LocalDateTime createdAt
) {
}
