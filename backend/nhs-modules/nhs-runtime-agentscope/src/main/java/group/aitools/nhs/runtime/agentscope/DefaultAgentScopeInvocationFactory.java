package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequestResolver;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.runtime.spi.RuntimeKnowledgeDefinition;
import group.aitools.nhs.runtime.spi.RuntimeKnowledgeProvider;
import group.aitools.nhs.runtime.spi.RuntimeMemoryDefinition;
import group.aitools.nhs.runtime.spi.RuntimeMemoryProvider;
import group.aitools.nhs.runtime.spi.RuntimeToolDefinition;
import group.aitools.nhs.runtime.spi.RuntimeToolProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 负责Default智能体范围调用相关的转换、解析或处理逻辑。
 * Builds a HarnessAgent from a frozen platform request using fail-closed runtime defaults. */
public final class DefaultAgentScopeInvocationFactory implements AgentScopeInvocationFactory {

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("openai", "openai-compatible");
    private static final Set<String> SUPPORTED_OPTIONS = Set.of(
        "temperature",
        "topP",
        "maxTokens",
        "maxCompletionTokens",
        "frequencyPenalty",
        "presencePenalty",
        "thinkingBudget",
        "reasoningEffort",
        "parallelToolCalls",
        "contextWindowSize",
        "nativeStructuredOutput",
        "nativeStructuredOutputWithTools",
        "endpointPath"
    );

    private final RuntimeCredentialResolver credentialResolver;
    private final AgentRunRequestResolver runRequestResolver;
    private final AgentStateStore stateStore;
    private final AgentScopeWorkspaceResolver workspaceResolver;
    private final RuntimeToolProvider toolProvider;
    private final RuntimeKnowledgeProvider knowledgeProvider;
    private final RuntimeMemoryProvider memoryProvider;
    private final ObjectMapper objectMapper;
    private final int maxWorkspaceFileSizeMb;
    private final boolean allowInsecureModelEndpoints;

