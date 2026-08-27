package group.aitools.nhs.platform.agent.persistence.row;

import lombok.Data;

/**
 * 表示智能体资源快照相关的领域对象。
 * Validated active resource plus the configuration frozen into a version binding. */
@Data
public class AgentResourceSnapshotRow {

    private Long id;
    private String status;
    private String snapshotJson;
}
