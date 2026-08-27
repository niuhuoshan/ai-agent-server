package group.aitools.nhs.platform.workflow.service;

import group.aitools.nhs.platform.execution.domain.AgentRunStep;

import java.util.List;

/**
 * 封装Prepared工作流Run相关的不可变数据。
 */
public record PreparedWorkflowRun(
    String authorizationJson,
    String firstRuntimeJson,
    String budgetJson,
    List<AgentRunStep> steps
) {
}
