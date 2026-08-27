package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.platform.agent.domain.AgentDefinitionVersion;
import group.aitools.nhs.platform.agent.persistence.row.AgentVersionBindingRow;
import group.aitools.nhs.platform.agent.service.AgentVersionContentHasher;
import group.aitools.nhs.platform.embed.domain.EmbedSession;
import group.aitools.nhs.platform.embed.domain.EmbedTurn;
import group.aitools.nhs.platform.embed.mapper.EmbedChatMapper;
import group.aitools.nhs.platform.embed.persistence.row.EmbedAgentRuntimeRow;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责嵌入式会话运行时快照相关的转换、解析或处理逻辑。
 */
@Component
public class EmbedRuntimeSnapshotFactory {

    private static final int MAX_INPUT_BYTES = 128 * 1024;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final EmbedChatMapper mapper;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final AgentVersionContentHasher contentHasher;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code EmbedRuntimeSnapshotFactory} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param contentHasher 待处理内容
     * @param jsonMapper {@code jsonMapper}参数
     */
    public EmbedRuntimeSnapshotFactory(
        EmbedChatMapper mapper,
        AuthorizationEnforcer authorizationEnforcer,
        AgentVersionContentHasher contentHasher,
        JsonMapper jsonMapper
    ) {
        this.mapper = mapper;
        this.authorizationEnforcer = authorizationEnforcer;
        this.contentHasher = contentHasher;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 校验{@code validate}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param agentVersionId 资源标识
     */
    public void validate(CurrentPrincipal principal, Long agentVersionId) {
        prepare(principal, agentVersionId, true);
    }

    /**
     * 校验{@code Input}，并在条件不满足时终止处理。
     *
     * @param rawInput {@code rawInput}参数
     */
    public void validateInput(String rawInput) {
        input(rawInput);
    }

    /**
     * 构建{@code build}。
     *
     * @param principal 当前操作主体
     * @param session 会话参数
     * @param turn 会话回合参数
     * @param rawInput {@code rawInput}参数
     * @return 处理结果
     */
    public AgentRunRequest build(
        CurrentPrincipal principal,
        EmbedSession session,
        EmbedTurn turn,
        String rawInput
    ) {
        return build(principal, session, turn, rawInput, List.of());
    }

    /**
     * 构建{@code build}。
     *
     * @param principal 当前操作主体
     * @param session 会话参数
     * @param turn 会话回合参数
     * @param rawInput {@code rawInput}参数
     * @param media {@code media}参数
     * @return 处理结果
     */
    public AgentRunRequest build(
        CurrentPrincipal principal,
        EmbedSession session,
        EmbedTurn turn,
        String rawInput,
        List<EmbedAttachmentService.RuntimeMedia> media
    ) {
        PreparedRuntime prepared = prepare(principal, session.getAgentVersionId(), true);
        String input = input(rawInput);
        Map<String, Object> runtime = map(prepared.definition().getRuntimeConfigJson(), "Agent运行配置");
        RuntimeModelConfig model = runtimeModel(runtime);
        int maxIterations = integer(runtime.get("maxIterations"), 12, 1, 100, "maxIterations");
        Map<String, Object> authorization = authorizationSnapshot(principal, prepared);
        Map<String, Object> attributes = new LinkedHashMap<>();
        applyEngineAttributes(runtime, attributes);
        applyModelAttributes(runtime, attributes);
        attributes.put("embedSessionId", session.getId());
        attributes.put("resourceBindings", bindingSnapshots(prepared.bindings()));
        attributes.put("taskResourceSnapshot", Map.of(
            "agentVersionId", prepared.definition().getAgentVersionId(),
            "resources", bindingSnapshots(prepared.bindings())
        ));
        attributes.put("memorySnapshot", List.of());
        attributes.put("workspaceAccess", "none");
        if (media != null && !media.isEmpty()) {
            if (!Boolean.TRUE.equals(attributes.get("modelSupportsVision"))) {
                throw conflict("当前Agent模型不支持图片理解，请切换到多模态模型后重试");
            }
            attributes.put("embedMedia", media.stream().map(item -> Map.of(
                "mimeType", item.mimeType(), "base64", item.base64()
            )).toList());
        }
        return new AgentRunRequest(
            new RuntimeExecutionKey("embed-turn-" + turn.getId(), turn.getTraceId()),
            principal.id(), session.getConversationId(), null, null, null,
            prepared.definition().getAgentVersionId(), prepared.definition().getAgentName(),
            session.getSessionKey(), input, prepared.definition().getSystemPrompt(), model,
            null, maxIterations, authorization, Map.copyOf(attributes)
        );
    }

    /**
 * 构建Human会话。
 * Builds a human conversation request while excluding resources that require a TaskRun approval. */
    public AgentRunRequest buildHumanConversation(
        CurrentPrincipal principal,
        Long conversationId,
        String sessionKey,
        Long turnId,
        String traceId,
        Long agentVersionId,
        String rawInput,
        List<Map<String, Object>> memorySnapshot
    ) {
        return buildHumanConversation(
            principal, conversationId, sessionKey, turnId, traceId, agentVersionId,
            rawInput, memorySnapshot, Map.of()
        );
    }

    /**
 * 构建Human会话。
 *
     * Builds a human conversation request with the immutable routing decision
     * captured before the runtime worker is released.
     */
    public AgentRunRequest buildHumanConversation(
        CurrentPrincipal principal,
        Long conversationId,
        String sessionKey,
        Long turnId,
        String traceId,
        Long agentVersionId,
        String rawInput,
        List<Map<String, Object>> memorySnapshot,
        Map<String, Object> routingDecision
    ) {
        if (!principal.isHuman()) {
            throw new ServiceException("平台会话只能由人员主体运行", HttpStatus.FORBIDDEN);
        }
        PreparedRuntime prepared = prepare(principal, agentVersionId, false);
        String input = input(rawInput);
        Map<String, Object> runtime = map(prepared.definition().getRuntimeConfigJson(), "Agent运行配置");
        RuntimeModelConfig model = runtimeModel(runtime);
        int maxIterations = integer(runtime.get("maxIterations"), 12, 1, 100, "maxIterations");
        List<Map<String, Object>> resources = bindingSnapshots(prepared.bindings());
        Map<String, Object> attributes = new LinkedHashMap<>();
        applyEngineAttributes(runtime, attributes);
        applyModelAttributes(runtime, attributes);
        attributes.put("conversationTurnId", turnId);
        attributes.put("resourceBindings", resources);
        attributes.put("taskResourceSnapshot", Map.of(
            "agentVersionId", prepared.definition().getAgentVersionId(),
            "resources", resources
        ));
        attributes.put("memorySnapshot", List.copyOf(memorySnapshot));
        attributes.put("workspaceAccess", "none");
        if (routingDecision != null && !routingDecision.isEmpty()) {
            Map<String, Object> frozenDecision = Map.copyOf(routingDecision);
            attributes.put("routingDecision", frozenDecision);
            attributes.put("turnDecision", frozenDecision);
        }
        return new AgentRunRequest(
            new RuntimeExecutionKey("conversation-turn-" + turnId, traceId),
            principal.id(), conversationId, null, null, null,
            prepared.definition().getAgentVersionId(), prepared.definition().getAgentName(),
            sessionKey, input, prepared.definition().getSystemPrompt(), model,
            null, maxIterations, authorizationSnapshot(principal, prepared), Map.copyOf(attributes)
        );
    }

    /**
 * 构建{@code Delegated}。
 *
     * Builds a child request which participates in the parent's task/run identity.
     * A task run is allowed to have no conversation, so delegated execution must
     * not route through {@link #buildHumanConversation} (whose identity is a
     * conversation turn).  The child still gets its own execution/trace key and
     * its target Agent's frozen resource snapshot.
     */
    public AgentRunRequest buildDelegated(
        CurrentPrincipal principal,
        AgentRunRequest parent,
        String sessionKey,
        Long turnId,
        String traceId,
        Long agentVersionId,
        String rawInput,
        List<Map<String, Object>> memorySnapshot
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (!principal.isHuman()) {
            throw new ServiceException("平台会话只能由人员主体运行", HttpStatus.FORBIDDEN);
        }
        if (parent == null) {
            throw new IllegalArgumentException("parent request must not be null");
        }
        PreparedRuntime prepared = prepare(principal, agentVersionId, false);
        String input = input(rawInput);
        Map<String, Object> runtime = map(prepared.definition().getRuntimeConfigJson(), "Agent运行配置");
        RuntimeModelConfig model = runtimeModel(runtime);
        int maxIterations = integer(runtime.get("maxIterations"), 12, 1, 100, "maxIterations");
        List<Map<String, Object>> resources = bindingSnapshots(prepared.bindings());
        Map<String, Object> attributes = new LinkedHashMap<>();
        applyEngineAttributes(runtime, attributes);
        applyModelAttributes(runtime, attributes);
        attributes.put("delegationTurnId", turnId);
        attributes.put("resourceBindings", resources);
        attributes.put("taskResourceSnapshot", Map.of(
            "agentVersionId", prepared.definition().getAgentVersionId(),
            "resources", resources
        ));
        attributes.put(
            "memorySnapshot",
            memorySnapshot == null ? List.of() : List.copyOf(memorySnapshot)
        );
        attributes.put("workspaceAccess", "none");
        return new AgentRunRequest(
            new RuntimeExecutionKey("delegated-run-" + turnId, traceId),
            principal.id(), parent.conversationId(), parent.taskId(), parent.runId(), parent.stepId(),
            prepared.definition().getAgentVersionId(), prepared.definition().getAgentName(),
            sessionKey, input, prepared.definition().getSystemPrompt(), model,
            null, maxIterations, authorizationSnapshot(principal, prepared), Map.copyOf(attributes)
        );
    }

    /**
     * 处理{@code prepare}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param agentVersionId 资源标识
     * @param embed 嵌入式会话参数
     * @return 处理结果
     */
    private PreparedRuntime prepare(
        CurrentPrincipal principal,
        Long agentVersionId,
        boolean embed
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        EmbedAgentRuntimeRow definition = mapper.selectAgentRuntime(agentVersionId);
        if (definition == null) {
            throw new ServiceException("绑定的Agent版本不存在", HttpStatus.NOT_FOUND);
        }
        if (!"active".equals(definition.getAgentStatus())
            || definition.getPublishedAt() == null
            || !Set.of("published", "archived").contains(definition.getVersionStatus())) {
            throw conflict("只能使用已发布且当前可用的Agent版本");
        }
        List<AgentVersionBindingRow> allBindings = mapper.selectBindings(agentVersionId);
        if (embed && mapper.countUnsafeEmbedTools(agentVersionId) > 0) {
            throw new ServiceException(
                "Embed不允许绑定高风险、需审批或不可用工具", HttpStatus.FORBIDDEN
            );
        }
        AgentDefinitionVersion version = new AgentDefinitionVersion();
        version.setId(definition.getAgentVersionId());
        version.setAgentId(definition.getAgentId());
        version.setSystemPrompt(definition.getSystemPrompt());
        version.setModelId(definition.getModelId());
        version.setSynthesisModelId(definition.getSynthesisModelId());
        version.setRuntimeConfigJson(definition.getRuntimeConfigJson());
        version.setWelcomeConfigJson(definition.getWelcomeConfigJson());
        version.setRoutingTagsJson(definition.getRoutingTagsJson());
        if (!contentHasher.hash(version, allBindings).equals(definition.getContentHash())) {
            throw conflict("Agent版本内容哈希不一致");
        }
        AuthorizationDecision agentDecision = authorizationEnforcer.requireAllowed(
            principal,
            context("agent_version", agentVersionId, definition.getAgentKey(), "use")
        );
        if (agentDecision.requiresApproval()) {
            throw new ServiceException("交互式会话不接受需要审批的Agent授权", HttpStatus.FORBIDDEN);
        }
        Set<Long> unsafeToolIds = embed
            ? Set.of() : Set.copyOf(mapper.selectUnsafeInteractiveToolIds(agentVersionId));
        List<AgentVersionBindingRow> selectedBindings = new ArrayList<>();
        List<ResourceDecision> resources = new ArrayList<>();
        for (AgentVersionBindingRow binding : allBindings) {
            String action = switch (binding.getResourceType()) {
                case "tool" -> "invoke";
                case "skill" -> "use";
                case "knowledge_base" -> "read";
                default -> throw conflict("Agent版本包含未知资源类型");
            };
            AuthorizationDecision decision = embed
                ? authorizationEnforcer.requireAllowed(
                    principal, context(binding.getResourceType(), binding.getResourceId(), null, action)
                )
                : authorizationEnforcer.decide(
                    principal, context(binding.getResourceType(), binding.getResourceId(), null, action)
                );
            if (embed && decision.requiresApproval()) {
                throw new ServiceException("Embed不允许绑定需要人工审批的资源", HttpStatus.FORBIDDEN);
            }
            if (!embed && (!decision.allowed() || decision.requiresApproval()
                || ("tool".equals(binding.getResourceType())
                    && unsafeToolIds.contains(binding.getResourceId())))) {
                continue;
            }
            selectedBindings.add(binding);
            resources.add(new ResourceDecision(binding, action, decision));
        }
        return new PreparedRuntime(
            definition, List.copyOf(selectedBindings), agentDecision, List.copyOf(resources)
        );
    }

    /**
     * 处理授权快照并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param prepared {@code prepared}参数
     * @return 处理结果
     */
    private Map<String, Object> authorizationSnapshot(
        CurrentPrincipal principal,
        PreparedRuntime prepared
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("principalId", principal.id());
        result.put("principalType", principal.type().name().toLowerCase(java.util.Locale.ROOT));
        result.put("roles", principal.roles().stream().map(PlatformRole::key).sorted().toList());
        result.put("agentVersionId", prepared.definition().getAgentVersionId());
        result.put("agentDecision", decision(prepared.agentDecision()));
        result.put("resourceDecisions", prepared.resources().stream().map(resource -> Map.of(
            "resourceType", resource.binding().getResourceType(),
            "resourceId", resource.binding().getResourceId(),
            "action", resource.action(),
            "decision", decision(resource.decision())
        )).toList());
        result.put("workspaceAccess", "none");
        result.put("frozenAt", LocalDateTime.now(ZoneOffset.UTC).toString());
        return Map.copyOf(result);
    }

    /**
     * 处理{@code decision}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> decision(AuthorizationDecision value) {
        return Map.of(
            "effect", value.effect().name().toLowerCase(java.util.Locale.ROOT),
            "reasonCode", value.reasonCode()
        );
    }

    /**
     * 处理{@code bindingSnapshots}并返回对应结果。
     *
     * @param bindings {@code bindings}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> bindingSnapshots(List<AgentVersionBindingRow> bindings) {
        return bindings.stream().map(binding -> Map.<String, Object>of(
            "resourceType", binding.getResourceType(),
            "resourceId", binding.getResourceId(),
            "permission", binding.getPermission(),
            "config", map(binding.getConfigJson(), "Agent资源绑定")
        )).toList();
    }

    /**
     * 处理上下文并返回对应结果。
     *
     * @param type 业务类型
     * @param id 资源标识
     * @param key {@code key}参数
     * @param action {@code action}参数
     * @return 处理结果
     */
    private PermissionContext context(
        String type,
        Long id,
        String key,
        String action
    ) {
        return new PermissionContext(
            type, id, key, action, ResourceState.ACTIVE, false, Set.of(), null
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
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Map<String, Object> options = new LinkedHashMap<>();
        if (modelSnapshot.get("reasoningConfig") != null) {
            options.putAll(nestedMap(modelSnapshot.get("reasoningConfig"), "模型推理配置"));
        }
        if (runtime.containsKey("temperature")) {
            options.put("temperature", runtime.get("temperature"));
        }
        if (runtime.containsKey("topP")) {
            options.put("topP", runtime.get("topP"));
        }
        if (modelSnapshot.get("contextSize") != null) {
            options.put("contextWindowSize", integer(
                modelSnapshot.get("contextSize"), null, 1, 10_000_000, "contextSize"
            ));
        }
        if (modelSnapshot.get("maxOutputTokens") != null) {
            options.put("maxTokens", integer(
                modelSnapshot.get("maxOutputTokens"), null, 1, 1_000_000, "maxOutputTokens"
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
     * 处理apply模型Attributes相关逻辑。
     *
     * @param runtime 运行时参数
     * @param attributes {@code attributes}参数
     */
    private void applyModelAttributes(
        Map<String, Object> runtime,
        Map<String, Object> attributes
    ) {
        Map<String, Object> snapshot = nestedMap(runtime.get("modelSnapshot"), "模型快照");
        String modelType = optionalText(snapshot.get("modelType"));
        Map<String, Object> capabilities = optionalMap(snapshot.get("capabilities"));
        boolean supportsVision = Set.of("multimodal", "vision", "image2text").contains(modelType)
            || Boolean.TRUE.equals(capabilities.get("vision"))
            || Boolean.TRUE.equals(capabilities.get("multimodal"));
        attributes.put("modelType", modelType == null ? "chat" : modelType);
        attributes.put("modelSupportsVision", supportsVision);
    }

    /**
     * 处理{@code input}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String input(String value) {
        if (value == null || value.isBlank()) {
            throw new ServiceException("消息不能为空", HttpStatus.BAD_REQUEST);
        }
        String normalized = value.strip();
        if (normalized.indexOf('\0') >= 0
            || normalized.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new ServiceException("消息包含非法字符或超过128KB", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param json {@code json}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Map<String, Object> map(String json, String label) {
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
     * 处理{@code optionalMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> optionalMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
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
     * @param fallback {@code fallback}参数
     * @param min {@code min}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private int integer(Object value, Integer fallback, int min, int max, String label) {
        if (value == null && fallback != null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw conflict(label + "不是整数");
        }
        long normalized = number.longValue();
        if (number.doubleValue() != normalized || normalized < min || normalized > max) {
            throw conflict(label + "超出允许范围");
        }
        return Math.toIntExact(normalized);
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
     * 封装Prepared运行时相关的不可变数据。
     */
    private record PreparedRuntime(
        EmbedAgentRuntimeRow definition,
        List<AgentVersionBindingRow> bindings,
        AuthorizationDecision agentDecision,
        List<ResourceDecision> resources
    ) {
    }

    /**
     * 封装资源Decision相关的不可变数据。
     */
    private record ResourceDecision(
        AgentVersionBindingRow binding,
        String action,
        AuthorizationDecision decision
    ) {
    }
}
