package group.aitools.nhs.platform.browser.web;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 封装浏览器Upload相关的不可变数据。
 * Worker-local upload paths; the Worker enforces its configured upload root. */
public record BrowserUploadRequest(
    @NotBlank @Size(max = 1000) String selector,
    @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 512) String> files
) {
}
