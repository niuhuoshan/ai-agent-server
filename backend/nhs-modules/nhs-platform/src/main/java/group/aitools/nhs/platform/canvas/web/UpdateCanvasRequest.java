package group.aitools.nhs.platform.canvas.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 封装Update画布相关的不可变数据。
 */
public record UpdateCanvasRequest(
    @Min(1) int expectedVersion,
    @NotBlank @Size(max = 255) String title,
    @NotBlank @Size(max = 32) String contentType,
    @NotNull String content,
    Map<String, Object> metadata
) {
}
