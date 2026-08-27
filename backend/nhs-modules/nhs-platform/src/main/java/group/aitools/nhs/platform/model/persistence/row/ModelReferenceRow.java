package group.aitools.nhs.platform.model.persistence.row;

import lombok.Data;

/**
 * 表示模型Reference相关的领域对象。
 * Active or draft agent-version reference to a model registration. */
@Data
public class ModelReferenceRow {

    private Long agentId;
    private String agentName;
    private Long versionId;
    private Integer versionNo;
    private String versionStatus;
    private Boolean primaryModel;
    private Boolean synthesisModel;
}
