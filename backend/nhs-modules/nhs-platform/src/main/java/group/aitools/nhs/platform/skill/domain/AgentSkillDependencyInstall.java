package group.aitools.nhs.platform.skill.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体技能DependencyInstall相关的领域对象。
 * Durable status of an explicit Skill dependency installation attempt. */
@Data
@TableName("agent_skill_dependency_install")
public class AgentSkillDependencyInstall {

    @TableId
    private Long id;
    private Long skillId;
    private Long versionId;
    private String dependencyHash;
    private String status;
    private Integer attemptNo;
    private Long requestedBy;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;
    private String installRoot;
    private String message;
}
