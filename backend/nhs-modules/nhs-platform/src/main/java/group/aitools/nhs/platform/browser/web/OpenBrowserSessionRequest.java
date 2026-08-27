package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.Size;

/**
 * 封装Open浏览器会话相关的不可变数据。
 */
public record OpenBrowserSessionRequest(
    @Size(max = 128) String profileKey,
    @Size(max = 2048) String startUrl
) {
}
