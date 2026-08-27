package group.aitools.nhs.platform.skill.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体技能Publication版本相关的领域对象。
 * Immutable metadata and lifecycle record for one publication request. */
@Data
@TableName("agent_skill_publication_version")
public class AgentSkillPublicationVersion {

    @TableId
    private Long id;
    private Long publicationId;
    private Integer versionNo;
    private Long sourceSkillVersionId;
    private String sourceSkillKeySnapshot;
    private String nameSnapshot;
    private String descriptionSnapshot;
    private String contentSnapshot;
    private String manifestJson;
    private String runtimeRequirementsJson;
    private String status;
    private String contentHash;
    private String fileBundleHash;
    private Integer fileCount;
    private Long totalSizeBytes;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewComment;
    private Long withdrawnBy;
    private LocalDateTime withdrawnAt;
    private Long publishedSystemSkillId;
    private Long publishedSystemVersionId;
}
