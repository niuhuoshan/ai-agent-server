package group.aitools.nhs.platform.skill.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 封装Create技能相关的不可变数据。
 */
public record CreateSkillRequest(
    @NotBlank @Size(max = 128) String skillKey,
    @NotBlank @Size(max = 128) String name,
    @Size(max = 12000) String description,
    @NotBlank @Pattern(regexp = "system|project|user") String scopeType,
    @Positive Long scopeId,
    @NotBlank @Size(max = 32768) String content,
    @NotNull Map<String, Object> manifest,
    @NotNull Map<String, Object> runtimeRequirements
) {
}
