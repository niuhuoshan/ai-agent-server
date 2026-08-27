package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装浏览器Download相关的不可变数据。
 * Clicks a download target; the server returns a short-lived capability link. */
public record BrowserDownloadRequest(
    @NotBlank @Size(max = 1000) String selector
) {
}
