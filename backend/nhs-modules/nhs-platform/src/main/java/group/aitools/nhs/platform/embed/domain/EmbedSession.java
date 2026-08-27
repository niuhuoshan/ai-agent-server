package group.aitools.nhs.platform.embed.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示嵌入式会话会话相关的领域对象。
 */
@Data
public class EmbedSession {
    private Long id;
    private String sessionKey;
    private Long applicationId;
    private Long serviceAccountId;
    private Long agentVersionId;
    private Long conversationId;
    private String externalUserHash;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
}
