package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装浏览器SelectOption相关的不可变数据。
 * Selects one option from a native HTML select without exposing page internals. */
public record BrowserSelectOptionRequest(
    @NotBlank @Size(max = 1000) String selector,
    @Size(max = 255) String value,
    @Size(max = 255) String label
) {
}
