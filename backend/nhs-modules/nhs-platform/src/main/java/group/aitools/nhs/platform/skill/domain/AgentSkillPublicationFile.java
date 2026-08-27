package group.aitools.nhs.platform.skill.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 表示智能体技能Publication文件相关的领域对象。
 * Immutable file row copied into a publication request snapshot. */
@Data
@TableName("agent_skill_publication_file")
public class AgentSkillPublicationFile {

    @TableId
    private Long id;
    private Long publicationVersionId;
    private String path;
    private String fileKind;
    private String content;
    private byte[] contentBytes;
    private String contentEncoding;
    private String contentHash;
    private Integer sizeBytes;
}
