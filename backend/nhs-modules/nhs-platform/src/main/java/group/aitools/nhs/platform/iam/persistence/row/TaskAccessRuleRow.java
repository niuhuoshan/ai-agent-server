package group.aitools.nhs.platform.iam.persistence.row;

import lombok.Data;

/**
 * 表示任务AccessRule相关的领域对象。
 * Active task or artifact ACL row used for subject matching in Java. */
@Data
public class TaskAccessRuleRow {

    private Long id;
    private Long taskId;
    private Long artifactId;
    private String subjectType;
    private Long subjectId;
    private String subjectKey;
    private String action;
    private String effect;
}
