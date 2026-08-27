package group.aitools.nhs.platform.skill.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 封装技能Publication文件Node相关的不可变数据。
 * Nhs-compatible immutable snapshot tree node. */
public record SkillPublicationFileNode(
    String name,
    String path,
    @JsonProperty("is_dir") boolean directory,
    Integer size,
    List<SkillPublicationFileNode> children
) {
}
