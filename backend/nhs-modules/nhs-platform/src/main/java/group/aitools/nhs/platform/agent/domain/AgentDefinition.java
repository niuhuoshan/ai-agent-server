package group.aitools.nhs.platform.agent.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体定义相关的领域对象。
 * Stable Agent identity whose executable configuration lives in immutable versions. */
@Data
@TableName("agent_definition")
public class AgentDefinition {

    @TableId
    private Long id;
    private String agentKey;
    private String name;
    private String description;
    private String agentType;
    private String engineType;
    private String avatarUrl;
    private Boolean isSystem;
    private Boolean isDefault;
    private String status;
    private Long ownerId;
    private Integer sortOrder;
    private String engineConfigJson;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
    private String extraJson;

    @TableField(exist = false)
    private Long publishedVersionId;
}
