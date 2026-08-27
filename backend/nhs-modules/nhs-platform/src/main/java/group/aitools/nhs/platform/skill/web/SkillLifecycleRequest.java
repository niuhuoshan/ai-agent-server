package group.aitools.nhs.platform.skill.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 封装技能Lifecycle相关的不可变数据。
 */
public record SkillLifecycleRequest(@NotNull @Positive Long expectedRevision) {
}
