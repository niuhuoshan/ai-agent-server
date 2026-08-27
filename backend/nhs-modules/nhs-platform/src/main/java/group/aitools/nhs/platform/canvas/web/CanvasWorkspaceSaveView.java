package group.aitools.nhs.platform.canvas.web;

import java.time.LocalDateTime;

/**
 * 封装画布工作空间Save相关的不可变数据。
 */
public record CanvasWorkspaceSaveView(
    Long canvasId,
    int version,
    String path,
    String fileName,
    long contentSize,
    boolean overwritten,
    LocalDateTime savedAt
) {
}
