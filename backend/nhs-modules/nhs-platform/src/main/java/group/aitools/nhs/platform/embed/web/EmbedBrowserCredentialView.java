package group.aitools.nhs.platform.embed.web;

import java.time.LocalDateTime;

/**
 * 封装嵌入式会话浏览器凭据相关的不可变数据。
 */
public record EmbedBrowserCredentialView(
    String credential,
    LocalDateTime expiresAt,
    String protocolVersion,
    String embedPath
) {
}
