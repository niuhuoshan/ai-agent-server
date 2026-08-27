package group.aitools.nhs.platform.workflow.persistence.row;

import lombok.Data;

/**
 * 表示工作流RunStep相关的领域对象。
 */
@Data
public class WorkflowRunStepRow {

    private Long id;
    private Long runId;
    private String stepKey;
    private String stepType;
    private String roleKey;
    private Integer sequenceNo;
    private String status;
    private Long agentVersionId;
    private String dependsOnJson;
    private String runtimeTemplateJson;
    private String runtimeSnapshotJson;
    private String authorizationSnapshotJson;
    private String inputJson;
    private String outputSummary;
    private String outputJson;
    private String waitReason;
}
