package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.Size;

/**
 * 封装浏览器Handoff相关的不可变数据。
 * Non-sensitive reason shown to the user when an Agent needs browser help. */
public record BrowserHandoffRequest(
    @Size(max = 255) String reason
) {
}
