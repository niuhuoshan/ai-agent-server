package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装Navigate浏览器相关的不可变数据。
 */
public record NavigateBrowserRequest(
    @NotBlank @Size(max = 2048) String url
) {
}
