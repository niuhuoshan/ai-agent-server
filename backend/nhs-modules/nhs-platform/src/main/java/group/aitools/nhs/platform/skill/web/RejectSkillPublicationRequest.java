package group.aitools.nhs.platform.skill.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装Reject技能Publication相关的不可变数据。
 */
public record RejectSkillPublicationRequest(
    @NotBlank @Size(max = 2000) String comment
) {
}
