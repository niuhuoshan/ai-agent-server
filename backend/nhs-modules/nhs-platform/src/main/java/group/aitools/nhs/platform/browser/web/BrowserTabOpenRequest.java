package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.Size;

/**
 * 封装浏览器TabOpen相关的不可变数据。
 * Optional URL for a newly created browser tab. */
public record BrowserTabOpenRequest(
    @Size(max = 2048) String url
) {
}
