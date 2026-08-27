package group.aitools.nhs.platform.artifact.persistence.row;

import lombok.Data;

/**
 * 表示制品任务相关的领域对象。
 * Task context required for artifact visibility and registration. */
@Data
public class ArtifactTaskRow {

    private Long taskId;
    private Long projectId;
    private Long latestRunId;
    private String taskStatus;
    private String visibility;
}