    /**
     * 创建 {@code DefaultAgentScopeInvocationFactory} 实例并初始化所需依赖。
     *
     * @param credentialResolver 凭据Resolver参数
     * @param runRequestResolver {@code runRequestResolver}参数
     * @param stateStore {@code stateStore}参数
     * @param workspaceResolver 工作空间Resolver参数
     * @param toolProvider 工具提供方参数
     * @param knowledgeProvider 知识库提供方参数
     * @param memoryProvider 记忆提供方参数
     * @param objectMapper {@code objectMapper}参数
     * @param maxWorkspaceFileSizeMb 数量上限
     * @param allowInsecureModelEndpoints allowInsecure模型Endpoints参数
     */
    public DefaultAgentScopeInvocationFactory(
        RuntimeCredentialResolver credentialResolver,
        AgentRunRequestResolver runRequestResolver,
        AgentStateStore stateStore,
        AgentScopeWorkspaceResolver workspaceResolver,
        RuntimeToolProvider toolProvider,
        RuntimeKnowledgeProvider knowledgeProvider,
        RuntimeMemoryProvider memoryProvider,
        ObjectMapper objectMapper,
        int maxWorkspaceFileSizeMb,
        boolean allowInsecureModelEndpoints
    ) {
        this.credentialResolver = Objects.requireNonNull(
            credentialResolver, "credentialResolver must not be null"
        );
        this.runRequestResolver = Objects.requireNonNull(
            runRequestResolver, "runRequestResolver must not be null"
        );
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.workspaceResolver = Objects.requireNonNull(
            workspaceResolver, "workspaceResolver must not be null"
        );
        this.toolProvider = Objects.requireNonNull(toolProvider, "toolProvider must not be null");
        this.knowledgeProvider = Objects.requireNonNull(
            knowledgeProvider, "knowledgeProvider must not be null"
        );
        this.memoryProvider = Objects.requireNonNull(
            memoryProvider, "memoryProvider must not be null"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        if (maxWorkspaceFileSizeMb < 1 || maxWorkspaceFileSizeMb > 1024) {
            throw new IllegalArgumentException("maxWorkspaceFileSizeMb must be between 1 and 1024");
        }
        this.maxWorkspaceFileSizeMb = maxWorkspaceFileSizeMb;
        this.allowInsecureModelEndpoints = allowInsecureModelEndpoints;
    }

    /**
     * 创建 {@code DefaultAgentScopeInvocationFactory} 实例并初始化所需依赖。
     *
     * @param credentialResolver 凭据Resolver参数
     * @param runRequestResolver {@code runRequestResolver}参数
     * @param stateStore {@code stateStore}参数
     * @param workspaceResolver 工作空间Resolver参数
     * @param toolProvider 工具提供方参数
     * @param knowledgeProvider 知识库提供方参数
     * @param objectMapper {@code objectMapper}参数
     * @param maxWorkspaceFileSizeMb 数量上限
     * @param allowInsecureModelEndpoints allowInsecure模型Endpoints参数
     */
    public DefaultAgentScopeInvocationFactory(
        RuntimeCredentialResolver credentialResolver,
        AgentRunRequestResolver runRequestResolver,
        AgentStateStore stateStore,
        AgentScopeWorkspaceResolver workspaceResolver,
        RuntimeToolProvider toolProvider,
        RuntimeKnowledgeProvider knowledgeProvider,
        ObjectMapper objectMapper,
        int maxWorkspaceFileSizeMb,
        boolean allowInsecureModelEndpoints
    ) {
        this(
            credentialResolver, runRequestResolver, stateStore, workspaceResolver,
            toolProvider, knowledgeProvider, RuntimeMemoryProvider.empty(), objectMapper,
            maxWorkspaceFileSizeMb, allowInsecureModelEndpoints
        );
    }

    /**
     * 创建 {@code DefaultAgentScopeInvocationFactory} 实例并初始化所需依赖。
     *
     * @param credentialResolver 凭据Resolver参数
     * @param runRequestResolver {@code runRequestResolver}参数
     * @param stateStore {@code stateStore}参数
     * @param workspaceResolver 工作空间Resolver参数
     * @param toolProvider 工具提供方参数
     * @param objectMapper {@code objectMapper}参数
     * @param maxWorkspaceFileSizeMb 数量上限
     * @param allowInsecureModelEndpoints allowInsecure模型Endpoints参数
     */
    public DefaultAgentScopeInvocationFactory(
        RuntimeCredentialResolver credentialResolver,
        AgentRunRequestResolver runRequestResolver,
        AgentStateStore stateStore,
        AgentScopeWorkspaceResolver workspaceResolver,
        RuntimeToolProvider toolProvider,
        ObjectMapper objectMapper,
        int maxWorkspaceFileSizeMb,
        boolean allowInsecureModelEndpoints
    ) {
        this(
            credentialResolver, runRequestResolver, stateStore, workspaceResolver,
            toolProvider, RuntimeKnowledgeProvider.empty(), RuntimeMemoryProvider.empty(),
            objectMapper, maxWorkspaceFileSizeMb, allowInsecureModelEndpoints
        );
    }

    /**
     * 创建 {@code DefaultAgentScopeInvocationFactory} 实例并初始化所需依赖。
     *
     * @param credentialResolver 凭据Resolver参数
     * @param runRequestResolver {@code runRequestResolver}参数
     * @param stateStore {@code stateStore}参数
     * @param workspaceResolver 工作空间Resolver参数
     * @param objectMapper {@code objectMapper}参数
     * @param maxWorkspaceFileSizeMb 数量上限
     * @param allowInsecureModelEndpoints allowInsecure模型Endpoints参数
     */
    public DefaultAgentScopeInvocationFactory(
        RuntimeCredentialResolver credentialResolver,
        AgentRunRequestResolver runRequestResolver,
        AgentStateStore stateStore,
        AgentScopeWorkspaceResolver workspaceResolver,
        ObjectMapper objectMapper,
        int maxWorkspaceFileSizeMb,
        boolean allowInsecureModelEndpoints
    ) {
        this(
            credentialResolver, runRequestResolver, stateStore, workspaceResolver,
            RuntimeToolProvider.empty(), RuntimeKnowledgeProvider.empty(),
            RuntimeMemoryProvider.empty(), objectMapper, maxWorkspaceFileSizeMb,
            allowInsecureModelEndpoints
        );
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Override
    public AgentScopeInvocation create(AgentRunRequest request) {
        return build(Objects.requireNonNull(request, "request must not be null"));
    }

    /**
     * 创建并保存{@code ForResume}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Override
    public AgentScopeInvocation createForResume(AgentResumeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        AgentRunRequest frozen = Objects.requireNonNull(
            runRequestResolver.resolveForResume(request),
            "persisted AgentRunRequest must not be null"
        );
        validateResumeIdentity(request, frozen);
        return build(withControlledResumeAttributes(frozen, request));
    }

    /**
 * 处理{@code withControlledResumeAttributes}并返回对应结果。
 *
     * Carries only server-issued, bounded resume scopes into the tool snapshot.
     * The persisted run remains the authority; this metadata can only narrow its
     * dataset resources and is ignored for every other resume payload.
     */
    private AgentRunRequest withControlledResumeAttributes(
        AgentRunRequest frozen,
        AgentResumeRequest resume
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Object rawScope = resume.decisionMetadata().get("metadata_dataset_scope");
        if (!(rawScope instanceof Map<?, ?> scope)) {
            return frozen;
        }
        if (!"runtime_user_question".equals(resume.decisionMetadata().get("source"))
            || !"submitted".equals(resume.decisionMetadata().get("status"))
            || !"user_question".equals(scope.get("source"))) {
            return frozen;
        }
        Object rawIds = scope.get("dataset_ids");
        if (!(rawIds instanceof List<?> ids) || ids.isEmpty() || ids.size() > 32) {
            return frozen;
        }
        List<String> normalized = new java.util.ArrayList<>();
        for (Object rawId : ids) {
            if (!(rawId instanceof String text) || !text.matches("[1-9][0-9]{0,18}")) {
                return frozen;
            }
            try {
                String id = Long.toString(Long.parseLong(text));
                if (!normalized.contains(id)) {
                    normalized.add(id);
                }
            } catch (NumberFormatException exception) {
                return frozen;
            }
        }
        if (normalized.isEmpty()) {
            return frozen;
        }
        Map<String, Object> attributes = new LinkedHashMap<>(frozen.attributes());
        attributes.put("runtimeResumeDatasetScope", Map.of(
            "source", "user_question",
            "dataset_ids", List.copyOf(normalized)
        ));
        return new AgentRunRequest(
            frozen.executionKey(), frozen.userId(), frozen.conversationId(), frozen.taskId(),
            frozen.runId(), frozen.stepId(), frozen.agentVersionId(), frozen.agentName(),
            frozen.sessionId(), frozen.input(), frozen.systemPrompt(), frozen.model(),
            frozen.workspaceKey(), frozen.maxIterations(), frozen.authorizationSnapshot(),
            Map.copyOf(attributes)
        );
    }

    /**
     * 构建{@code build}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private AgentScopeInvocation build(AgentRunRequest request) {
        toolProvider.begin(request);
        try {
            return buildMounted(request);
        } catch (RuntimeException | Error failure) {
            try {
                toolProvider.end(request);
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * 构建{@code Mounted}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private AgentScopeInvocation buildMounted(AgentRunRequest request) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Path workspace = workspaceResolver.resolve(request);
        // The platform provider mounts only the immutable files referenced by this run snapshot.
        // This happens after workspace validation and before any model/tool execution.
        toolProvider.mount(request, workspace);
        Model model = model(request.model());
        Toolkit toolkit = new Toolkit();
        PermissionContextState.Builder permissions = PermissionContextState.builder();
        List<RuntimeToolDefinition> tools = toolProvider.resolve(request);
        List<RuntimeKnowledgeDefinition> knowledge = knowledgeProvider.resolve(request);
        AgentRunRequest runtimeRequest = withMountedToolNames(request, tools, knowledge);
        for (RuntimeToolDefinition tool : tools) {
            if (tool.externalExecution()) {
                toolkit.registration().agentTool(new SchemaOnlyTool(
                    tool.name(), tool.description(), tool.inputSchema()
                )).apply();
            } else {
                toolkit.registration()
                    .agentTool(new PlatformRuntimeAgentTool(runtimeRequest, tool, toolProvider, objectMapper))
                    .apply();
            }
            PermissionBehavior behavior = Set.of("R0", "R1").contains(tool.riskLevel())
                ? PermissionBehavior.ALLOW : PermissionBehavior.ASK;
            PermissionRule rule = new PermissionRule(
                tool.name(), null, behavior, "agent-platform-risk-policy"
            );
            if (behavior == PermissionBehavior.ALLOW) {
                permissions.addAllowRule(tool.name(), rule);
            } else {
                permissions.addAskRule(tool.name(), rule);
            }
        }
        for (RuntimeKnowledgeDefinition definition : knowledge) {
            PlatformRuntimeKnowledgeTool knowledgeTool = new PlatformRuntimeKnowledgeTool(
                runtimeRequest, definition, knowledgeProvider, objectMapper
            );
            toolkit.registration().agentTool(knowledgeTool).apply();
            PermissionBehavior behavior = definition.requiresApproval()
                ? PermissionBehavior.ASK : PermissionBehavior.ALLOW;
            PermissionRule rule = new PermissionRule(
                knowledgeTool.getName(), null, behavior, "agent-platform-knowledge-policy"
            );
            if (behavior == PermissionBehavior.ALLOW) {
                permissions.addAllowRule(knowledgeTool.getName(), rule);
            } else {
                permissions.addAskRule(knowledgeTool.getName(), rule);
            }
        }
        List<RuntimeMemoryDefinition> memory = memoryProvider.resolve(request);
        HarnessAgent.Builder builder = HarnessAgent.builder()
            .agentId("agent-version-" + runtimeRequest.agentVersionId())
            .name(runtimeRequest.agentName())
            .sysPrompt(systemPrompt(runtimeRequest, memory, tools, knowledge))
            .model(model)
            .toolkit(toolkit)
            .maxIters(request.maxIterations())
            .stateStore(stateStore)
            .workspace(workspace)
            .abstractFilesystem(new LocalFilesystem(
                workspace,
                true,
                maxWorkspaceFileSizeMb
            ))
            .enablePendingToolRecovery(true)
            .disableShellTool()
            .disableSubagents()
            .disableDynamicSubagents()
            .disableDynamicSkills()
            .disableDefaultWorkspaceSkills()
            .disableToolsConfig()
            .disableMemoryTools()
            .disableMemoryHooks();
        if (!tools.isEmpty() || !knowledge.isEmpty()) {
            builder.permissionContext(permissions.build());
        }
        if (!filesystemToolsAllowed(request.authorizationSnapshot())) {
            builder.disableFilesystemTools();
        }
        return new HarnessAgentInvocation(
            builder.build(), objectMapper, runtimeRequest, () -> toolProvider.end(request)
        );
    }

    /**
     * 处理withMounted工具Names并返回对应结果。
     *
     * @param request 请求参数
     * @param tools {@code tools}参数
     * @param knowledge 知识库参数
     * @return 处理结果
     */
    private AgentRunRequest withMountedToolNames(
        AgentRunRequest request,
        List<RuntimeToolDefinition> tools,
        List<RuntimeKnowledgeDefinition> knowledge
    ) {
        Map<String, Object> attributes = new LinkedHashMap<>(request.attributes());
        Set<String> names = new java.util.LinkedHashSet<>();
        tools.forEach(tool -> names.add(tool.name()));
        knowledge.forEach(item -> names.add(item.name()));
        attributes.put("mountedToolNames", List.copyOf(names));
        return new AgentRunRequest(
            request.executionKey(), request.userId(), request.conversationId(), request.taskId(),
            request.runId(), request.stepId(), request.agentVersionId(), request.agentName(),
            request.sessionId(), request.input(), request.systemPrompt(), request.model(),
            request.workspaceKey(), request.maxIterations(), request.authorizationSnapshot(),
            Map.copyOf(attributes)
        );
    }

    /**
     * 处理系统提示词并返回对应结果。
     *
     * @param request 请求参数
     * @param memory 记忆参数
     * @param tools {@code tools}参数
     * @param knowledge 知识库参数
     * @return 处理结果
     */
    private String systemPrompt(
        AgentRunRequest request,
        List<RuntimeMemoryDefinition> memory,
        List<RuntimeToolDefinition> tools,
        List<RuntimeKnowledgeDefinition> knowledge
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        StringBuilder prompt = new StringBuilder(request.systemPrompt());
        Object rawBindings = request.attributes().get("resourceBindings");
        if (rawBindings instanceof List<?> bindings) {
            for (Object value : bindings) {
                if (!(value instanceof Map<?, ?> binding)
                    || !"skill".equals(binding.get("resourceType"))
                    || !(binding.get("config") instanceof Map<?, ?> config)
                    || !(config.get("resourceSnapshot") instanceof Map<?, ?> snapshot)
                    || !(snapshot.get("content") instanceof String content)
                    || content.isBlank()) {
                    continue;
                }
                Object id = binding.get("resourceId");
                String name = snapshot.get("name") instanceof String text && !text.isBlank()
                    ? text.strip() : "skill-" + id;
                Object version = snapshot.get("versionNo");
                String skillKey = snapshot.get("skillKey") instanceof String text && !text.isBlank()
                    ? text.strip() : null;
                prompt.append("\n\n## Frozen Skill: ")
                    .append(name)
                    .append(" (resource ").append(id).append(", version ").append(version).append(")\n\n")
                    .append(skillKey == null
                        ? "Runtime files are mounted in the isolated workspace; scripts, assets and references are read-only frozen files for this run.\n\n"
                        : "Runtime files are mounted at skills/" + skillKey
                            + "; scripts, assets and references are read-only frozen files for this run.\n\n")
                    .append(content.strip());
            }
        }
        String withMemory = appendMemory(prompt.toString(), memory);
        String withDecision = appendTurnDecision(withMemory, request.attributes());
        Map<String, Object> decision = mapAttribute(request.attributes().get("turnDecision"));
        String nudge = RuntimeToolNudgePolicy.build(request.input(), decision, tools, knowledge);
        return nudge.isBlank() ? withDecision : withDecision + "\n\n" + nudge;
    }

    /**
     * 处理append会话回合Decision并返回对应结果。
     *
     * @param base {@code base}参数
     * @param attributes {@code attributes}参数
     * @return 处理结果
     */
    private String appendTurnDecision(String base, Map<String, Object> attributes) {
        Map<String, Object> decision = mapAttribute(attributes.get("turnDecision"));
        if (decision.isEmpty()) {
            return base;
        }
        StringBuilder prompt = new StringBuilder(base)
            .append("\n\n## Frozen Turn Decision\n")
            .append("The following server decision is authoritative for this turn. Do not silently change its source or context strategy.\n")
            .append("- source: ").append(bounded(decision.get("source"), "unknown", 64)).append('\n')
            .append("- capability: ").append(bounded(decision.get("capability"), "answer", 64)).append('\n')
            .append("- reference_mode: ").append(bounded(decision.get("referenceMode"), "unknown", 32)).append('\n')
            .append("- context_strategy: ").append(bounded(decision.get("contextStrategy"), "UNCERTAIN", 16)).append('\n')
            .append("- needs_fresh_data: ").append(Boolean.TRUE.equals(decision.get("needsFreshData"))).append('\n')
            .append("- reasoning: ").append(bounded(decision.get("reasoning"), "", 256)).append('\n');
        String strategy = bounded(decision.get("contextStrategy"), "UNCERTAIN", 16);
        if ("KEEP".equalsIgnoreCase(strategy)) {
            prompt.append("Keep the relevant prior conversation context, but do not invent facts absent from it.\n");
        } else if ("BREAK".equalsIgnoreCase(strategy)) {
            prompt.append("Treat this as a new source request and obtain fresh evidence when the capability requires it.\n");
        } else {
            prompt.append("The context relation is uncertain; do not claim a prior result unless a mounted tool or the conversation proves it.\n");
        }
        return prompt.toString();
    }

    /**
     * 将输入数据转换为{@code Attribute}。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> mapAttribute(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key instanceof String text && !text.isBlank()) {
                normalized.put(text, item);
            }
        });
        return normalized;
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String bounded(Object value, String fallback, int maxLength) {
        String text = value == null ? "" : String.valueOf(value).strip();
        if (text.isEmpty()) {
            text = fallback;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * 处理append记忆并返回对应结果。
     *
     * @param basePrompt base提示词参数
     * @param memory 记忆参数
     * @return 处理结果
     */
    private String appendMemory(String basePrompt, List<RuntimeMemoryDefinition> memory) {
        if (memory.isEmpty()) {
            return basePrompt;
        }
        StringBuilder prompt = new StringBuilder(basePrompt)
            .append("\n\n## Approved Platform Memory (read-only)\n\n")
            .append("Use these governed facts only as context. Do not edit them or claim they came from the current user message.\n");
        for (RuntimeMemoryDefinition entry : memory) {
            prompt.append("- [")
                .append(entry.scopeType()).append(':').append(entry.scopeId())
                .append("/").append(entry.memoryType()).append("] ")
                .append(entry.content()).append('\n');
        }
        return prompt.toString();
    }

    /**
     * 处理模型并返回对应结果。
     *
     * @param config {@code config}参数
     * @return 处理结果
     */
    private Model model(RuntimeModelConfig config) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String provider = config.provider().toLowerCase(Locale.ROOT).replace('_', '-');
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("unsupported model provider: " + config.provider());
        }
        validateOptions(config.options());
        String baseUrl = validateBaseUrl(config.baseUrl(), provider);
        String apiKey = credentialResolver.resolve(config.credentialRef());

        GenerateOptions.Builder generateOptions = GenerateOptions.builder();
        applyNumber(config.options(), "temperature", 0, 2, generateOptions::temperature);
        applyNumber(config.options(), "topP", 0, 1, generateOptions::topP);
        applyInteger(config.options(), "maxTokens", 1, 1_000_000, generateOptions::maxTokens);
        applyInteger(
            config.options(),
            "maxCompletionTokens",
            1,
            1_000_000,
            generateOptions::maxCompletionTokens
        );
        applyNumber(
            config.options(), "frequencyPenalty", -2, 2, generateOptions::frequencyPenalty
        );
        applyNumber(
            config.options(), "presencePenalty", -2, 2, generateOptions::presencePenalty
        );
        applyInteger(
            config.options(), "thinkingBudget", 0, 1_000_000, generateOptions::thinkingBudget
        );
        String reasoningEffort = textOption(config.options(), "reasoningEffort");
        if (reasoningEffort != null) {
            if (!Set.of("low", "medium", "high").contains(reasoningEffort)) {
                throw new IllegalArgumentException("reasoningEffort must be low, medium or high");
            }
            generateOptions.reasoningEffort(reasoningEffort);
        }
        Boolean parallelToolCalls = booleanOption(config.options(), "parallelToolCalls");
        if (parallelToolCalls != null) {
            generateOptions.parallelToolCalls(parallelToolCalls);
        }

        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
            .apiKey(apiKey)
            .modelName(config.modelName())
            .stream(true)
            .generateOptions(generateOptions.build());
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        String endpointPath = textOption(config.options(), "endpointPath");
        if (endpointPath != null) {
            if (!endpointPath.startsWith("/") || endpointPath.contains("..")) {
                throw new IllegalArgumentException("endpointPath must be an absolute safe path");
            }
            builder.endpointPath(endpointPath);
        }
        Integer contextWindowSize = integerOption(
            config.options(), "contextWindowSize", 1, 10_000_000
        );
        if (contextWindowSize != null) {
            builder.contextWindowSize(contextWindowSize);
        }
        boolean officialOpenAi = "openai".equals(provider);
        builder.nativeStructuredOutput(booleanOption(
            config.options(), "nativeStructuredOutput", officialOpenAi
        ));
        builder.nativeStructuredOutputWithTools(booleanOption(
            config.options(), "nativeStructuredOutputWithTools", officialOpenAi
        ));
        return builder.build();
    }

