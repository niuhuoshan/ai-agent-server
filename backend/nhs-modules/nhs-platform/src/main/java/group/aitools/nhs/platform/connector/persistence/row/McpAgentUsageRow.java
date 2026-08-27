package group.aitools.nhs.platform.connector.persistence.row;

import lombok.Data;

/**
 * 表示Mcp智能体Usage相关的领域对象。
 * One immutable Agent-version-to-MCP-tool binding used by the Usage summary. */
@Data
public class McpAgentUsageRow {

    private Long agentId;
    private String agentName;
    private String agentStatus;
    private Long agentVersionId;
    private String agentVersionStatus;
    private Long toolId;
    private String toolStatus;
    private Boolean toolAvailable;
}
