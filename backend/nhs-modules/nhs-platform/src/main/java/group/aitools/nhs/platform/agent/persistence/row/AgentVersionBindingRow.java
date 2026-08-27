package group.aitools.nhs.platform.agent.persistence.row;

import lombok.Data;

/**
 * 表示智能体版本Binding相关的领域对象。
 * Tool, skill or knowledge binding attached to one Agent version. */
@Data
public class AgentVersionBindingRow {

    private Long id;
    private String resourceType;
    private Long resourceId;
    private String permission;
    private String configJson;
}
