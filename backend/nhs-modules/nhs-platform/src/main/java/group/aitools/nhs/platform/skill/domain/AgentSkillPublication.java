package group.aitools.nhs.platform.skill.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体技能Publication相关的领域对象。
 * Durable publication identity for one personal Skill. */
@Data
@TableName("agent_skill_publication")
public class AgentSkillPublication {

    @TableId
    private Long id;
    private Long sourceSkillId;
    private Long sourceOwnerId;
    private Long systemSkillId;
    private Integer currentPublicVersionNo;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
