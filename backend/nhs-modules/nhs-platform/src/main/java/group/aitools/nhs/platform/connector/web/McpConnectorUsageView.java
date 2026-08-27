package group.aitools.nhs.platform.connector.web;

import java.util.List;

/**
 * 封装Mcp连接器Usage相关的不可变数据。
 * Agent binding usage for one MCP connector, matching Nhs's Usage contract. */
public record McpConnectorUsageView(
    Long connectorId,
    int boundAgentCount,
    int activeAgentCount,
    int boundVersionCount,
    List<McpAgentUsageView> agents
) {
    /**
     * 创建 {@code McpConnectorUsageView} 实例并初始化所需依赖。
     *
     * @param connectorId 资源标识
     * @param boundAgentCount bound智能体Count参数
     * @param activeAgentCount active智能体Count参数
     * @param boundVersionCount bound版本Count参数
     * @param agents {@code agents}参数
     */
    public McpConnectorUsageView {
        agents = agents == null ? List.of() : List.copyOf(agents);
    }
}
