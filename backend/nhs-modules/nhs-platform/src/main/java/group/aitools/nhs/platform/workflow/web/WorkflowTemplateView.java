package group.aitools.nhs.platform.workflow.web;

import group.aitools.nhs.platform.workflow.service.WorkflowNode;
import group.aitools.nhs.platform.workflow.service.WorkflowTemplate;

import java.util.List;

/**
 * 封装工作流模板相关的不可变数据。
 */
public record WorkflowTemplateView(
    Long workflowId,
    Long versionId,
    int versionNo,
    String key,
    String name,
    String contentHash,
    int maxParallelism,
    List<WorkflowTemplate.WorkflowRole> roles,
    List<WorkflowNode> nodes
) {
    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param template 模板参数
     * @return 处理结果
     */
    public static WorkflowTemplateView from(WorkflowTemplate template) {
        return new WorkflowTemplateView(
            template.workflowId(), template.versionId(), template.versionNo(), template.key(),
            template.name(), template.contentHash(), template.maxParallelism(),
            template.roles(), template.nodes()
        );
    }
}
