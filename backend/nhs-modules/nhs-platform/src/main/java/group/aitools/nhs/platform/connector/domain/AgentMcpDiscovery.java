package group.aitools.nhs.platform.connector.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体McpDiscovery相关的领域对象。
 * One immutable attempt to discover an MCP server's advertised tools. */
@Data
@TableName("agent_mcp_discovery")
public class AgentMcpDiscovery {

    @TableId
    private Long id;
    private Long connectorId;
    private Long connectorRevision;
    private String status;
    private String protocolVersion;
    private String serverInfoJson;
    private Integer toolCount;
    private String contentHash;
    private String errorSummary;
    private Long startedBy;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
