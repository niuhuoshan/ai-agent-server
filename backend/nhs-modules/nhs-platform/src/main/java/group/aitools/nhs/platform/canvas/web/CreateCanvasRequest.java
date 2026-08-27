package group.aitools.nhs.platform.canvas.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 封装Create画布相关的不可变数据。
 */
public record CreateCanvasRequest(
    @NotBlank @Size(max = 255) String title,
    @NotBlank @Size(max = 32) String contentType,
    @NotNull String content,
    Map<String, Object> metadata
) {
}
