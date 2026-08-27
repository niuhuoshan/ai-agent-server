package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装浏览器Click相关的不可变数据。
 */
public record BrowserClickRequest(
    @NotBlank @Size(max = 1000) String selector
) {
}
