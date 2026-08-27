package group.aitools.nhs.platform.workflow.service;

import group.aitools.nhs.platform.agent.domain.AgentDefinitionVersion;
import group.aitools.nhs.platform.agent.persistence.row.AgentVersionBindingRow;
import group.aitools.nhs.platform.agent.service.AgentVersionContentHasher;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.domain.AgentRunStep;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.execution.persistence.row.TaskRunDefinitionRow;
import group.aitools.nhs.platform.execution.persistence.row.WorkflowAgentDefinitionRow;
import group.aitools.nhs.platform.execution.service.TaskRunSnapshotFactory;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.DecisionEvidence;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.workflow.mapper.WorkflowRunMapper;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责工作流RunPreparation相关的业务编排与领域规则处理。
 * Freezes every role, resource decision and step template before a workflow run is visible. */
@Service
public class WorkflowRunPreparationService {

    private static final int MAX_AUTHORIZATION_BYTES = 128 * 1024;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final WorkflowCatalogService catalogService;
    private final WorkflowRunMapper workflowMapper;
    private final TaskRunCommandMapper runMapper;
    private final TaskRunSnapshotFactory snapshotFactory;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final AgentVersionContentHasher agentHasher;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code WorkflowRunPreparationService} 实例并初始化所需依赖。
     *
     * @param catalogService 目录Service参数
     * @param workflowMapper 工作流Mapper参数
     * @param runMapper {@code runMapper}参数
     * @param snapshotFactory 快照Factory参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param agentHasher 智能体Hasher参数
     * @param idGenerator {@code idGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public WorkflowRunPreparationService(
        WorkflowCatalogService catalogService,
        WorkflowRunMapper workflowMapper,
        TaskRunCommandMapper runMapper,
        TaskRunSnapshotFactory snapshotFactory,
        AuthorizationEnforcer authorizationEnforcer,
        AgentVersionContentHasher agentHasher,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper
    ) {
        this.catalogService = catalogService;
        this.workflowMapper = workflowMapper;
        this.runMapper = runMapper;
        this.snapshotFactory = snapshotFactory;
        this.authorizationEnforcer = authorizationEnforcer;
        this.agentHasher = agentHasher;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code prepare}并返回对应结果。
     *
     * @param definition 定义参数
     * @param principal 当前操作主体
     * @param taskDecision 任务Decision参数
     * @param runId 资源标识
     * @param traceId 资源标识
     * @param workflowInput 工作流Input参数
     * @return 处理结果
     */
    public PreparedWorkflowRun prepare(
        TaskRunDefinitionRow definition,
        CurrentPrincipal principal,
        AuthorizationDecision taskDecision,
        Long runId,
        String traceId,
        String workflowInput
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (!"multi_agent_template".equals(definition.getOrchestrationMode())
            || definition.getWorkflowVersionId() == null) {
            throw conflict("多智能体运行缺少固定工作流版本");
        }
        WorkflowTemplate template = catalogService.requirePublished(
            definition.getWorkflowVersionId()
        );
        FrozenTaskResources taskResources = taskResources(definition, template);
        Map<String, FrozenRole> roles = freezeRoles(
            definition, template, taskResources, principal
        );
        Map<String, Long> stepIds = new LinkedHashMap<>();
        template.nodes().forEach(node -> stepIds.put(node.key(), idGenerator.nextId()));

        LocalDateTime now = LocalDateTime.now();
        List<AgentRunStep> steps = new ArrayList<>(template.nodes().size());
        String firstRuntimeJson = null;
        String budgetJson = "{}";
        for (WorkflowNode node : template.nodes()) {
            AgentRunStep step = new AgentRunStep();
            step.setId(stepIds.get(node.key()));
            step.setRunId(runId);
            step.setStepKey(node.key());
            step.setParentStepId(node.dependsOn().size() == 1
                ? stepIds.get(node.dependsOn().getFirst()) : null);
            step.setStepType(node.type());
            step.setRoleKey(node.role());
            step.setDependsOnJson(jsonMapper.writeValueAsString(node.dependsOn()));
            step.setSequenceNo(node.sequence());
            step.setStatus("pending");
            step.setCreatedAt(now);
            step.setInputSummary(summary(node.instruction()));
            step.setInputJson(jsonMapper.writeValueAsString(Map.of(
                "workflowInput", workflowInput,
                "instruction", node.instruction()
            )));
            if ("agent".equals(node.type())) {
                FrozenRole role = roles.get(node.role());
                step.setAgentVersionId(role.definition().getAgentVersionId());
                Map<String, Object> stepAuthorization = stepAuthorization(
                    principal, definition, template, node, taskDecision, role
                );
                String baseInput = workflowInput + "\n\nFixed workflow node: " + node.key()
                    + "\nRole: " + node.role() + "\nInstruction: " + node.instruction();
                TaskRunSnapshotFactory.FrozenRunSnapshot snapshot = snapshotFactory.createWorkflowStep(
                    definition, role.definition(), principal, runId, step.getId(), traceId,
                    baseInput, stepAuthorization, role.bindings(), node.key(), node.role(),
                    workflowInput, template.maxParallelism(), template.maxDependencyBytes(),
                    template.contentHash()
                );
                step.setRuntimeTemplateJson(snapshot.runtimeJson());
                step.setAuthorizationSnapshotJson(snapshot.authorizationJson());
                if (firstRuntimeJson == null) {
                    firstRuntimeJson = snapshot.runtimeJson();
                    budgetJson = snapshot.budgetJson();
                }
            } else {
                step.setAuthorizationSnapshotJson(jsonMapper.writeValueAsString(Map.of(
                    "workflowVersionId", template.versionId(),
                    "workflowContentHash", template.contentHash(),
                    "nodeKey", node.key(),
                    "nodeType", node.type()
                )));
            }
            steps.add(step);
        }
        if (firstRuntimeJson == null) {
            throw conflict("固定工作流没有可执行Agent节点");
        }
        Map<String, Object> runAuthorization = new LinkedHashMap<>();
        runAuthorization.put("principalId", principal.id());
        runAuthorization.put("principalType", principal.type().name().toLowerCase(java.util.Locale.ROOT));
        runAuthorization.put("roles", principal.roles().stream().map(PlatformRole::key).sorted().toList());
        runAuthorization.put("taskId", definition.getTaskId());
        runAuthorization.put("taskVersionId", definition.getTaskVersionId());
        runAuthorization.put("workflowVersionId", template.versionId());
        runAuthorization.put("workflowContentHash", template.contentHash());
        runAuthorization.put("workflowAgentVersions", taskResources.agentVersions());
        runAuthorization.put("taskDecision", decisionMap(taskDecision));
        runAuthorization.put("frozenAt", LocalDateTime.now().toString());
        String authorizationJson = jsonMapper.writeValueAsString(runAuthorization);
        if (authorizationJson.getBytes(StandardCharsets.UTF_8).length > MAX_AUTHORIZATION_BYTES) {
            throw new ServiceException("工作流授权快照超过128KB限制", HttpStatus.BAD_REQUEST);
        }
        return new PreparedWorkflowRun(
            authorizationJson, firstRuntimeJson, budgetJson, List.copyOf(steps)
        );
    }

    /**
     * 处理{@code freezeRoles}并返回对应结果。
     *
     * @param definition 定义参数
     * @param template 模板参数
     * @param taskResources 任务Resources参数
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private Map<String, FrozenRole> freezeRoles(
        TaskRunDefinitionRow definition,
        WorkflowTemplate template,
        FrozenTaskResources taskResources,
        CurrentPrincipal principal
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Map<String, FrozenRole> result = new LinkedHashMap<>();
        for (WorkflowTemplate.WorkflowRole role : template.roles()) {
            Long versionId = taskResources.agentVersions().get(role.key());
            WorkflowAgentDefinitionRow agent = workflowMapper.selectAgentDefinition(versionId);
            if (agent == null || agent.getAgentPublishedAt() == null
                || !Set.of("published", "archived").contains(agent.getAgentVersionStatus())
                || !"active".equals(agent.getAgentStatus())) {
                throw conflict("工作流角色绑定的Agent版本不可用：" + role.key());
            }
            List<AgentVersionBindingRow> bindings = runMapper.selectBindings(versionId);
            validateAgentHash(agent, bindings);
            AuthorizationDecision agentDecision = authorizationEnforcer.requireAllowed(
                principal, context("agent_version", versionId, "use", definition.getTaskId())
            );
            List<FrozenResourceDecision> resourceDecisions = new ArrayList<>();
            for (AgentVersionBindingRow binding : bindings) {
                String action = action(binding);
                requireTaskGrant(taskResources.grants(), binding, action);
                AuthorizationDecision decision = authorizationEnforcer.decide(
                    principal,
                    context(binding.getResourceType(), binding.getResourceId(), action, definition.getTaskId())
                );
                if (!decision.allowed() && !decision.requiresApproval()) {
                    throw new ServiceException(
                        "工作流Agent资源没有执行权限：" + binding.getResourceType() + ':'
                            + binding.getResourceId() + "（" + decision.reasonCode() + "）",
                        HttpStatus.FORBIDDEN
                    );
                }
                resourceDecisions.add(new FrozenResourceDecision(binding, action, decision));
            }
            result.put(role.key(), new FrozenRole(
                agent, List.copyOf(bindings), agentDecision, List.copyOf(resourceDecisions)
            ));
        }
        return Map.copyOf(result);
    }

    /**
     * 处理任务Resources并返回对应结果。
     *
     * @param definition 定义参数
     * @param template 模板参数
     * @return 处理结果
     */
    private FrozenTaskResources taskResources(
        TaskRunDefinitionRow definition,
        WorkflowTemplate template
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Map<String, Object> snapshot;
        try {
            snapshot = jsonMapper.readValue(definition.getTaskResourceSnapshotJson(), MAP_TYPE);
        } catch (RuntimeException exception) {
            throw conflict("任务资源快照无效");
        }
        if (!sameId(definition.getWorkflowVersionId(), snapshot.get("workflowVersionId"))) {
            throw conflict("任务资源快照的工作流版本不一致");
        }
        if (!(snapshot.get("workflowAgentVersions") instanceof Map<?, ?> rawAgents)) {
            throw conflict("任务资源快照没有冻结工作流角色Agent");
        }
        Map<String, Long> agents = new LinkedHashMap<>();
        rawAgents.forEach((key, value) -> {
            if (!(value instanceof Number number) || number.longValue() <= 0
                || number.doubleValue() != number.longValue()) {
                throw conflict("任务资源快照包含无效Agent版本");
            }
            agents.put(String.valueOf(key), number.longValue());
        });
        Set<String> requiredRoles = template.roles().stream()
            .map(WorkflowTemplate.WorkflowRole::key)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!agents.keySet().equals(requiredRoles)) {
            throw conflict("任务资源快照的工作流角色不完整");
        }
        if (!sameId(definition.getAgentVersionId(), snapshot.get("agentVersionId"))) {
            throw conflict("任务资源快照的主Agent版本不一致");
        }
        if (!(snapshot.get("resources") instanceof List<?> rawResources)) {
            throw conflict("任务资源快照没有冻结资源授权");
        }
        Set<TaskGrant> grants = new LinkedHashSet<>();
        for (Object raw : rawResources) {
            if (!(raw instanceof Map<?, ?> resource)
                || !(resource.get("resourceType") instanceof String resourceType)
                || !(resource.get("resourceId") instanceof Number resourceId)
                || !(resource.get("permission") instanceof String permission)) {
                throw conflict("任务资源快照包含无效资源");
            }
            grants.add(new TaskGrant(resourceType, resourceId.longValue(), permission));
        }
        for (Long agentVersionId : agents.values()) {
            if (!grants.contains(new TaskGrant("agent_version", agentVersionId, "use"))) {
                throw conflict("任务资源快照没有授权工作流Agent版本");
            }
        }
        return new FrozenTaskResources(Map.copyOf(agents), Set.copyOf(grants));
    }

    /**
     * 处理step授权并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param definition 定义参数
     * @param template 模板参数
     * @param node {@code node}参数
     * @param taskDecision 任务Decision参数
     * @param role 角色参数
     * @return 处理结果
     */
    private Map<String, Object> stepAuthorization(
        CurrentPrincipal principal,
        TaskRunDefinitionRow definition,
        WorkflowTemplate template,
        WorkflowNode node,
        AuthorizationDecision taskDecision,
        FrozenRole role
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("principalId", principal.id());
        result.put("principalType", principal.type().name().toLowerCase(java.util.Locale.ROOT));
        result.put("roles", principal.roles().stream().map(PlatformRole::key).sorted().toList());
        result.put("taskId", definition.getTaskId());
        result.put("taskVersionId", definition.getTaskVersionId());
        result.put("workflowVersionId", template.versionId());
        result.put("workflowContentHash", template.contentHash());
        result.put("workflowNodeKey", node.key());
        result.put("workflowRole", node.role());
        result.put("agentVersionId", role.definition().getAgentVersionId());
        result.put("taskDecision", decisionMap(taskDecision));
        result.put("agentDecision", decisionMap(role.agentDecision()));
        result.put("resourceDecisions", role.resources().stream().map(item -> Map.of(
            "resourceType", item.binding().getResourceType(),
            "resourceId", item.binding().getResourceId(),
            "action", item.action(),
            "decision", decisionMap(item.decision())
        )).toList());
        Map<String, Object> runtime = jsonMapper.readValue(
            role.definition().getAgentRuntimeConfigJson(), MAP_TYPE
        );
        result.put("workspaceAccess", runtime.getOrDefault("workspaceAccess", "none"));
        result.put("frozenAt", LocalDateTime.now().toString());
        return Map.copyOf(result);
    }

    /**
     * 校验智能体Hash，并在条件不满足时终止处理。
     *
     * @param definition 定义参数
     * @param bindings {@code bindings}参数
     */
    private void validateAgentHash(
        WorkflowAgentDefinitionRow definition,
        List<AgentVersionBindingRow> bindings
    ) {
        AgentDefinitionVersion version = new AgentDefinitionVersion();
        version.setId(definition.getAgentVersionId());
        version.setAgentId(definition.getAgentId());
        version.setSystemPrompt(definition.getSystemPrompt());
        version.setModelId(definition.getModelId());
        version.setSynthesisModelId(definition.getSynthesisModelId());
        version.setRuntimeConfigJson(definition.getAgentRuntimeConfigJson());
        version.setWelcomeConfigJson(definition.getAgentWelcomeConfigJson());
        version.setRoutingTagsJson(definition.getAgentRoutingTagsJson());
        if (!agentHasher.hash(version, bindings).equals(definition.getAgentContentHash())) {
            throw conflict("工作流Agent版本内容哈希不一致");
        }
    }

    /**
     * 校验任务Grant，并在条件不满足时终止处理。
     *
     * @param grants {@code grants}参数
     * @param binding {@code binding}参数
     * @param action {@code action}参数
     */
    private void requireTaskGrant(
        Set<TaskGrant> grants,
        AgentVersionBindingRow binding,
        String action
    ) {
        String permission = "invoke".equals(action) ? "use" : action;
        if (!grants.contains(new TaskGrant(
            binding.getResourceType(), binding.getResourceId(), permission
        )) && !grants.contains(new TaskGrant(
            binding.getResourceType(), binding.getResourceId(), "admin"
        ))) {
            throw new ServiceException(
                "任务资源快照未授权工作流Agent能力：" + binding.getResourceType() + ':'
                    + binding.getResourceId(),
                HttpStatus.FORBIDDEN
            );
        }
    }

    /**
     * 处理{@code action}并返回对应结果。
     *
     * @param binding {@code binding}参数
     * @return 处理结果
     */
    private String action(AgentVersionBindingRow binding) {
        return switch (binding.getResourceType()) {
            case "tool" -> "invoke";
            case "skill" -> "use";
            case "knowledge_base" -> "read";
            default -> throw conflict("工作流Agent版本包含未知资源类型");
        };
    }

    /**
     * 处理上下文并返回对应结果。
     *
     * @param type 业务类型
     * @param id 资源标识
     * @param action {@code action}参数
     * @param taskId 资源标识
     * @return 处理结果
     */
    private PermissionContext context(String type, Long id, String action, Long taskId) {
        return new PermissionContext(
            type, id, null, action, ResourceState.ACTIVE, false, Set.of(), taskId
        );
    }

    /**
     * 处理{@code sameId}并返回对应结果。
     *
     * @param expected {@code expected}参数
     * @param raw {@code raw}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean sameId(Long expected, Object raw) {
        return raw instanceof Number number && number.longValue() > 0
            && number.doubleValue() == number.longValue()
            && expected.longValue() == number.longValue();
    }

    /**
     * 处理{@code decisionMap}并返回对应结果。
     *
     * @param decision {@code decision}参数
     * @return 处理结果
     */
    private Map<String, Object> decisionMap(AuthorizationDecision decision) {
        return Map.of(
            "effect", decision.effect().name().toLowerCase(java.util.Locale.ROOT),
            "reasonCode", decision.reasonCode(),
            "evidence", decision.evidence().stream().map(this::evidenceMap).toList()
        );
    }

    /**
     * 处理{@code evidenceMap}并返回对应结果。
     *
     * @param evidence {@code evidence}参数
     * @return 处理结果
     */
    private Map<String, Object> evidenceMap(DecisionEvidence evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", evidence.source().name().toLowerCase(java.util.Locale.ROOT));
        value.put("sourceReference", evidence.sourceReference());
        value.put("effect", evidence.effect().name().toLowerCase(java.util.Locale.ROOT));
        value.put("reason", evidence.reason());
        return value;
    }

    /**
     * 处理{@code summary}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String summary(String value) {
        return value.length() <= 512 ? value : value.substring(0, 512);
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

    /**
     * 封装Frozen任务Resources相关的不可变数据。
     */
    private record FrozenTaskResources(Map<String, Long> agentVersions, Set<TaskGrant> grants) {
    }

    /**
     * 封装任务Grant相关的不可变数据。
     */
    private record TaskGrant(String type, Long id, String permission) {
    }

    /**
     * 封装Frozen角色相关的不可变数据。
     */
    private record FrozenRole(
        WorkflowAgentDefinitionRow definition,
        List<AgentVersionBindingRow> bindings,
        AuthorizationDecision agentDecision,
        List<FrozenResourceDecision> resources
    ) {
    }

    /**
     * 封装Frozen资源Decision相关的不可变数据。
     */
    private record FrozenResourceDecision(
        AgentVersionBindingRow binding,
        String action,
        AuthorizationDecision decision
    ) {
    }
}
