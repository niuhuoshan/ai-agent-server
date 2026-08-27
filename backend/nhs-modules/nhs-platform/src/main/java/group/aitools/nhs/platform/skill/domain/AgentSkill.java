package group.aitools.nhs.platform.skill.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体技能相关的领域对象。
 * Skill identity and ownership scope. */
@Data
@TableName("agent_skill")
public class AgentSkill {

    @TableId
    private Long id;
    private String skillKey;
    private String name;
    private String description;
    private String scopeType;
    private Long scopeId;
    private Long ownerId;
    private String status;
    private Long revisionNo;
    private Long publishedVersionId;
    private Integer publishedVersionNo;
    private String publishedContentHash;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
    private String extraJson;
}
