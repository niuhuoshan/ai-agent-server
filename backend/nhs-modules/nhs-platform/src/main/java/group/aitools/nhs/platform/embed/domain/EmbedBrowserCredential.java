package group.aitools.nhs.platform.embed.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示嵌入式会话浏览器凭据相关的领域对象。
 * Hashed, origin-bound browser capability used by an Embed iframe. */
@Data
public class EmbedBrowserCredential {
    private Long id;
    private String tokenHash;
    private String tokenKind;
    private Long applicationId;
    private Long apiCredentialId;
    private Long serviceAccountId;
    private Long agentVersionId;
    private String hostOrigin;
    private String externalUserHash;
    private Integer sessionMinutes;
    private Long sessionId;
    private LocalDateTime expiresAt;
    private LocalDateTime consumedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
}
