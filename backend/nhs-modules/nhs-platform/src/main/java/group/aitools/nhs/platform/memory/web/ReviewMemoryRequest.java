package group.aitools.nhs.platform.memory.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装Review记忆相关的不可变数据。
 */
public record ReviewMemoryRequest(
    @Positive Long expectedRevision,
    @NotBlank @Size(max = 16) String decision,
    @Size(max = 2000) String comment
) {
}
