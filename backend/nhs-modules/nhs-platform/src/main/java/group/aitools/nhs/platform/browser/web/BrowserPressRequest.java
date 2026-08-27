package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装浏览器Press相关的不可变数据。
 * A bounded Playwright keyboard key or combination (for example Enter or Control+L). */
public record BrowserPressRequest(
    @NotBlank @Size(max = 64) String key
) {
}
