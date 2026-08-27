package group.aitools.nhs.platform.workflow.service;

import java.util.Map;

/**
 * 封装工作流任务Binding相关的不可变数据。
 */
public record WorkflowTaskBinding(
    WorkflowTemplate template,
    Map<String, Long> agentVersions
) {
    /**
     * 处理{@code none}并返回对应结果。
     *
     * @return 处理结果
     */
    public static WorkflowTaskBinding none() {
        return new WorkflowTaskBinding(null, Map.of());
    }
}
