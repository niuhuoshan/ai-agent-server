package group.aitools.nhs.platform.skill.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装Update技能相关的不可变数据。
 */
public record UpdateSkillRequest(
    @NotBlank @Size(max = 128) String name,
    @Size(max = 12000) String description,
    @NotNull @Positive Long expectedRevision
) {
}
