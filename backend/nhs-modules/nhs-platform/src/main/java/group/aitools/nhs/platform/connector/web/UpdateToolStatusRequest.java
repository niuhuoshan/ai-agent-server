package group.aitools.nhs.platform.connector.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 封装Update工具Status相关的不可变数据。
 */
public record UpdateToolStatusRequest(
    @NotBlank @Pattern(regexp = "active|disabled|deprecated") String expectedStatus,
    @NotBlank @Pattern(regexp = "active|disabled|deprecated") String status
) {
}
