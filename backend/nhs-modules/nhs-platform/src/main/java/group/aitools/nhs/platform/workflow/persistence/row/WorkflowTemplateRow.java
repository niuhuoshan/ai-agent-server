package group.aitools.nhs.platform.workflow.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示工作流模板相关的领域对象。
 */
@Data
public class WorkflowTemplateRow {

    private Long workflowId;
    private String workflowKey;
    private String name;
    private String workflowType;
    private String workflowStatus;
    private Long versionId;
    private Integer versionNo;
    private String graphJson;
    private String runtimePolicyJson;
    private String contentHash;
    private String versionStatus;
    private LocalDateTime publishedAt;
}
