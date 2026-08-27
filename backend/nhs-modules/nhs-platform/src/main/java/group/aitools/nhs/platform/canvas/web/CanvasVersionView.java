package group.aitools.nhs.platform.canvas.web;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装画布版本相关的不可变数据。
 */
public record CanvasVersionView(
    Long id,
    Long canvasId,
    int versionNo,
    String title,
    String contentType,
    String content,
    Map<String, Object> metadata,
    String workspacePath,
    long contentSize,
    String contentSha256,
    String changeType,
    Integer sourceVersionNo,
    Long createdBy,
    LocalDateTime createdAt
) {
}
