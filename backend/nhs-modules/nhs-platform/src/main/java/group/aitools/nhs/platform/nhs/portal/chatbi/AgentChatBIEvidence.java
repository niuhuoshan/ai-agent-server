package group.aitools.nhs.platform.nhs.portal.chatbi;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体对话BIEvidence相关的领域对象。
 * Persisted evidence receipt issued only for a successful immutable ChatBI result. */
@Data
public class AgentChatBIEvidence {
    private Long id;
    private Long queryId;
    private Long ownerId;
    private Long conversationId;
    private String traceId;
    private Long datasetId;
    private String evidenceType;
    private String producer;
    private String payloadDigest;
    private String resultHash;
    private String sourceRef;
    private String resultStatus;
    private String freshness;
    private LocalDateTime observedAt;
    private LocalDateTime sourceAsOf;
    private LocalDateTime expiresAt;
    private String permissionSnapshotJson;
    private String detailJson;
    private LocalDateTime createdAt;
}
