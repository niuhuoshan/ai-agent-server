package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.platform.agent.persistence.row.AgentVersionBindingRow;
import group.aitools.nhs.platform.execution.persistence.row.TaskRunDefinitionRow;
import group.aitools.nhs.platform.execution.persistence.row.WorkflowAgentDefinitionRow;
import group.aitools.nhs.platform.memory.service.MemoryRuntimeSnapshotService;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责任务Run快照相关的转换、解析或处理逻辑。
 * Builds and bounds the exact runtime request persisted before a worker may claim the run. */
@Component
public class TaskRunSnapshotFactory {

    private static final int MAX_RUNTIME_BYTES = 256 * 1024;
    private static final int MAX_AUTHORIZATION_BYTES = 128 * 1024;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;
    private final MemoryRuntimeSnapshotService memorySnapshotService;

    /**
     * 创建 {@code TaskRunSnapshotFactory} 实例并初始化所需依赖。
     *
     * @param jsonMapper {@code jsonMapper}参数
     */
    public TaskRunSnapshotFactory(JsonMapper jsonMapper) {
        this(jsonMapper, null);
    }

    /**
     * 创建 {@code TaskRunSnapshotFactory} 实例并初始化所需依赖。
     *
     * @param jsonMapper {@code jsonMapper}参数
     * @param memorySnapshotService 记忆快照Service参数
     */
    @Autowired
    public TaskRunSnapshotFactory(
        JsonMapper jsonMapper,
        MemoryRuntimeSnapshotService memorySnapshotService
    ) {
        this.jsonMapper = jsonMapper;
        this.memorySnapshotService = memorySnapshotService;
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param definition 定义参数
     * @param principal 当前操作主体
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param traceId 资源标识
     * @param input {@code input}参数
     * @param authorizationSnapshot 授权快照参数
     * @param bindings {@code bindings}参数
     * @return 处理结果
     */
    public FrozenRunSnapshot create(
        TaskRunDefinitionRow definition,
        CurrentPrincipal principal,
        Long runId,
        Long stepId,
        String traceId,
        String input,
        Map<String, Object> authorizationSnapshot,
        List<AgentVersionBindingRow> bindings
    ) {
        return createSnapshot(
            definition, agent(definition), principal, runId, stepId, traceId, input,
            authorizationSnapshot, bindings, "task-run-" + runId, "task-run-" + runId,
            "run-" + runId, Map.of()
        );
    }

    /**
     * 创建并保存工作流Step。
     *
     * @param definition 定义参数
     * @param agent 智能体参数
     * @param principal 当前操作主体
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param traceId 资源标识
     * @param input {@code input}参数
     * @param authorizationSnapshot 授权快照参数
     * @param bindings {@code bindings}参数
     * @param nodeKey {@code nodeKey}参数
     * @param roleKey 角色Key参数
     * @param workflowInput 工作流Input参数
     * @param workflowMaxParallelism 工作流MaxParallelism参数
     * @param maxDependencyBytes {@code maxDependencyBytes}参数
     * @param workflowContentHash 待处理内容
     * @return 处理结果
     */
    public FrozenRunSnapshot createWorkflowStep(
        TaskRunDefinitionRow definition,
        WorkflowAgentDefinitionRow agent,
        CurrentPrincipal principal,
        Long runId,
        Long stepId,
        String traceId,
        String input,
        Map<String, Object> authorizationSnapshot,
        List<AgentVersionBindingRow> bindings,
        String nodeKey,
        String roleKey,
        String workflowInput,
        int workflowMaxParallelism,
        int maxDependencyBytes,
        String workflowContentHash
    ) {
        Map<String, Object> workflowAttributes = new LinkedHashMap<>();
        workflowAttributes.put("workflowVersionId", definition.getWorkflowVersionId());
        workflowAttributes.put("workflowNodeKey", nodeKey);
        workflowAttributes.put("workflowRole", roleKey);
        workflowAttributes.put("workflowInput", workflowInput);
        workflowAttributes.put("workflowMaxParallelism", workflowMaxParallelism);
        workflowAttributes.put("workflowMaxDependencyBytes", maxDependencyBytes);
        workflowAttributes.put("workflowContentHash", workflowContentHash);
        return createSnapshot(
            definition, agent(agent), principal, runId, stepId, traceId, input,
            authorizationSnapshot, bindings, "task-run-" + runId + "-step-" + stepId,
            "task-run-" + runId + "-step-" + stepId, "run-" + runId,
            workflowAttributes
        );
    }

    /**
     * 创建并保存快照。
     *
     * @param definition 定义参数
     * @param agent 智能体参数
     * @param principal 当前操作主体
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param traceId 资源标识
     * @param input {@code input}参数
     * @param authorizationSnapshot 授权快照参数
     * @param bindings {@code bindings}参数
     * @param executionId 资源标识
     * @param sessionId 资源标识
     * @param workspaceKey 工作空间Key参数
     * @param extraAttributes {@code extraAttributes}参数
     * @return 处理结果
     */
    private FrozenRunSnapshot createSnapshot(
        TaskRunDefinitionRow definition,
        AgentSnapshot agent,
        CurrentPrincipal principal,
        Long runId,
        Long stepId,
        String traceId,
        String input,
        Map<String, Object> authorizationSnapshot,
        List<AgentVersionBindingRow> bindings,
        String executionId,
        String sessionId,
        String workspaceKey,
        Map<String, Object> extraAttributes
    ) {
        Map<String, Object> runtime = parseMap(agent.runtimeConfigJson(), "Agent运行配置");
        RuntimeModelConfig model = runtimeModel(runtime);
        int maxIterations = integer(runtime.get("maxIterations"), 12, 1, 100, "maxIterations");

        Map<String, Object> attributes = new LinkedHashMap<>();
        applyEngineAttributes(runtime, attributes);
        attributes.put("taskVersionId", definition.getTaskVersionId());
        attributes.put("projectId", definition.getProjectId());
        attributes.put("taskContentHash", definition.getTaskContentHash());
        attributes.put("agentContentHash", agent.contentHash());
        attributes.put("taskResourceSnapshot", parseMap(
            definition.getTaskResourceSnapshotJson(), "任务资源快照"
        ));
        attributes.put("resourceBindings", bindingSnapshots(bindings));
        attributes.put("acceptanceSnapshot", parseMap(
            definition.getTaskAcceptanceSnapshotJson(), "验收快照"
        ));
        attributes.put(
            "memorySnapshot",
            memorySnapshotService == null
                ? List.of() : memorySnapshotService.snapshot(principal, definition)
        );
        attributes.putAll(extraAttributes);

        AgentRunRequest request = new AgentRunRequest(
            new RuntimeExecutionKey(executionId, traceId),
            principal.id(),
            definition.getSourceConversationId(),
            definition.getTaskId(),
            runId,
            stepId,
            agent.versionId(),
            agent.name(),
            sessionId,
            input,
            agent.systemPrompt(),
            model,
            workspaceKey,
            maxIterations,
            authorizationSnapshot,
            attributes
        );
        String runtimeJson = boundedJson(request, MAX_RUNTIME_BYTES, "运行快照");
        String authorizationJson = boundedJson(
            authorizationSnapshot, MAX_AUTHORIZATION_BYTES, "授权快照"
        );
        String budgetJson = boundedJson(
            parseMap(definition.getTaskBudgetJson(), "预算快照"),
            64 * 1024,
            "预算快照"
        );
        return new FrozenRunSnapshot(request, runtimeJson, authorizationJson, budgetJson);
    }

    /**
     * 处理智能体并返回对应结果。
     *
     * @param definition 定义参数
     * @return 处理结果
     */
    private AgentSnapshot agent(TaskRunDefinitionRow definition) {
        return new AgentSnapshot(
            definition.getAgentVersionId(), definition.getAgentName(), definition.getSystemPrompt(),
            definition.getAgentRuntimeConfigJson(), definition.getAgentContentHash()
        );
    }

    /**
     * 处理智能体并返回对应结果。
     *
     * @param definition 定义参数
     * @return 处理结果
     */
    private AgentSnapshot agent(WorkflowAgentDefinitionRow definition) {
        return new AgentSnapshot(
            definition.getAgentVersionId(), definition.getAgentName(), definition.getSystemPrompt(),
            definition.getAgentRuntimeConfigJson(), definition.getAgentContentHash()
        );
    }

    /**
     * 处理模型Options并返回对应结果。
     *
     * @param runtime 运行时参数
     * @param modelSnapshot 模型快照参数
     * @return 处理结果
     */
    private Map<String, Object> modelOptions(
        Map<String, Object> runtime,
        Map<String, Object> modelSnapshot
    ) {
        Map<String, Object> options = new LinkedHashMap<>();
        Object reasoning = modelSnapshot.get("reasoningConfig");
        if (reasoning != null) {
            options.putAll(nestedMap(reasoning, "模型推理配置"));
        }
        copyIfPresent(runtime, options, "temperature");
        copyIfPresent(runtime, options, "topP");
        Object contextSize = modelSnapshot.get("contextSize");
        if (contextSize != null) {
            options.put("contextWindowSize", integer(
                contextSize, null, 1, 10_000_000, "contextSize"
            ));
        }
        Object maxOutputTokens = modelSnapshot.get("maxOutputTokens");
        if (maxOutputTokens != null) {
            options.put("maxTokens", integer(
                maxOutputTokens, null, 1, 1_000_000, "maxOutputTokens"
            ));
        }
        return Map.copyOf(options);
    }

    /**
     * 执行time模型相关的处理流程。
     *
     * @param runtime 运行时参数
     * @return 处理结果
     */
    private RuntimeModelConfig runtimeModel(Map<String, Object> runtime) {
        Map<String, Object> modelSnapshot = nestedMap(runtime.get("modelSnapshot"), "模型快照");
        return new RuntimeModelConfig(
            requiredText(modelSnapshot, "provider"),
            requiredText(modelSnapshot, "modelName"),
            optionalText(modelSnapshot.get("endpointUrl")),
            requiredText(modelSnapshot, "credentialRef"),
            modelOptions(runtime, modelSnapshot)
        );
    }

    /**
     * 处理{@code bindingSnapshots}并返回对应结果。
     *
     * @param bindings {@code bindings}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> bindingSnapshots(List<AgentVersionBindingRow> bindings) {
        List<Map<String, Object>> result = new ArrayList<>(bindings.size());
        for (AgentVersionBindingRow binding : bindings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("resourceType", binding.getResourceType());
            item.put("resourceId", binding.getResourceId());
            item.put("permission", binding.getPermission());
            item.put("config", parseMap(binding.getConfigJson(), "Agent资源绑定"));
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code copyIfPresent}相关逻辑。
     *
     * @param source 数据源参数
     * @param target {@code target}参数
     * @param key {@code key}参数
     */
    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    /**
     * 处理{@code applyEngineAttributes}相关逻辑。
     *
     * @param runtime 运行时参数
     * @param attributes {@code attributes}参数
     */
    private void applyEngineAttributes(
        Map<String, Object> runtime,
        Map<String, Object> attributes
    ) {
        String engineType = optionalText(runtime.get("engineType"));
        if (engineType == null) engineType = "agentscope_java";
        if (!"agentscope_java".equals(engineType)) {
            throw conflict("Agent版本包含未知执行引擎");
        }
        attributes.put("engineType", engineType);
    }

    /**
     * 处理{@code parseMap}并返回对应结果。
     *
     * @param json {@code json}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Map<String, Object> parseMap(String json, String label) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = jsonMapper.readValue(json, MAP_TYPE);
            return value == null ? Map.of() : value;
        } catch (RuntimeException exception) {
            throw conflict(label + "无效");
        }
    }

    /**
     * 处理{@code nestedMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Map<String, Object> nestedMap(Object value, String label) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw conflict(label + "缺失或格式无效");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param source 数据源参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private String requiredText(Map<String, Object> source, String key) {
        String value = optionalText(source.get(key));
        if (value == null) {
            throw conflict("模型快照缺少" + key);
        }
        return value;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String optionalText(Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    /**
     * 处理{@code integer}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param defaultValue {@code defaultValue}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private int integer(Object value, Integer defaultValue, int minimum, int maximum, String label) {
        if (value == null && defaultValue != null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw conflict(label + "不是整数");
        }
        long normalized = number.longValue();
        if (number.doubleValue() != normalized || normalized < minimum || normalized > maximum) {
            throw conflict(label + "超出允许范围");
        }
        return Math.toIntExact(normalized);
    }

    /**
     * 处理{@code boundedJson}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maximumBytes {@code maximumBytes}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String boundedJson(Object value, int maximumBytes, String label) {
        String json = jsonMapper.writeValueAsString(value);
        if (json.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new ServiceException(label + "超过大小限制", HttpStatus.BAD_REQUEST);
        }
        return json;
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
     * 封装FrozenRun快照相关的不可变数据。
     */
    public record FrozenRunSnapshot(
        AgentRunRequest request,
        String runtimeJson,
        String authorizationJson,
        String budgetJson
    ) {
    }

    /**
     * 封装智能体快照相关的不可变数据。
     */
    private record AgentSnapshot(
        Long versionId,
        String name,
        String systemPrompt,
        String runtimeConfigJson,
        String contentHash
    ) {
    }
}
