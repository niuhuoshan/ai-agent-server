package group.aitools.nhs.platform.connector.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体连接器相关的领域对象。
 * External API, MCP or search provider configuration. */
@Data
@TableName("agent_connector")
public class AgentConnector {

    @TableId
    private Long id;
    private String connectorKey;
    private String name;
    private String providerType;
    private String scopeType;
    private Long ownerId;
    private String endpointUrl;
    private String credentialRef;
    private String configJson;
    private String status;
    private LocalDateTime lastCheckAt;
    private String lastError;
    private Long revisionNo;
    private Long lastDiscoveryId;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
    private String extraJson;
}
