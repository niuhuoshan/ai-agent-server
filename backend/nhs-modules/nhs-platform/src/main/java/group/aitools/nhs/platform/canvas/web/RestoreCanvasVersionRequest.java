package group.aitools.nhs.platform.canvas.web;

import jakarta.validation.constraints.Min;

/**
 * 封装Restore画布版本相关的不可变数据。
 */
public record RestoreCanvasVersionRequest(@Min(1) int expectedVersion) {
}
