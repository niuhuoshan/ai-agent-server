package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封装浏览器Drag相关的不可变数据。
 * Moves one bounded CSS target to another inside the isolated Worker page. */
public record BrowserDragRequest(
    @NotBlank @Size(max = 1000) String sourceSelector,
    @NotBlank @Size(max = 1000) String targetSelector
) {
}
