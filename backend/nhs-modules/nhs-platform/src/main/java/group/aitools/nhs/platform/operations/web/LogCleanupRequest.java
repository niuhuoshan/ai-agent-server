package group.aitools.nhs.platform.operations.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装{@code LogCleanup}相关的不可变数据。
 */
public record LogCleanupRequest(
    @NotBlank @Size(max = 128) String confirmationToken,
    @AssertTrue(message = "必须明确确认日志清理") boolean confirm
) {
}
