package group.aitools.nhs.platform.skill.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 封装Create技能文件Entry相关的不可变数据。
 * Creates an empty text file or a directory marker in a draft bundle. */
public record CreateSkillFileEntryRequest(
    @NotBlank @Size(max = 512) String path,
    @NotBlank @Pattern(regexp = "file|directory") String kind
) {
}
