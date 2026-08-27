package group.aitools.nhs.platform.debug.web;

import group.aitools.nhs.platform.execution.web.TaskRunView;

import java.time.LocalDateTime;

/**
 * 封装智能体DebugRunSummary相关的不可变数据。
 * Owner-visible debug history row without frozen credentials or runtime documents. */
public record AgentDebugRunSummaryView(
    Long id,
    Long parentDebugRunId,
    Long agentId,
    String agentKey,
    String agentName,
    Long agentVersionId,
    int versionNo,
    String versionStatus,
    Long taskId,
    String input,
    String inputSha256,
    TaskRunView run,
    LocalDateTime createdAt
) {
}
