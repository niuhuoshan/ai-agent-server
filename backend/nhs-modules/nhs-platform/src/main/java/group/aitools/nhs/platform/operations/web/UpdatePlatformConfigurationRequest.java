package group.aitools.nhs.platform.operations.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装Update平台配置相关的不可变数据。
 */
public record UpdatePlatformConfigurationRequest(
    @NotBlank @Size(max = 128) String productName,
    @NotBlank @Size(max = 32) String productShortName,
    @Size(max = 512) String logoUrl,
    @Size(max = 512) String faviconUrl,
    @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String primaryColor,
    @NotBlank @Size(max = 64) String platformTimezone,
    @NotBlank @Pattern(regexp = "zh-CN|en-US") String defaultLocale,
    boolean watermarkEnabled,
    @NotNull @Positive Long expectedRevision,
    @NotBlank @Size(max = 500) String changeReason
) {
}
