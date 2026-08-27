package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 封装浏览器Scroll相关的不可变数据。
 * Bounded browser scroll request. */
public record BrowserScrollRequest(
    @Min(-100000) @Max(100000) Integer x,
    @Min(-100000) @Max(100000) Integer y,
    @Size(max = 1000) String selector
) {
}
