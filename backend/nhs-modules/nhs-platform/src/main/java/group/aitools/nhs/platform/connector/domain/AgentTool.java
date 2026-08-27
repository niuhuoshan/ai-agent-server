package group.aitools.nhs.platform.connector.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体工具相关的领域对象。
 * Immutable tool configuration version plus mutable availability controls. */
@Data
@TableName("agent_tool")
public class AgentTool {

    @TableId
    private Long id;
    private String toolKey;
    private String name;
    private String description;
    private Long connectorId;
    private String toolType;
    private String riskLevel;
    private String parameterSchemaJson;
    private String executionPolicyJson;
    private String externalName;
    private String status;
    private Integer versionNo;
    private Long discoveryId;
    private String remoteSchemaHash;
    private Boolean isAvailable;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
    private String extraJson;
    @TableField(exist = false)
    private Integer usageCount;
}
