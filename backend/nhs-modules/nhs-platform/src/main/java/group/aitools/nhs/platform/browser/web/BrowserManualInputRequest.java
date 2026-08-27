package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 封装浏览器ManualInput相关的不可变数据。
 * A bounded human input event forwarded to the isolated browser Worker. */
public record BrowserManualInputRequest(
    @NotBlank
    @Pattern(regexp = "mouse_click|mouse_down|mouse_move|mouse_up|key|text|scroll")
    String event,
    @DecimalMin("0") @DecimalMax("4096") Double x,
    @DecimalMin("0") @DecimalMax("4096") Double y,
    @Size(max = 64) String key,
    @Size(max = 2000) String text,
    @DecimalMin("-2000") @DecimalMax("2000") Double deltaY
) {
}
