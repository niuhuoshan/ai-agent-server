package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 封装浏览器Fill相关的不可变数据。
 */
public record BrowserFillRequest(
    @NotBlank @Size(max = 1000) String selector,
    @NotNull @Size(max = 20000) String value
) {
}
