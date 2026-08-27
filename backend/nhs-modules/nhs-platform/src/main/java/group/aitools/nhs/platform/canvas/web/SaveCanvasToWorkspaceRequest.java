package group.aitools.nhs.platform.canvas.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 封装Save画布To工作空间相关的不可变数据。
 */
public record SaveCanvasToWorkspaceRequest(
    @Size(max = 512) String path,
    boolean overwrite,
    @Min(1) int expectedVersion
) {
}
