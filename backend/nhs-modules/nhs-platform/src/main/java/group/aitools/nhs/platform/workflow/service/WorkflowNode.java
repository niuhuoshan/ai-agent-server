package group.aitools.nhs.platform.workflow.service;

import java.util.List;

/**
 * 封装工作流Node相关的不可变数据。
 */
public record WorkflowNode(
    String key,
    String type,
    String role,
    int sequence,
    List<String> dependsOn,
    String instruction
) {
}
