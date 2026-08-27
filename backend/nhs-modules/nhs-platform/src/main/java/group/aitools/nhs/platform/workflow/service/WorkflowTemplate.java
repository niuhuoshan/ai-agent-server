package group.aitools.nhs.platform.workflow.service;

import java.util.List;
import java.util.Map;

/**
 * 封装工作流相关的不可变数据。
 */
public record WorkflowTemplate(
    Long workflowId,
    Long versionId,
    int versionNo,
    String key,
    String name,
    String contentHash,
    int maxParallelism,
    int maxDependencyBytes,
    List<WorkflowRole> roles,
    List<WorkflowNode> nodes,
    Map<String, Object> runtimePolicy
) {
    /**
     * 封装工作流角色相关的不可变数据。
     */
    public record WorkflowRole(String key, String name) {
    }
}
