package group.aitools.nhs.platform.execution.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封装执行时间线相关的不可变数据。
 * Public projection used by all execution surfaces for historical replay. */
public record ExecutionTimelineView(
    String traceId,
    Long conversationId,
    Long taskId,
    Long runId,
    String contentHash,
    LocalDateTime generatedAt,
    boolean persisted,
    List<ExecutionEventView> items
) {

    /**
     * 创建 {@code ExecutionTimelineView} 实例并初始化所需依赖。
     *
     * @param traceId 资源标识
     * @param conversationId 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param contentHash 待处理内容
     * @param generatedAt {@code generatedAt}参数
     * @param persisted {@code persisted}参数
     * @param items {@code items}参数
     */
    public ExecutionTimelineView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
