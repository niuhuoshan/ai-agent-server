package group.aitools.nhs.platform.skill.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 封装Put技能文件相关的不可变数据。
 * Text file update for one draft Skill version. */
public record PutSkillFileRequest(
    @NotBlank @Size(max = 512) String path,
    @NotNull @Size(max = 5 * 1024 * 1024) String content
) {
}
