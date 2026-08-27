package group.aitools.nhs.platform.workflow.service;

import group.aitools.nhs.platform.workflow.mapper.WorkflowCatalogMapper;
import group.aitools.nhs.platform.workflow.persistence.row.WorkflowTemplateRow;
import group.aitools.nhs.platform.workflow.web.WorkflowTemplateView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责工作流目录相关的业务编排与领域规则处理。
 */
@Service
public class WorkflowCatalogService {

    private final WorkflowCatalogMapper mapper;
    private final WorkflowGraphValidator validator;

    /**
     * 创建 {@code WorkflowCatalogService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param validator {@code validator}参数
     */
    public WorkflowCatalogService(
        WorkflowCatalogMapper mapper,
        WorkflowGraphValidator validator
    ) {
        this.mapper = mapper;
        this.validator = validator;
    }

    /**
     * 查询{@code list}列表。
     *
     * @return 符合条件的数据集合
     */
    public List<WorkflowTemplateView> list() {
        return mapper.selectPublished().stream().map(validator::validate)
            .map(WorkflowTemplateView::from).toList();
    }

    /**
     * 校验{@code Published}，并在条件不满足时终止处理。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    public WorkflowTemplate requirePublished(Long versionId) {
        WorkflowTemplateRow row = mapper.selectVersion(versionId);
        if (row == null) {
            throw new ServiceException("工作流版本不存在", HttpStatus.NOT_FOUND);
        }
        return validator.validate(row);
    }

    /**
     * 校验任务Binding，并在条件不满足时终止处理。
     *
     * @param orchestrationMode {@code orchestrationMode}参数
     * @param workflowVersionId 资源标识
     * @param primaryAgentVersionId 资源标识
     * @param requestedBindings {@code requestedBindings}参数
     * @return 处理结果
     */
    public WorkflowTaskBinding validateTaskBinding(
        String orchestrationMode,
        Long workflowVersionId,
        Long primaryAgentVersionId,
        Map<String, Long> requestedBindings
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Map<String, Long> bindings = requestedBindings == null ? Map.of() : requestedBindings;
        if (!"multi_agent_template".equals(orchestrationMode)) {
            if (workflowVersionId != null || !bindings.isEmpty()) {
                throw conflict("只有固定多智能体任务可以绑定工作流和角色Agent");
            }
            return WorkflowTaskBinding.none();
        }
        if (workflowVersionId == null) {
            throw conflict("固定多智能体任务必须绑定已发布工作流版本");
        }
        WorkflowTemplate template = requirePublished(workflowVersionId);
        Set<String> roles = template.roles().stream().map(WorkflowTemplate.WorkflowRole::key)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!bindings.keySet().equals(roles)) {
            throw conflict("工作流角色Agent必须完整且不能包含额外角色");
        }
        LinkedHashMap<String, Long> normalized = new LinkedHashMap<>();
        for (WorkflowTemplate.WorkflowRole role : template.roles()) {
            Long agentVersionId = bindings.get(role.key());
            if (agentVersionId == null || agentVersionId <= 0) {
                throw conflict("工作流角色Agent版本无效：" + role.key());
            }
            normalized.put(role.key(), agentVersionId);
        }
        String primaryRole = template.nodes().stream()
            .filter(node -> "agent".equals(node.type()))
            .findFirst().orElseThrow().role();
        if (!normalized.get(primaryRole).equals(primaryAgentVersionId)) {
            throw conflict("任务主Agent必须与工作流首个角色版本一致");
        }
        return new WorkflowTaskBinding(template, Map.copyOf(normalized));
    }

    /**
     * 处理{@code conflict}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }
}
