package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 封装浏览器Wait相关的不可变数据。
 * Bounded condition used by the browser runtime before reading a fresh page state. */
public record BrowserWaitRequest(
    @Pattern(regexp = "text|url|target|page_state") String condition,
    @Size(max = 2048) String value,
    @Min(100) @Max(30000) Integer timeoutMs
) {
}
