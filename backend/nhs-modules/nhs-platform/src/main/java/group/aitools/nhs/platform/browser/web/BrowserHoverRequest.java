package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装浏览器Hover相关的不可变数据。
 * CSS selector used for a guarded hover operation. */
public record BrowserHoverRequest(
    @NotBlank @Size(max = 1000) String selector
) {
}
