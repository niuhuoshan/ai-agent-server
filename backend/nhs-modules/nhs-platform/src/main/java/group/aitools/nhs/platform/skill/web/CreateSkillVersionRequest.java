package group.aitools.nhs.platform.skill.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 封装Create技能版本相关的不可变数据。
 */
public record CreateSkillVersionRequest(
    @NotBlank @Size(max = 32768) String content,
    @NotNull Map<String, Object> manifest,
    @NotNull Map<String, Object> runtimeRequirements,
    @NotNull @Positive Long expectedRevision
) {
}
