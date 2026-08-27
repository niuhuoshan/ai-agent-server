package group.aitools.nhs.platform.execution.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体RunStep相关的领域对象。
 * First-phase run step; one single-agent task starts with one immutable agent step. */
@Data
public class AgentRunStep {

    private Long id;
    private Long runId;
    private String stepKey;
    private Long parentStepId;
    private String stepType;
    private String roleKey;
    private String dependsOnJson;
    private Integer sequenceNo;
    private String status;
    private Long agentVersionId;
    private Long toolId;
    private String inputSummary;
    private String inputJson;
    private String outputSummary;
    private String outputJson;
    private String runtimeTemplateJson;
    private String runtimeSnapshotJson;
    private String authorizationSnapshotJson;
    private String waitReason;
    private String errorCode;
    private String errorSummary;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer retryCount;
    private LocalDateTime createdAt;
}
