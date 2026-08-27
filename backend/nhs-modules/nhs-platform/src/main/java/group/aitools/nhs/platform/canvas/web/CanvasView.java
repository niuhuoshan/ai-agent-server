package group.aitools.nhs.platform.canvas.web;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装画布相关的不可变数据。
 */
public record CanvasView(
    Long id,
    Long conversationId,
    String title,
    String contentType,
    String content,
    Map<String, Object> metadata,
    String workspacePath,
    Long sourceMessageId,
    int currentVersion,
    int revision,
    long contentSize,
    String contentSha256,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
