package group.aitools.nhs.platform.skill.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 封装Update技能Status相关的不可变数据。
 */
public record UpdateSkillStatusRequest(
    @NotBlank String expectedStatus,
    @NotBlank String status,
    @NotNull @Positive Long expectedRevision
) {
}