    /**
     * 校验{@code BaseUrl}，并在条件不满足时终止处理。
     *
     * @param baseUrl {@code baseUrl}参数
     * @param provider 提供方参数
     * @return 处理结果
     */
    private String validateBaseUrl(String baseUrl, String provider) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (baseUrl == null) {
            if ("openai-compatible".equals(provider)) {
                throw new IllegalArgumentException("openai-compatible provider requires baseUrl");
            }
            return null;
        }
        try {
            URI uri = new URI(baseUrl);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("model baseUrl is not a safe absolute URL");
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !allowInsecureModelEndpoints) {
                throw new IllegalArgumentException("model baseUrl must use https");
            }
            if (!Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("model baseUrl must use http or https");
            }
            return uri.toString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("model baseUrl is invalid", exception);
        }
    }

    /**
     * 校验{@code Options}，并在条件不满足时终止处理。
     *
     * @param options {@code options}参数
     */
    private void validateOptions(Map<String, Object> options) {
        Set<String> unsupported = new HashSet<>(options.keySet());
        unsupported.removeAll(SUPPORTED_OPTIONS);
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException("unsupported model options: " + unsupported);
        }
    }

    /**
     * 处理{@code filesystemToolsAllowed}并返回对应结果。
     *
     * @param authorizationSnapshot 授权快照参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean filesystemToolsAllowed(Map<String, Object> authorizationSnapshot) {
        return "read_write".equals(authorizationSnapshot.get("workspaceAccess"));
    }

    /**
     * 校验Resume身份，并在条件不满足时终止处理。
     *
     * @param resume {@code resume}参数
     * @param frozen {@code frozen}参数
     */
    private void validateResumeIdentity(AgentResumeRequest resume, AgentRunRequest frozen) {
        if (!resume.executionKey().equals(frozen.executionKey())
            || !resume.userId().equals(frozen.userId())
            || !resume.sessionId().equals(frozen.sessionId())
            || !Objects.equals(resume.conversationId(), frozen.conversationId())
            || !Objects.equals(resume.taskId(), frozen.taskId())
            || !Objects.equals(resume.runId(), frozen.runId())
            || !Objects.equals(resume.stepId(), frozen.stepId())) {
            throw new SecurityException("resume identity does not match the persisted runtime definition");
        }
    }

    /**
     * 处理{@code applyNumber}相关逻辑。
     *
     * @param options {@code options}参数
     * @param key {@code key}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     * @param consumer {@code consumer}参数
     */
    private void applyNumber(
        Map<String, Object> options,
        String key,
        double minimum,
        double maximum,
        java.util.function.Consumer<Double> consumer
    ) {
        Double value = numberOption(options, key, minimum, maximum);
        if (value != null) {
            consumer.accept(value);
        }
    }

    /**
     * 处理{@code applyInteger}相关逻辑。
     *
     * @param options {@code options}参数
     * @param key {@code key}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     * @param consumer {@code consumer}参数
     */
    private void applyInteger(
        Map<String, Object> options,
        String key,
        int minimum,
        int maximum,
        java.util.function.Consumer<Integer> consumer
    ) {
        Integer value = integerOption(options, key, minimum, maximum);
        if (value != null) {
            consumer.accept(value);
        }
    }

    /**
     * 处理{@code numberOption}并返回对应结果。
     *
     * @param options {@code options}参数
     * @param key {@code key}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private Double numberOption(
        Map<String, Object> options,
        String key,
        double minimum,
        double maximum
    ) {
        Object raw = options.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " is outside the supported range");
        }
        return value;
    }

    /**
     * 处理{@code integerOption}并返回对应结果。
     *
     * @param options {@code options}参数
     * @param key {@code key}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private Integer integerOption(
        Map<String, Object> options,
        String key,
        int minimum,
        int maximum
    ) {
        Object raw = options.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        long value = number.longValue();
        if (number.doubleValue() != value || value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " is outside the supported integer range");
        }
        return Math.toIntExact(value);
    }

    /**
     * 处理{@code textOption}并返回对应结果。
     *
     * @param options {@code options}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private String textOption(Map<String, Object> options, String key) {
        Object raw = options.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be non-blank text");
        }
        return text.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 处理{@code booleanOption}并返回对应结果。
     *
     * @param options {@code options}参数
     * @param key {@code key}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private Boolean booleanOption(Map<String, Object> options, String key) {
        Object raw = options.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Boolean value)) {
            throw new IllegalArgumentException(key + " must be boolean");
        }
        return value;
    }

    /**
     * 处理{@code booleanOption}并返回对应结果。
     *
     * @param options {@code options}参数
     * @param key {@code key}参数
     * @param defaultValue {@code defaultValue}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean booleanOption(Map<String, Object> options, String key, boolean defaultValue) {
        Boolean value = booleanOption(options, key);
        return value == null ? defaultValue : value;
    }
}
