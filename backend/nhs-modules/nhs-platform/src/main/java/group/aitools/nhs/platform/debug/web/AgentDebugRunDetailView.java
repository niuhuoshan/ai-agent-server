package group.aitools.nhs.platform.debug.web;

import group.aitools.nhs.platform.execution.web.RunStepView;

import java.util.List;

/**
 * 封装智能体DebugRunDetail相关的不可变数据。
 * Complete debugger snapshot reconstructed from the persisted task run and events. */
public record AgentDebugRunDetailView(
    AgentDebugRunSummaryView summary,
    List<RunStepView> steps,
    AgentDebugMetricsView metrics,
    String finalOutput
) {
}
