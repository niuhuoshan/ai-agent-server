package group.aitools.nhs.platform.debug.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 封装Retry智能体DebugRun相关的不可变数据。
 * Creates one idempotent retry from a terminal debug attempt. */
public record RetryAgentDebugRunRequest(
    @NotBlank @Size(max = 96) @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey
) {
}
