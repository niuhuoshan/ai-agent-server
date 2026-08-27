package group.aitools.nhs.platform.artifact.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体验收相关的领域对象。
 * Append-only decision over one run and its immutable artifact versions. */
@Data
public class AgentAcceptanceRecord {

    private Long id;
    private Long taskId;
    private Long runId;
    private String artifactIdsJson;
    private String acceptanceType;
    private String result;
    private String ruleResultJson;
    private String comment;
    private Long reviewerId;
    private String reviewerPrincipalType;
    private Integer reworkNo;
    private LocalDateTime createdAt;
    private String idempotencyKeyHash;
    private String requestHash;
}
