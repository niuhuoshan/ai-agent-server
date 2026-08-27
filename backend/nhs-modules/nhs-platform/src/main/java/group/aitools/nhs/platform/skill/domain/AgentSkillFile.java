package group.aitools.nhs.platform.skill.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体技能文件相关的领域对象。
 * One file (text or binary) in a Skill version bundle. */
@Data
public class AgentSkillFile {
    private Long id;
    private Long skillId;
    private Long versionId;
    private String path;
    private String fileKind;
    private String content;
    private byte[] contentBytes;
    private String contentEncoding;
    private String contentHash;
    private Integer sizeBytes;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private String delFlag;
}
