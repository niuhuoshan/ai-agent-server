package group.aitools.nhs.platform.skill.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体技能版本相关的领域对象。
 * Immutable Skill content version; only lifecycle status may change. */
@Data
@TableName("agent_skill_version")
public class AgentSkillVersion {

    @TableId
    private Long id;
    private Long skillId;
    private Integer versionNo;
    private String content;
    private String contentHash;
    private String fileBundleHash;
    private String manifestJson;
    private String runtimeRequirementsJson;
    private String status;
    private LocalDateTime publishedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
