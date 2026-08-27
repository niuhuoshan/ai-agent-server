package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeToolDefinition;
import group.aitools.nhs.runtime.spi.RuntimeToolProvider;
import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.platform.audit.service.ToolInvocationAuditService;
import group.aitools.nhs.platform.browser.service.BrowserSessionApplicationService;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.domain.AgentTool;
import group.aitools.nhs.platform.connector.mapper.ConnectorCatalogMapper;
import group.aitools.nhs.platform.execution.service.FrozenRuntimePrincipalResolver;
import group.aitools.nhs.platform.data.service.PlatformRuntimeDataQueryProvider;
import group.aitools.nhs.platform.embed.mapper.EmbedChatMapper;
import group.aitools.nhs.platform.embed.persistence.row.EmbedAgentRuntimeRow;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.knowledge.service.PlatformRuntimeKnowledgeProvider;
import group.aitools.nhs.platform.memory.domain.AgentMemory;
import group.aitools.nhs.platform.memory.mapper.MemoryCatalogMapper;
import group.aitools.nhs.platform.memory.service.MemoryScopeAuthorizationService;
import group.aitools.nhs.platform.memory.service.MemoryVectorApplicationService;
import group.aitools.nhs.platform.nhs.service.NhsWorkspaceService;
import group.aitools.nhs.platform.nhs.portal.example.PortalExampleRuntimeSearchService;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.platform.notification.service.NotificationChannelSender;
import group.aitools.nhs.platform.notification.service.NotificationMessage;
import group.aitools.nhs.platform.notification.service.NotificationRecipient;
import group.aitools.nhs.platform.notification.service.UserNotificationConfigService;
import group.aitools.nhs.platform.sandbox.service.SandboxJobQueueService;
import group.aitools.nhs.platform.sandbox.service.SandboxJobSubmission;
import group.aitools.nhs.platform.sandbox.service.SandboxSkillManifest;
import group.aitools.nhs.platform.search.service.WebSearchApplicationService;
import group.aitools.nhs.platform.skill.service.SkillRuntimeMountService;
import group.aitools.nhs.platform.task.domain.AgentTask;
import group.aitools.nhs.platform.task.mapper.AgentTaskMapper;
import group.aitools.nhs.platform.runtime.question.domain.UserQuestionCreateCommand;
import group.aitools.nhs.platform.runtime.question.service.RuntimeUserQuestionApplicationService;
import group.aitools.nhs.platform.runtime.question.web.UserQuestionView;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 负责平台运行时工具相关的转换、解析或处理逻辑。
 * Resolves and invokes only the frozen task/Agent tool intersection through current controls. */
@Service
public class PlatformRuntimeToolProvider implements RuntimeToolProvider {

    private static final Set<String> RISKS = Set.of("R0", "R1", "R2", "R3");
    private static final Set<String> RUNTIME_TOOL_TYPES = Set.of(
        "builtin", "mcp", "api", "search", "sql", "sandbox"
    );
    private static final Set<String> SKILL_SCOPES = Set.of(
        "system", "project", "user", "global", "personal"
    );
    private static final String SKILL_NHS_BINDING_PERMISSION = "use";
    private static final Set<String> SKILL_TASK_PERMISSIONS = Set.of("use", "admin");
    private static final Set<String> SKILL_SESSION_PERMISSIONS = Set.of("invoke", "use", "admin");
    private static final int MAX_SKILL_ID_LENGTH = 128;
    private static final int MAX_SKILL_INSTRUCTION_BYTES = 256 * 1024;
    private static final int MAX_SKILL_CATALOG_ENTRIES = 128;

    private final ConnectorCatalogMapper mapper;
    private final FrozenRuntimePrincipalResolver principalResolver;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final ConnectorMcpConnectionFactory connectionFactory;
    private final McpRemoteClient remoteClient;
    private final ApiToolExecutor apiToolExecutor;
    private final ToolArgumentValidator argumentValidator;
    private final ToolInvocationAuditService auditService;
    private final JsonMapper jsonMapper;
    private final PlatformRuntimeDataQueryProvider dataQueryProvider;
    private final SandboxJobQueueService sandboxQueue;
    private final PlatformRuntimeKnowledgeProvider knowledgeProvider;
    private final MemoryCatalogMapper memoryMapper;
    private final MemoryScopeAuthorizationService memoryScopeAuthorization;
    /** Optional for backwards-compatible embedders; enables governed vector recall. */
    private MemoryVectorApplicationService memoryVectorService;
    private final PlatformIdGenerator idGenerator;
    private final NotificationApplicationService notificationService;
    /** Optional for backwards-compatible embedders; required when external notification tools run. */
    private UserNotificationConfigService notificationConfigService;
    private final NhsWorkspaceService workspaceService;
    private final PortalExampleRuntimeSearchService exampleSearchService;
    private final McpRuntimeLifecycleService mcpLifecycle;
    private final WebSearchApplicationService webSearchService;
    private final AgentTaskMapper taskMapper;
    private final TaskControlBuiltinService taskControlService;
    /** Optional for backwards-compatible embedders; supplies the authorized Agent catalog tool. */
    private EmbedChatMapper agentCatalogMapper;
    /** Optional for backwards-compatible embedders; required by public-web builtins. */
    private BuiltinWebToolService webToolService;
    /** Optional for backwards-compatible embedders; required by runtime Skill creation. */
    private RuntimeSkillBuiltinService runtimeSkillService;
    /** Optional for backwards-compatible embedders; required by command/process builtins. */
    private RuntimeSandboxBuiltinService runtimeSandboxService;
    /** Optional for backwards-compatible embedders; required by stateful auxiliary builtins. */
    private RuntimeAuxiliaryBuiltinService auxiliaryBuiltinService;
    /** Optional for backwards-compatible embedders; mounts immutable Skill bundles for a run. */
    private SkillRuntimeMountService skillRuntimeMountService;
    /** Optional for backwards-compatible embedders; handles Agent-initiated user questions. */
    private RuntimeUserQuestionApplicationService runtimeUserQuestionService;
    /**
 * 创建 {@code PlatformRuntimeToolProvider} 实例并初始化所需依赖。
 * Optional for backwards-compatible embedders; controls isolated browser sessions. */
    private BrowserSessionApplicationService browserSessionService;

    @Autowired
    public PlatformRuntimeToolProvider(
        ConnectorCatalogMapper mapper,
        FrozenRuntimePrincipalResolver principalResolver,
        AuthorizationEnforcer authorizationEnforcer,
        ConnectorMcpConnectionFactory connectionFactory,
        McpRemoteClient remoteClient,
        ApiToolExecutor apiToolExecutor,
        ToolArgumentValidator argumentValidator,
        ToolInvocationAuditService auditService,
        PlatformRuntimeDataQueryProvider dataQueryProvider,
        SandboxJobQueueService sandboxQueue,
        JsonMapper jsonMapper,
        PlatformRuntimeKnowledgeProvider knowledgeProvider,
        MemoryCatalogMapper memoryMapper,
        MemoryScopeAuthorizationService memoryScopeAuthorization,
        PlatformIdGenerator idGenerator,
        NotificationApplicationService notificationService,
        NhsWorkspaceService workspaceService,
        PortalExampleRuntimeSearchService exampleSearchService,
        McpRuntimeLifecycleService mcpLifecycle,
        WebSearchApplicationService webSearchService,
        AgentTaskMapper taskMapper,
        TaskControlBuiltinService taskControlService
    ) {
        this.mapper = mapper;
        this.principalResolver = principalResolver;
        this.authorizationEnforcer = authorizationEnforcer;
        this.connectionFactory = connectionFactory;
        this.remoteClient = remoteClient;
        this.apiToolExecutor = apiToolExecutor;
        this.argumentValidator = argumentValidator;
        this.auditService = auditService;
        this.dataQueryProvider = dataQueryProvider;
        this.sandboxQueue = sandboxQueue;
        this.jsonMapper = jsonMapper;
        this.knowledgeProvider = knowledgeProvider;
        this.memoryMapper = memoryMapper;
        this.memoryScopeAuthorization = memoryScopeAuthorization;
        this.idGenerator = idGenerator;
        this.notificationService = notificationService;
        this.workspaceService = workspaceService;
        this.exampleSearchService = exampleSearchService;
        this.mcpLifecycle = mcpLifecycle;
        this.webSearchService = webSearchService;
        this.taskMapper = taskMapper;
        this.taskControlService = taskControlService;
    }

    /**
 * 设置用户通知ConfigService。
 *
     * Injects the owner-scoped notification configuration without changing the many legacy
     * constructors used by focused runtime tests and non-Spring embedders.
     */
    @Autowired(required = false)
    public void setUserNotificationConfigService(
        UserNotificationConfigService notificationConfigService
    ) {
        this.notificationConfigService = notificationConfigService;
    }

    /**
 * 设置BuiltinWeb工具Service。
 * Injects the bounded public-web builtin service without changing legacy constructors. */
    @Autowired(required = false)
    public void setBuiltinWebToolService(BuiltinWebToolService webToolService) {
        this.webToolService = webToolService;
    }

    /**
 * 设置运行时技能BuiltinService。
 * Injects the Skill creation executor without changing legacy constructors. */
    @Autowired(required = false)
    public void setRuntimeSkillBuiltinService(RuntimeSkillBuiltinService runtimeSkillService) {
        this.runtimeSkillService = runtimeSkillService;
    }

    /**
 * 设置运行时沙箱BuiltinService。
 * Injects the durable Sandbox command/process executor without changing legacy constructors. */
    @Autowired(required = false)
    public void setRuntimeSandboxBuiltinService(RuntimeSandboxBuiltinService runtimeSandboxService) {
        this.runtimeSandboxService = runtimeSandboxService;
    }

    /**
 * 设置运行时AuxiliaryBuiltinService。
 * Injects the Nhs-compatible stateful builtin executor without changing legacy constructors. */
    @Autowired(required = false)
    public void setRuntimeAuxiliaryBuiltinService(RuntimeAuxiliaryBuiltinService auxiliaryBuiltinService) {
        this.auxiliaryBuiltinService = auxiliaryBuiltinService;
    }

    /**
 * 设置技能运行时MountService。
 * Injects the immutable Skill bundle materializer without changing legacy constructors. */
    @Autowired(required = false)
    public void setSkillRuntimeMountService(SkillRuntimeMountService skillRuntimeMountService) {
        this.skillRuntimeMountService = skillRuntimeMountService;
    }

    /**
 * 设置运行时用户追问应用Service。
 * Injects the owner-scoped Agent question executor without changing legacy constructors. */
    @Autowired(required = false)
    public void setRuntimeUserQuestionApplicationService(
        RuntimeUserQuestionApplicationService runtimeUserQuestionService
    ) {
        this.runtimeUserQuestionService = runtimeUserQuestionService;
    }

    /**
     * 设置浏览器会话应用Service。
     *
     * @param browserSessionService 浏览器会话Service参数
     */
    @Autowired(required = false)
    public void setBrowserSessionApplicationService(BrowserSessionApplicationService browserSessionService) {
        this.browserSessionService = browserSessionService;
    }

    /**
     * 设置智能体目录Mapper。
     *
     * @param agentCatalogMapper 智能体目录Mapper参数
     */
    @Autowired(required = false)
    public void setAgentCatalogMapper(EmbedChatMapper agentCatalogMapper) {
        this.agentCatalogMapper = agentCatalogMapper;
    }

    /**
 * 设置记忆Vector应用Service。
 * Injects the same vector service used by the memory operations page. */
    @Autowired(required = false)
    public void setMemoryVectorApplicationService(MemoryVectorApplicationService memoryVectorService) {
        this.memoryVectorService = memoryVectorService;
    }

    /**
     * 处理{@code mount}相关逻辑。
     *
     * @param request 请求参数
     * @param workspace 工作空间参数
     */
    @Override
    public void mount(AgentRunRequest request, Path workspace) {
        if (skillRuntimeMountService != null) {
            skillRuntimeMountService.mount(request, workspace);
        }
    }

    /**
 * 创建 {@code PlatformRuntimeToolProvider} 实例并初始化所需依赖。
 * Backwards-compatible full constructor for focused tests and non-Spring embedders. */
    public PlatformRuntimeToolProvider(
        ConnectorCatalogMapper mapper,
        FrozenRuntimePrincipalResolver principalResolver,
        AuthorizationEnforcer authorizationEnforcer,
        ConnectorMcpConnectionFactory connectionFactory,
        McpRemoteClient remoteClient,
        ApiToolExecutor apiToolExecutor,
        ToolArgumentValidator argumentValidator,
        ToolInvocationAuditService auditService,
        PlatformRuntimeDataQueryProvider dataQueryProvider,
        SandboxJobQueueService sandboxQueue,
        JsonMapper jsonMapper,
        PlatformRuntimeKnowledgeProvider knowledgeProvider,
        MemoryCatalogMapper memoryMapper,
        MemoryScopeAuthorizationService memoryScopeAuthorization,
        PlatformIdGenerator idGenerator,
        NotificationApplicationService notificationService,
        NhsWorkspaceService workspaceService,
        PortalExampleRuntimeSearchService exampleSearchService
    ) {
        this(
            mapper, principalResolver, authorizationEnforcer, connectionFactory, remoteClient,
            apiToolExecutor, argumentValidator, auditService, dataQueryProvider, sandboxQueue,
            jsonMapper, knowledgeProvider, memoryMapper, memoryScopeAuthorization, idGenerator,
            notificationService, workspaceService, exampleSearchService, null, null, null, null
        );
    }

    /**
 * 创建 {@code PlatformRuntimeToolProvider} 实例并初始化所需依赖。
 * Backwards-compatible full constructor for embedders that predate local examples. */
    public PlatformRuntimeToolProvider(
        ConnectorCatalogMapper mapper,
        FrozenRuntimePrincipalResolver principalResolver,
        AuthorizationEnforcer authorizationEnforcer,
        ConnectorMcpConnectionFactory connectionFactory,
        McpRemoteClient remoteClient,
        ApiToolExecutor apiToolExecutor,
        ToolArgumentValidator argumentValidator,
        ToolInvocationAuditService auditService,
        PlatformRuntimeDataQueryProvider dataQueryProvider,
        SandboxJobQueueService sandboxQueue,
        JsonMapper jsonMapper,
        PlatformRuntimeKnowledgeProvider knowledgeProvider,
        MemoryCatalogMapper memoryMapper,
        MemoryScopeAuthorizationService memoryScopeAuthorization,
        PlatformIdGenerator idGenerator,
        NotificationApplicationService notificationService,
        NhsWorkspaceService workspaceService
    ) {
        this(
            mapper, principalResolver, authorizationEnforcer, connectionFactory, remoteClient,
            apiToolExecutor, argumentValidator, auditService, dataQueryProvider, sandboxQueue,
            jsonMapper, knowledgeProvider, memoryMapper, memoryScopeAuthorization, idGenerator,
            notificationService, workspaceService, null, null, null, null, null
        );
    }

    /**
 * 创建 {@code PlatformRuntimeToolProvider} 实例并初始化所需依赖。
 * Backwards-compatible constructor used by focused unit tests and embedders. */
    public PlatformRuntimeToolProvider(
        ConnectorCatalogMapper mapper,
        FrozenRuntimePrincipalResolver principalResolver,
        AuthorizationEnforcer authorizationEnforcer,
        ConnectorMcpConnectionFactory connectionFactory,
        McpRemoteClient remoteClient,
        ApiToolExecutor apiToolExecutor,
        ToolArgumentValidator argumentValidator,
        ToolInvocationAuditService auditService,
        PlatformRuntimeDataQueryProvider dataQueryProvider,
        SandboxJobQueueService sandboxQueue,
        JsonMapper jsonMapper
    ) {
        this(
            mapper, principalResolver, authorizationEnforcer, connectionFactory, remoteClient,
            apiToolExecutor, argumentValidator, auditService, dataQueryProvider, sandboxQueue,
            jsonMapper, null, null, null, null, null, null, null, null, null, null, null
        );
    }

    /**
     * 创建 {@code PlatformRuntimeToolProvider} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param principalResolver 操作主体Resolver参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param connectionFactory {@code connectionFactory}参数
     * @param remoteClient remote客户端参数
     * @param apiToolExecutor 接口工具Executor参数
     * @param argumentValidator {@code argumentValidator}参数
     * @param auditService 审计Service参数
     * @param dataQueryProvider 数据查询提供方参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public PlatformRuntimeToolProvider(
        ConnectorCatalogMapper mapper,
        FrozenRuntimePrincipalResolver principalResolver,
        AuthorizationEnforcer authorizationEnforcer,
        ConnectorMcpConnectionFactory connectionFactory,
        McpRemoteClient remoteClient,
        ApiToolExecutor apiToolExecutor,
        ToolArgumentValidator argumentValidator,
        ToolInvocationAuditService auditService,
        PlatformRuntimeDataQueryProvider dataQueryProvider,
        JsonMapper jsonMapper
    ) {
        this(
            mapper, principalResolver, authorizationEnforcer, connectionFactory, remoteClient,
            apiToolExecutor, argumentValidator, auditService, dataQueryProvider, null, jsonMapper,
            null, null, null, null, null, null, null, null, null, null, null
        );
    }

    /**
     * 创建 {@code PlatformRuntimeToolProvider} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param principalResolver 操作主体Resolver参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param connectionFactory {@code connectionFactory}参数
     * @param remoteClient remote客户端参数
     * @param apiToolExecutor 接口工具Executor参数
     * @param argumentValidator {@code argumentValidator}参数
     * @param auditService 审计Service参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public PlatformRuntimeToolProvider(
        ConnectorCatalogMapper mapper,
        FrozenRuntimePrincipalResolver principalResolver,
        AuthorizationEnforcer authorizationEnforcer,
        ConnectorMcpConnectionFactory connectionFactory,
        McpRemoteClient remoteClient,
        ApiToolExecutor apiToolExecutor,
        ToolArgumentValidator argumentValidator,
        ToolInvocationAuditService auditService,
        JsonMapper jsonMapper
    ) {
        this(
            mapper, principalResolver, authorizationEnforcer, connectionFactory, remoteClient,
            apiToolExecutor, argumentValidator, auditService, null, null, jsonMapper,
            null, null, null, null, null, null, null, null, null, null, null
        );
    }

    /**
 * 创建 {@code PlatformRuntimeToolProvider} 实例并初始化所需依赖。
 * Focused constructor for runtime built-in tests that need task catalog access. */
    public PlatformRuntimeToolProvider(
        ConnectorCatalogMapper mapper,
        FrozenRuntimePrincipalResolver principalResolver,
        AuthorizationEnforcer authorizationEnforcer,
        ConnectorMcpConnectionFactory connectionFactory,
        McpRemoteClient remoteClient,
        ApiToolExecutor apiToolExecutor,
        ToolArgumentValidator argumentValidator,
        ToolInvocationAuditService auditService,
        PlatformRuntimeDataQueryProvider dataQueryProvider,
        SandboxJobQueueService sandboxQueue,
        JsonMapper jsonMapper,
        PlatformRuntimeKnowledgeProvider knowledgeProvider,
        MemoryCatalogMapper memoryMapper,
        MemoryScopeAuthorizationService memoryScopeAuthorization,
        PlatformIdGenerator idGenerator,
        NotificationApplicationService notificationService,
        NhsWorkspaceService workspaceService,
        PortalExampleRuntimeSearchService exampleSearchService,
        AgentTaskMapper taskMapper
    ) {
        this(
            mapper, principalResolver, authorizationEnforcer, connectionFactory, remoteClient,
            apiToolExecutor, argumentValidator, auditService, dataQueryProvider, sandboxQueue,
            jsonMapper, knowledgeProvider, memoryMapper, memoryScopeAuthorization, idGenerator,
            notificationService, workspaceService, exampleSearchService, null, null, taskMapper, null
        );
    }

    /**
 * 创建 {@code PlatformRuntimeToolProvider} 实例并初始化所需依赖。
 * Focused constructor for task-control builtin tests and non-Spring embedders. */
    public PlatformRuntimeToolProvider(
        ConnectorCatalogMapper mapper,
        FrozenRuntimePrincipalResolver principalResolver,
        AuthorizationEnforcer authorizationEnforcer,
        ConnectorMcpConnectionFactory connectionFactory,
        McpRemoteClient remoteClient,
        ApiToolExecutor apiToolExecutor,
        ToolArgumentValidator argumentValidator,
        ToolInvocationAuditService auditService,
        PlatformRuntimeDataQueryProvider dataQueryProvider,
        SandboxJobQueueService sandboxQueue,
        JsonMapper jsonMapper,
        TaskControlBuiltinService taskControlService
    ) {
        this(
            mapper, principalResolver, authorizationEnforcer, connectionFactory, remoteClient,
            apiToolExecutor, argumentValidator, auditService, dataQueryProvider, sandboxQueue,
            jsonMapper, null, null, null, null, null, null, null, null, null, null,
            taskControlService
        );
    }

    /**
     * 获取{@code resolve}。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    @Override
    public List<RuntimeToolDefinition> resolve(AgentRunRequest request) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Objects.requireNonNull(request, "request must not be null");
        CurrentPrincipal principal = principalResolver.resolve(request);
        List<RuntimeToolDefinition> result = new ArrayList<>();
        Map<Long, AgentConnector> mcpConnectors = new LinkedHashMap<>();
        for (FrozenTool frozen : frozenTools(request)) {
            requireTaskGrant(request, frozen.id());
            CurrentTool current = currentTool(frozen, principal);
            if (current == null || !RUNTIME_TOOL_TYPES.contains(frozen.toolType())) {
                continue;
            }
            // Keep a known but not-configured builtin in the frozen runtime catalog.  Dropping it
            // here made the resource appear to disappear between Agent setup and execution, and
            // the model could not surface the actual provider/configuration failure.  Invocation
            // remains fail-closed and returns the typed tool_unavailable/503 error below.
            if ("builtin".equals(frozen.toolType()) && !BuiltinToolCatalog.contains(builtinKey(frozen))) {
                continue;
            }
            AuthorizationDecision decision = authorizationEnforcer.decide(
                principal, permissionContext(request, frozen)
            );
            if (!decision.allowed() && !decision.requiresApproval()) {
                continue;
            }
            if (mcpLifecycle != null && "mcp".equals(frozen.toolType())
                && current.connector() != null) {
                mcpConnectors.putIfAbsent(current.connector().getId(), current.connector());
            }
            String builtin = "builtin".equals(frozen.toolType()) ? builtinKey(frozen) : null;
            String risk = "request_user_confirmation".equals(builtin) ? "R2"
                : (decision.requiresApproval() || "sandbox".equals(frozen.toolType()))
                    && Set.of("R0", "R1").contains(frozen.riskLevel()) ? "R2" : frozen.riskLevel();
            result.add(new RuntimeToolDefinition(
                frozen.id(), runtimeName(frozen), runtimeDescription(request, frozen),
                frozen.inputSchema(), frozen.outputSchema(), risk, frozen.readOnly(),
                "sandbox".equals(frozen.toolType())
            ));
        }
        if (dataQueryProvider != null) {
            result.addAll(dataQueryProvider.resolve(request));
        }
        if (mcpLifecycle != null) {
            mcpConnectors.values().forEach(connector -> mcpLifecycle.prepare(request, connector));
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code end}相关逻辑。
     *
     * @param request 请求参数
     */
    @Override
    public void end(AgentRunRequest request) {
        if (mcpLifecycle != null) {
            mcpLifecycle.end(request);
        }
    }

    /**
     * 执行{@code invoke}相关的处理流程。
     *
     * @param request 请求参数
     * @param toolId 资源标识
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    @Override
    public Object invoke(AgentRunRequest request, Long toolId, Map<String, Object> arguments) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Objects.requireNonNull(request, "request must not be null");
        if (toolId == null || toolId <= 0) {
            throw forbidden("运行时工具 ID 无效");
        }
        if (dataQueryProvider != null && dataQueryProvider.supports(request, toolId)) {
            return dataQueryProvider.invoke(request, toolId, arguments);
        }
        FrozenTool frozen = frozenTools(request).stream()
            .filter(item -> toolId.equals(item.id()))
            .findFirst()
            .orElseThrow(() -> forbidden("工具不在 Agent 冻结资源中"));
        requireTaskGrant(request, toolId);
        CurrentPrincipal principal = principalResolver.resolve(request);
        CurrentTool current = currentTool(frozen, principal);
        if (current == null) {
            throw forbidden("工具或连接器当前不可用");
        }
        if (!RUNTIME_TOOL_TYPES.contains(frozen.toolType())) {
            throw new ServiceException("当前运行时尚不支持此工具类型", HttpStatus.NOT_IMPLEMENTED);
        }
        if ("sandbox".equals(frozen.toolType())) {
            throw new ServiceException("沙箱工具必须通过AgentScope外部执行协议调用", HttpStatus.CONFLICT);
        }

        AuthorizationDecision decision = authorizationEnforcer.decide(
            principal, permissionContext(request, frozen)
        );
        if (!decision.allowed() && !decision.requiresApproval()) {
            throw forbidden("工具当前授权已失效：" + decision.reasonCode());
        }

        String argumentsJson = argumentValidator.validate(arguments, frozen.inputSchema());
        boolean outcomeRecorded = false;
        try {
            boolean success;
            String auditEvent;
            String resultJson;
            if ("builtin".equals(frozen.toolType())) {
                Map<String, Object> builtinResult = invokeBuiltin(request, frozen, arguments);
                resultJson = argumentValidator.boundedResultJson(builtinResult);
                String builtinStatus = normalizedResultStatus(builtinResult.get("status"));
                boolean degraded = "degraded".equals(builtinStatus);
                success = Boolean.TRUE.equals(builtinResult.get("ok")) && !degraded;
                auditEvent = degraded
                    ? "BUILTIN_TOOL_DEGRADED"
                    : success ? "BUILTIN_TOOL_SUCCEEDED" : "BUILTIN_TOOL_FAILED";
            } else if ("sql".equals(frozen.toolType())) {
                if (dataQueryProvider == null) {
                    throw new ServiceException("SQL 工具执行器当前不可用", 503);
                }
                Map<String, Object> sqlResult = envelope(
                    dataQueryProvider.executeConfigured(
                        request, frozen.inputSchema(), frozen.executionPolicy(),
                        arguments, frozen.toolKey()
                    )
                );
                resultJson = argumentValidator.boundedResultJson(sqlResult);
                success = Boolean.TRUE.equals(sqlResult.get("ok"));
                auditEvent = success ? "SQL_TOOL_SUCCEEDED" : "SQL_TOOL_FAILED";
            } else {
                ToolResult remote = invokeRemote(
                    request, current.connector(), frozen, arguments, argumentsJson
                );
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("error", remote.error());
                result.put("content", remote.content());
                result.put("structuredContent", remote.structuredContent());
                result.put("metadata", remote.metadata());
                resultJson = argumentValidator.boundedResultJson(result);
                success = !remote.error();
                auditEvent = remote.error()
                    ? frozen.toolType().toUpperCase(Locale.ROOT) + "_TOOL_ERROR"
                    : frozen.toolType().toUpperCase(Locale.ROOT) + "_TOOL_SUCCEEDED";
            }
            auditService.record(
                request, toolId, argumentsJson, resultJson, success, auditEvent
            );
            outcomeRecorded = true;
            return jsonMapper.readValue(resultJson, Object.class);
        } catch (RuntimeException exception) {
            if (!outcomeRecorded) {
                auditService.record(
                    request, toolId, argumentsJson, null, false,
                    "TOOL_INVOCATION_EXCEPTION:" + exception.getClass().getSimpleName()
                );
            }
            throw exception;
        }
    }

    /**
     * 执行{@code Builtin}相关的处理流程。
     *
     * @param request 请求参数
     * @param frozen {@code frozen}参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> invokeBuiltin(
        AgentRunRequest request,
        FrozenTool frozen,
        Map<String, Object> arguments
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String key = builtinKey(frozen);
        Object data = switch (key) {
            case "get_dataset_schema" -> builtinDatasetSchema(request, arguments);
            case "execute_sql_query" -> builtinSqlQuery(request, arguments);
            case "get_current_time" -> currentTime(arguments);
            case "resolve_relative_dates" -> relativeDates(arguments);
            case "get_myinfo" -> myInfo(request);
            case "get_current_model" -> currentModel(request);
            case "session_status" -> auxiliaryBuiltin(key).sessionStatus(
                principalResolver.resolve(request), request
            );
            case "read_image" -> auxiliaryBuiltin(key).readImage(
                principalResolver.resolve(request), arguments
            );
            case "browser_open" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).open(
                    optionalBuiltinText(firstArgument(arguments, "profile_key", "profileKey")),
                    optionalBuiltinText(firstArgument(arguments, "start_url", "startUrl"))
                )
            );
            case "browser_navigate" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).navigate(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    requiredBuiltinText(firstArgument(arguments, "url"), "页面URL", 2048)
                )
            );
            case "browser_snapshot" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).snapshot(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID")
                )
            );
            case "browser_click" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).click(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    requiredBuiltinText(firstArgument(arguments, "selector"), "CSS选择器", 1000)
                )
            );
            case "browser_fill" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).fill(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    requiredBuiltinText(firstArgument(arguments, "selector"), "CSS选择器", 1000),
                    optionalBuiltinText(firstArgument(arguments, "value")) == null
                        ? "" : optionalBuiltinText(firstArgument(arguments, "value"))
                )
            );
            case "browser_close" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).close(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID")
                )
            );
            case "browser_press" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).press(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    requiredBuiltinText(firstArgument(arguments, "key"), "键盘按键", 64)
                )
            );
            case "browser_back", "browser_forward", "browser_reload" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).history(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    key.substring("browser_".length())
                )
            );
            case "browser_wait_for" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).waitFor(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    requiredBuiltinText(firstArgument(arguments, "condition"), "等待条件", 32),
                    optionalBuiltinText(firstArgument(arguments, "value")),
                    optionalBuiltinInteger(firstArgument(arguments, "timeout_ms", "timeoutMs"), 100, 30000, "等待超时")
                )
            );
            case "browser_select_option" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).selectOption(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    requiredBuiltinText(firstArgument(arguments, "selector", "target_ref", "targetRef"), "CSS选择器", 1000),
                    optionalBuiltinText(firstArgument(arguments, "value")),
                    optionalBuiltinText(firstArgument(arguments, "label"))
                )
            );
            case "browser_read_visible" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).readVisible(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID")
                )
            );
            case "browser_drag" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).drag(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    requiredBuiltinText(firstArgument(arguments, "source_selector", "source_ref", "sourceRef"), "源CSS选择器", 1000),
                    requiredBuiltinText(firstArgument(arguments, "target_selector", "target_ref", "targetRef"), "目标CSS选择器", 1000)
                )
            );
            case "browser_download" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).download(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    requiredBuiltinText(firstArgument(arguments, "selector", "target_ref", "targetRef"), "CSS选择器", 1000)
                )
            );
            case "browser_scroll" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).scroll(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    optionalBuiltinInteger(firstArgument(arguments, "x"), -100000, 100000, "滚动横向距离"),
                    optionalBuiltinInteger(firstArgument(arguments, "y"), -100000, 100000, "滚动纵向距离"),
                    optionalBuiltinText(firstArgument(arguments, "selector"))
                )
            );
            case "browser_hover" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).hover(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    requiredBuiltinText(firstArgument(arguments, "selector"), "CSS选择器", 1000)
                )
            );
            case "browser_upload" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).upload(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    requiredBuiltinText(firstArgument(arguments, "selector"), "CSS选择器", 1000),
                    requiredBuiltinStringList(firstArgument(arguments, "files"), "文件路径")
                )
            );
            case "browser_tabs" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).tabs(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID")
                )
            );
            case "browser_tab_open" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).openTab(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    optionalBuiltinText(firstArgument(arguments, "url"))
                )
            );
            case "browser_tab_activate" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).activateTab(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    requiredBuiltinText(firstArgument(arguments, "tab_id", "tabId"), "浏览器标签页 ID", 255)
                )
            );
            case "browser_switch_tab" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).activateTab(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    requiredBuiltinText(firstArgument(arguments, "tab_id", "tabId"), "浏览器标签页 ID", 255)
                )
            );
            case "browser_tab_close" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).closeTab(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    requiredBuiltinText(firstArgument(arguments, "tab_id", "tabId"), "浏览器标签页 ID", 255)
                )
            );
            case "browser_close_tab" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).closeTab(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    requiredBuiltinText(firstArgument(arguments, "tab_id", "tabId"), "浏览器标签页 ID", 255)
                )
            );
            case "browser_human_handoff" -> browserBuiltin(key).runAsRuntimePrincipal(
                principalResolver.resolve(request), () -> browserBuiltin(key).requestHandoff(
                    positiveBuiltinLong(firstArgument(arguments, "session_id", "sessionId"), "浏览器会话ID"),
                    optionalBuiltinText(firstArgument(arguments, "reason"))
                )
            );
            case "get_my_tasks" -> myTasks(request, arguments);
            case "create_recurring_task" -> createRecurringTask(request, arguments);
            case "cancel_task" -> controlRecurringTask(request, arguments, "cancel");
            case "start_task" -> controlRecurringTask(request, arguments, "start");
            case "pause_task" -> controlRecurringTask(request, arguments, "pause");
            case "run_task_manually" -> controlRecurringTask(request, arguments, "run");
            case "list_available_skills" -> accessibleSkills(request, arguments);
            case "read_skill_instruction" -> readSkillInstruction(request, arguments);
            case "search_knowledge_base" -> searchKnowledgeBase(request, arguments);
            case "search_qa_examples" -> searchQaExamples(request, arguments);
            case "web_search_baidu", "web_search_baidu_http", "web_search_bing_http" ->
                webSearch(request, key, arguments);
            case "update_user_preference" -> updateUserPreference(request, arguments);
            case "memory_search" -> memorySearch(request, arguments);
            case "fetch_user_long_term_memory" -> fetchUserLongTermMemory(request, arguments);
            case "delete_user_preference" -> deleteUserPreference(request, arguments);
            case "send_portal_notification" -> sendPortalNotification(request, arguments);
            case "send_dingtalk_message", "send_email", "send_wechat_work_message" ->
                sendExternalNotification(request, key, arguments);
            case "update_dashboard_context" -> auxiliaryBuiltin(key).updateDashboardContext(
                principalResolver.resolve(request), request.conversationId(), arguments
            );
            case "jira_search", "jira_create_issue", "jira_get_projects" -> auxiliaryBuiltin(key).jira(
                principalResolver.resolve(request), key, arguments
            );
            case "sqlite_scratchpad" -> auxiliaryBuiltin(key).sqliteScratchpad(
                principalResolver.resolve(request), arguments
            );
            case "request_user_confirmation" -> auxiliaryBuiltin(key).requestUserConfirmation(
                principalResolver.resolve(request), request, arguments
            );
            case "ask_user_question" -> askUserQuestion(request, arguments);
            case "todo_write" -> auxiliaryBuiltin(key).todoWrite(
                principalResolver.resolve(request), arguments
            );
            case "sub_agent_call" -> auxiliaryBuiltin(key).delegate(
                principalResolver.resolve(request), request,
                request.executionKey().executionId(), request.conversationId(), arguments
            );
            case "sub_agent_batch_call" -> auxiliaryBuiltin(key).delegateBatch(
                principalResolver.resolve(request), request,
                request.executionKey().executionId(), request.conversationId(), arguments
            );
            case "excel_document_read" -> auxiliaryBuiltin(key).excelRead(
                principalResolver.resolve(request), arguments
            );
            case "excel_document_write" -> auxiliaryBuiltin(key).excelWrite(
                principalResolver.resolve(request), arguments
            );
            case "word_document_read" -> auxiliaryBuiltin(key).wordRead(
                principalResolver.resolve(request), arguments
            );
            case "word_document_write" -> auxiliaryBuiltin(key).wordWrite(
                principalResolver.resolve(request), arguments
            );
            case "create_skills" -> createSkill(request, arguments);
            case "exec_command", "list_process", "manage_process" ->
                executeSandboxBuiltin(request, frozen.id(), key, arguments);
            case "system_http_request" -> webToolSystemHttpRequest(arguments);
            case "fetch_static_web_url" -> webToolFetchStaticUrl(arguments);
            case "web_renderer_and_snapshot" -> webToolRender(arguments);
            case "code_syntax_linter" -> webToolLint(arguments);
            case "read_file" -> workspaceRead(request, arguments);
            case "write_file" -> workspaceWrite(request, arguments);
            case "search_text" -> workspaceSearchText(request, arguments);
            case "directory_tree_navigator" -> workspaceList(request, arguments);
            case "list_accessible_datasets" -> accessibleDatasets(request);
            case "list_available_agents" -> accessibleAgents(request);
            case "list_accessible_knowledge_bases" -> accessibleKnowledgeBases(request);
            default -> {
                String reason = BuiltinToolCatalog.contains(key)
                    ? "builtin tool executor is not configured"
                    : "unknown builtin tool";
                throw new ServiceException("tool_unavailable: " + key + " (" + reason + ")", 503);
            }
        };
        return envelope(data);
    }

    /**
     * 处理ask用户追问并返回对应结果。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> askUserQuestion(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (runtimeUserQuestionService == null) {
            throw builtinUnavailable(
                "ask_user_question", "runtime user-question service is not configured"
            );
        }
        CurrentPrincipal principal = principalResolver.resolve(request);
        if (!principal.isHuman()) {
            throw new ServiceException("服务账号不能向用户发起交互提问", HttpStatus.FORBIDDEN);
        }
        if (request.conversationId() == null || request.conversationId() <= 0) {
            throw new ServiceException("用户提问必须绑定个人会话", HttpStatus.BAD_REQUEST);
        }
        String question = requiredBuiltinText(
            firstArgument(arguments, "question", "prompt"), "问题内容", 2000
        );
        Object rawOptions = firstArgument(arguments, "options", "choices");
        if (!(rawOptions instanceof List<?> values) || values.size() < 2 || values.size() > 12) {
            throw new ServiceException("问题选项数量必须在2到12之间", HttpStatus.BAD_REQUEST);
        }
        List<Map<String, Object>> options = new ArrayList<>(values.size());
        for (Object raw : values) {
            if (!(raw instanceof Map<?, ?> source)) {
                throw new ServiceException("问题选项必须是对象", HttpStatus.BAD_REQUEST);
            }
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", requiredBuiltinText(source.get("id"), "选项ID", 128));
            option.put("label", requiredBuiltinText(source.get("label"), "选项名称", 500));
            String description = optionalText(source.get("description"));
            if (description != null) {
                if (description.length() > 1000) {
                    throw new ServiceException("选项说明超过长度限制", HttpStatus.BAD_REQUEST);
                }
                option.put("description", description);
            }
            options.add(option);
        }
        Integer expiresInSeconds = optionalBuiltinInteger(
            firstArgument(arguments, "expires_in_seconds", "expiresInSeconds"),
            60, 3600, "expiresInSeconds"
        );
        String toolCallId = optionalBuiltinText(firstArgument(
            arguments, "tool_call_id", "toolCallId"
        ));
        if (toolCallId == null) {
            toolCallId = optionalBuiltinText(firstArgument(
                request.attributes(), "toolCallId", "tool_call_id", "currentToolCallId"
            ));
        }
        String idempotencyKey = optionalBuiltinText(firstArgument(
            arguments, "idempotency_key", "idempotencyKey"
        ));
        if (idempotencyKey == null) {
            idempotencyKey = "runtime-user-question-"
                + ContentHashing.sha256(
                    request.executionKey().executionId() + ":" + String.valueOf(toolCallId)
                ).substring(0, 48);
        }
        String questionId = optionalBuiltinText(firstArgument(
            arguments, "question_id", "questionId"
        ));
        Long turnId = optionalBuiltinLong(firstArgument(
            request.attributes(), "conversationTurnId", "conversation_turn_id", "turnId"
        ));
        LocalDateTime expiresAt = expiresInSeconds == null
            ? null : LocalDateTime.now().plusSeconds(expiresInSeconds);
        UserQuestionView view = runtimeUserQuestionService.create(new UserQuestionCreateCommand(
            principal.id(), questionId, request.conversationId(),
            request.executionKey().executionId(), turnId, toolCallId, idempotencyKey,
            question, options,
            Boolean.TRUE.equals(firstArgument(arguments, "multi_select", "multiSelect")),
            Boolean.TRUE.equals(firstArgument(arguments, "allow_custom_input", "allowCustomInput")),
            optionalBuiltinText(firstArgument(arguments, "context")),
            optionalBuiltinText(firstArgument(arguments, "purpose")), expiresAt
        ));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", view.status());
        result.put("question_id", view.questionId());
        result.put("conversation_id", view.conversationId());
        result.put("conversation_turn_id", view.conversationTurnId());
        result.put("execution_id", view.executionId());
        result.put("tool_call_id", view.toolCallId());
        result.put("question", view.question());
        result.put("options", view.options());
        result.put("multi_select", view.multiSelect());
        result.put("allow_custom_input", view.allowCustomInput());
        result.put("context", view.context());
        result.put("purpose", view.purpose());
        result.put("expires_at", view.expiresAt());
        result.put("message", "已向用户展示问题，请等待回答后继续执行。");
        return result;
    }

    /**
     * 处理{@code auxiliaryBuiltin}并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 处理结果
     */
    private RuntimeAuxiliaryBuiltinService auxiliaryBuiltin(String key) {
        if (auxiliaryBuiltinService == null) {
            throw builtinUnavailable(key, "runtime auxiliary builtin service is not configured");
        }
        return auxiliaryBuiltinService;
    }

    /**
     * 处理浏览器Builtin并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 处理结果
     */
    private BrowserSessionApplicationService browserBuiltin(String key) {
        if (browserSessionService == null) {
            throw builtinUnavailable(key, "browser session service is not configured");
        }
        return browserSessionService;
    }

    /**
     * 处理web工具系统HttpRequest并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> webToolSystemHttpRequest(Map<String, Object> arguments) {
        if (webToolService == null) {
            throw builtinUnavailable("system_http_request", "web tool service is not configured");
        }
        return webToolService.systemHttpRequest(arguments);
    }

    /**
     * 处理web工具FetchStaticUrl并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> webToolFetchStaticUrl(Map<String, Object> arguments) {
        if (webToolService == null) {
            throw builtinUnavailable("fetch_static_web_url", "web tool service is not configured");
        }
        return webToolService.fetchStaticWebUrl(arguments);
    }

    /**
     * 处理web工具Render并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> webToolRender(Map<String, Object> arguments) {
        if (webToolService == null) {
            throw builtinUnavailable("web_renderer_and_snapshot", "web tool service is not configured");
        }
        return webToolService.renderAndSnapshot(arguments);
    }

    /**
     * 处理web工具Lint并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> webToolLint(Map<String, Object> arguments) {
        if (webToolService == null) {
            throw builtinUnavailable("code_syntax_linter", "web tool service is not configured");
        }
        return webToolService.lintSyntax(arguments);
    }

    /**
     * 创建并保存技能。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> createSkill(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        if (runtimeSkillService == null) {
            throw builtinUnavailable("create_skills", "runtime Skill service is not configured");
        }
        return runtimeSkillService.create(principalResolver.resolve(request), arguments);
    }

    /**
     * 执行沙箱Builtin相关的处理流程。
     *
     * @param request 请求参数
     * @param toolId 资源标识
     * @param builtin {@code builtin}参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> executeSandboxBuiltin(
        AgentRunRequest request,
        Long toolId,
        String builtin,
        Map<String, Object> arguments
    ) {
        requireSandboxSkillWorkspace(request);
        if (runtimeSandboxService == null) {
            throw builtinUnavailable(builtin, "runtime Sandbox service is not configured");
        }
        return runtimeSandboxService.execute(
            request, principalResolver.resolve(request), toolId, builtin, arguments
        );
    }

    /**
     * 处理builtin数据集Schema并返回对应结果。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Object builtinDatasetSchema(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        if (dataQueryProvider == null) {
            throw builtinUnavailable("get_dataset_schema", "data query provider is not configured");
        }
        return dataQueryProvider.schema(request, arguments);
    }

    /**
     * 处理builtinSql查询并返回对应结果。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Object builtinSqlQuery(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        if (dataQueryProvider == null) {
            throw builtinUnavailable("execute_sql_query", "data query provider is not configured");
        }
        return dataQueryProvider.executeBuiltin(request, arguments);
    }

    /**
     * 处理{@code builtinKey}并返回对应结果。
     *
     * @param frozen {@code frozen}参数
     * @return 处理结果
     */
    private String builtinKey(FrozenTool frozen) {
        Object handler = frozen.executionPolicy().get("handlerKey");
        String value = handler instanceof String text && !text.isBlank()
            ? text
            : frozen.externalName() == null || frozen.externalName().isBlank()
                ? frozen.toolKey() : frozen.externalName();
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
        return normalized.startsWith("builtin.")
            ? normalized.substring("builtin.".length()) : normalized;
    }

    /**
     * 处理{@code builtinAvailable}并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean builtinAvailable(String key) {
        if (!BuiltinToolCatalog.implemented(key)) {
            return false;
        }
        return switch (key) {
            case "get_dataset_schema", "execute_sql_query", "list_accessible_datasets" ->
                dataQueryProvider != null;
            case "search_knowledge_base", "list_accessible_knowledge_bases" ->
                knowledgeProvider != null;
            case "search_qa_examples" -> exampleSearchService != null;
            case "web_search_baidu", "web_search_baidu_http", "web_search_bing_http" ->
                webSearchService != null;
            case "update_user_preference" -> memoryMapper != null
                && memoryScopeAuthorization != null && idGenerator != null;
            case "memory_search", "fetch_user_long_term_memory", "delete_user_preference" ->
                memoryMapper != null && memoryScopeAuthorization != null;
            case "send_portal_notification" -> notificationService != null;
            case "send_dingtalk_message", "send_email", "send_wechat_work_message" ->
                notificationConfigService != null;
            case "update_dashboard_context", "jira_search", "jira_create_issue", "jira_get_projects",
                 "sqlite_scratchpad", "request_user_confirmation", "todo_write", "sub_agent_call",
                 "sub_agent_batch_call", "session_status", "read_image",
                 "excel_document_read", "excel_document_write", "word_document_read",
                 "word_document_write" -> auxiliaryBuiltinService != null;
            case "browser_open", "browser_navigate", "browser_snapshot", "browser_click",
                 "browser_fill", "browser_close", "browser_press", "browser_scroll", "browser_hover",
                 "browser_upload", "browser_tabs", "browser_tab_open", "browser_tab_activate",
                 "browser_tab_close", "browser_human_handoff", "browser_back", "browser_forward",
                 "browser_reload", "browser_wait_for", "browser_select_option", "browser_read_visible",
                 "browser_drag", "browser_download", "browser_switch_tab", "browser_close_tab" -> browserSessionService != null;
            case "ask_user_question" -> runtimeUserQuestionService != null;
            case "system_http_request", "fetch_static_web_url", "code_syntax_linter" ->
                webToolService != null;
            case "web_renderer_and_snapshot" ->
                webToolService != null && webToolService.chromiumAvailable();
            case "read_file", "write_file", "search_text", "directory_tree_navigator" ->
                workspaceService != null;
            case "get_my_tasks" -> taskMapper != null;
            case "create_recurring_task", "cancel_task", "start_task", "pause_task",
                 "run_task_manually" -> taskControlService != null;
            case "list_available_skills", "read_skill_instruction" -> true;
            case "create_skills" -> runtimeSkillService != null;
            case "exec_command", "list_process", "manage_process" -> runtimeSandboxService != null;
            default -> true;
        };
    }

    /**
     * 创建并保存Recurring任务。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> createRecurringTask(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        if (taskControlService == null) {
            throw builtinUnavailable("create_recurring_task", "task control service is not configured");
        }
        Object rawServiceAccountId = firstArgument(
            arguments, "service_account_id", "serviceAccountId"
        );
        Long serviceAccountId = rawServiceAccountId == null ? null
            : positiveBuiltinLong(rawServiceAccountId, "服务账号ID");
        return taskControlService.createRecurring(
            request,
            principalResolver.resolve(request),
            requiredBuiltinText(firstArgument(arguments, "name"), "周期任务名称"),
            requiredBuiltinText(firstArgument(arguments, "cron"), "Cron表达式"),
            requiredBuiltinText(
                firstArgument(arguments, "prompt"), "周期执行指令", 12000
            ),
            notificationChannels(firstArgument(
                arguments, "notification_channels", "notificationChannels"
            )),
            optionalBuiltinText(firstArgument(arguments, "timezone")),
            serviceAccountId
        );
    }

    /**
     * 处理controlRecurring任务并返回对应结果。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @param action {@code action}参数
     * @return 处理结果
     */
    private Map<String, Object> controlRecurringTask(
        AgentRunRequest request,
        Map<String, Object> arguments,
        String action
    ) {
        if (taskControlService == null) {
            throw builtinUnavailable(action + "_task", "task control service is not configured");
        }
        CurrentPrincipal principal = principalResolver.resolve(request);
        Long taskId = positiveBuiltinLong(firstArgument(arguments, "task_id", "taskId"), "周期任务ID");
        return switch (action) {
            case "cancel" -> taskControlService.cancel(principal, taskId);
            case "start" -> taskControlService.start(principal, taskId);
            case "pause" -> taskControlService.pause(principal, taskId);
            case "run" -> taskControlService.runManually(request, principal, taskId);
            default -> throw new IllegalStateException("unknown recurring task action");
        };
    }

    /**
     * 处理当前Time并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> currentTime(Map<String, Object> arguments) {
        String timezone = optionalText(arguments == null ? null : arguments.get("timezone"));
        ZoneId zone;
        try {
            zone = timezone == null ? ZoneOffset.UTC : ZoneId.of(timezone);
        } catch (RuntimeException exception) {
            throw new ServiceException("timezone 无效", HttpStatus.BAD_REQUEST);
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        return Map.of(
            "iso", now.toOffsetDateTime().toString(),
            "timezone", zone.getId(),
            "epoch_ms", now.toInstant().toEpochMilli(),
            "date", now.toLocalDate().toString(),
            "time", now.toLocalTime().toString()
        );
    }

    /**
     * 处理{@code relativeDates}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> relativeDates(Map<String, Object> arguments) {
        String expression = requiredBuiltinText(
            arguments == null ? null : arguments.get("expression"), "相对日期表达式"
        ).toLowerCase(Locale.ROOT);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start;
        LocalDate end;
        switch (expression) {
            case "today", "今天" -> start = end = today;
            case "yesterday", "昨天" -> start = end = today.minusDays(1);
            case "tomorrow", "明天" -> start = end = today.plusDays(1);
            case "this_week", "本周" -> {
                start = today.minusDays(today.getDayOfWeek().getValue() - 1L);
                end = start.plusDays(6);
            }
            case "last_week", "上周" -> {
                end = today.minusDays(today.getDayOfWeek().getValue());
                start = end.minusDays(6);
            }
            default -> throw new ServiceException("无法解析相对日期表达式", HttpStatus.BAD_REQUEST);
        }
        return Map.of(
            "expression", expression,
            "timezone", "UTC",
            "start_date", start.toString(),
            "end_date", end.toString()
        );
    }

    /**
     * 处理{@code myInfo}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private Map<String, Object> myInfo(AgentRunRequest request) {
        CurrentPrincipal principal = principalResolver.resolve(request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("principal_id", principal.id());
        data.put("principal_type", principal.type().name().toLowerCase(Locale.ROOT));
        data.put("roles", principal.roles().stream().map(PlatformRole::key).sorted().toList());
        data.put("agent_version_id", request.agentVersionId());
        data.put("agent_name", request.agentName());
        data.put("session_id", request.sessionId());
        data.put("execution_id", request.executionKey().executionId());
        data.put("trace_id", request.executionKey().traceId());
        putIfPresent(data, "conversation_id", request.conversationId());
        putIfPresent(data, "task_id", request.taskId());
        putIfPresent(data, "run_id", request.runId());
        putIfPresent(data, "step_id", request.stepId());
        putIfPresent(data, "workspace_key", request.workspaceKey());
        return data;
    }

    /**
     * 处理当前模型并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private Map<String, Object> currentModel(AgentRunRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "available");
        data.put("provider", request.model().provider());
        data.put("model_id", request.model().modelName());
        data.put("agent_version_id", request.agentVersionId());
        data.put("agent_name", request.agentName());
        data.put("execution_id", request.executionKey().executionId());
        data.put("trace_id", request.executionKey().traceId());
        data.put("phase", request.stepId() == null ? "conversation_turn" : "task_step");
        return data;
    }

    /**
     * 处理{@code accessibleDatasets}并返回对应结果。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> accessibleDatasets(AgentRunRequest request) {
        if (dataQueryProvider == null) {
            throw builtinUnavailable("list_accessible_datasets", "data provider is not configured");
        }
        List<Map<String, Object>> catalog = dataQueryProvider.accessibleCatalog(request);
        return catalog == null ? frozenResourceCatalog(request, Set.of("dataset")) : catalog;
    }

    /**
     * 处理accessible知识库Bases并返回对应结果。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> accessibleKnowledgeBases(AgentRunRequest request) {
        if (knowledgeProvider == null) {
            throw builtinUnavailable(
                "list_accessible_knowledge_bases", "knowledge provider is not configured"
            );
        }
        List<Map<String, Object>> catalog = knowledgeProvider.accessibleCatalog(request);
        return catalog == null ? frozenResourceCatalog(
            request, Set.of("knowledge", "knowledge_base", "knowledgeBase")
        ) : catalog;
    }

    /**
 * 处理{@code accessibleAgents}并返回对应结果。
 *
     * Returns the bounded, owner-authorized published Agent catalog for the current run.
     * Discovery never grants access; every candidate is rechecked with the same use
     * permission used by runtime materialization.
     */
    private List<Map<String, Object>> accessibleAgents(AgentRunRequest request) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (agentCatalogMapper == null) {
            throw builtinUnavailable("list_available_agents", "agent catalog provider is not configured");
        }
        CurrentPrincipal principal = principalResolver.resolve(request);
        List<EmbedAgentRuntimeRow> candidates = agentCatalogMapper.selectPublishedAgentRuntimes();
        if (candidates == null) return List.of();
        Long currentAgentId = currentAgentId(request);
        List<Map<String, Object>> result = new ArrayList<>();
        for (EmbedAgentRuntimeRow candidate : candidates) {
            if (!"active".equals(candidate.getAgentStatus()) || candidate.getPublishedAt() == null) {
                continue;
            }
            AuthorizationDecision decision = authorizationEnforcer.decide(principal, new PermissionContext(
                "agent_version", candidate.getAgentVersionId(), candidate.getAgentKey(), "use",
                ResourceState.ACTIVE, true, Set.of(), null
            ));
            if (decision == null || !decision.allowed()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agent_id", candidate.getAgentId());
            item.put("agent_version_id", candidate.getAgentVersionId());
            item.put("agent_name", candidate.getAgentKey());
            item.put("display_name", candidate.getAgentName());
            item.put("description", candidate.getAgentDescription());
            item.put("capabilities", routingTags(candidate.getRoutingTagsJson()));
            item.put("is_current", currentAgentId != null
                ? currentAgentId.equals(candidate.getAgentId())
                : candidate.getAgentVersionId().equals(request.agentVersionId()));
            result.add(item);
        }
        return result;
    }

    /**
     * 处理当前智能体Id并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private Long currentAgentId(AgentRunRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Object rawDecision = request.attributes().get("routingDecision");
        if (!(rawDecision instanceof Map<?, ?> decision)) return null;
        Object rawId = decision.get("agentId");
        if (rawId instanceof Number number && number.longValue() > 0) return number.longValue();
        if (rawId instanceof String text && text.matches("[1-9][0-9]{0,18}")) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 处理{@code routingTags}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 符合条件的数据集合
     */
    private List<String> routingTags(String raw) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (raw == null || raw.isBlank()) return List.of();
        try {
            Object parsed = jsonMapper.readValue(raw, Object.class);
            if (parsed instanceof List<?> values) {
                return values.stream().filter(String.class::isInstance)
                    .map(value -> ((String) value).strip())
                    .filter(value -> !value.isBlank()).limit(32).toList();
            }
            if (parsed instanceof Map<?, ?> values) {
                Object tags = values.get("tags");
                if (tags instanceof List<?> list) {
                    return list.stream().filter(String.class::isInstance)
                        .map(value -> ((String) value).strip())
                        .filter(value -> !value.isBlank()).limit(32).toList();
                }
            }
        } catch (RuntimeException ignored) {
            // Malformed optional routing metadata must not block the authorized catalog.
        }
        return List.of();
    }

    /**
 * 处理{@code accessibleSkills}并返回对应结果。
 *
     * Lists only Skill versions already frozen into this Agent run. Runtime discovery must not
     * query the current Skill catalog because that could enlarge a task's authorized scope.
     */
    private List<Map<String, Object>> accessibleSkills(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (arguments == null || !arguments.isEmpty()) {
            throw new ServiceException(
                "list_available_skills 不接受参数", HttpStatus.BAD_REQUEST
            );
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (FrozenSkill skill : frozenSkills(request)) {
            if (!hasTaskSkillGrant(request, skill.id())) {
                continue;
            }
            if (result.size() >= MAX_SKILL_CATALOG_ENTRIES) {
                throw skillUnavailable(null, "冻结 Skill 数量超过运行时上限");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", skill.skillKey());
            item.put("key", skill.skillKey());
            item.put("name", skill.name());
            item.put("description", skill.description() == null ? "" : skill.description());
            item.put("scope", skill.scope());
            item.put("resource_id", skill.id());
            item.put("version", skill.versionNo());
            item.put("version_no", skill.versionNo());
            item.put("hash", skill.contentHash());
            item.put("content_hash", skill.contentHash());
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    /**
 * 处理read技能Instruction并返回对应结果。
 * Reads the frozen SKILL.md content and dependency declarations without rehydrating a Skill. */
    private Map<String, Object> readSkillInstruction(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (arguments == null || !arguments.keySet().equals(Set.of("skill_id"))) {
            throw new ServiceException(
                "skill_unavailable: read_skill_instruction 参数无效", HttpStatus.BAD_REQUEST
            );
        }
        String skillKey = requiredSkillId(arguments.get("skill_id"));
        FrozenSkill skill = frozenSkills(request).stream()
            .filter(item -> skillKey.equals(item.skillKey()))
            .findFirst()
            .orElseThrow(() -> forbidden("Skill 不在 Agent 冻结资源中"));
        if (!hasTaskSkillGrant(request, skill.id())) {
            throw forbidden("Skill 不在当前运行的冻结授权中");
        }
        String content = skill.content();
        if (content == null || content.isBlank()) {
            throw skillUnavailable(skillKey, "冻结 Skill 缺少 SKILL.md 指令");
        }
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_SKILL_INSTRUCTION_BYTES) {
            throw skillUnavailable(skillKey, "SKILL.md 超过单次读取大小限制");
        }
        Map<String, Object> dependencies = new LinkedHashMap<>();
        putIfPresent(dependencies, "required_tool_keys", skill.manifest().get("requiredToolKeys"));
        putIfPresent(
            dependencies, "required_tool_ids", skill.runtimeRequirements().get("requiredToolIds")
        );
        putIfPresent(
            dependencies, "required_knowledge_base_ids",
            skill.runtimeRequirements().get("requiredKnowledgeBaseIds")
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill_id", skill.skillKey());
        result.put("key", skill.skillKey());
        result.put("resource_id", skill.id());
        result.put("name", skill.name());
        result.put("scope", skill.scope());
        result.put("version", skill.versionNo());
        result.put("version_no", skill.versionNo());
        result.put("hash", skill.contentHash());
        result.put("content_hash", skill.contentHash());
        result.put("content", content);
        result.put("instruction", content);
        result.put("manifest", skill.manifest());
        result.put("runtime_requirements", skill.runtimeRequirements());
        result.put("dependencies", dependencies);
        result.put("runtime_mount", "skills/" + skill.skillKey());
        result.put("bundle_roots", List.of("scripts", "assets", "references"));
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * 处理{@code frozenSkills}并返回对应结果。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    private List<FrozenSkill> frozenSkills(AgentRunRequest request) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Object rawBindings = request.attributes().get("resourceBindings");
        if (!(rawBindings instanceof List<?> bindings)) {
            throw new SecurityException("运行快照缺少 Agent 资源绑定");
        }
        List<FrozenSkill> result = new ArrayList<>();
        for (Object value : bindings) {
            if (!(value instanceof Map<?, ?> raw) || !"skill".equals(raw.get("resourceType"))) {
                continue;
            }
            Map<String, Object> binding = stringMap(raw);
            Long id = positiveLong(binding.get("resourceId"), "Skill 资源 ID");
            String permission = optionalText(binding.get("permission"));
            if (!SKILL_NHS_BINDING_PERMISSION.equals(permission)) {
                throw skillUnavailable(null, "冻结 Skill 绑定权限无效");
            }
            Map<String, Object> config = requiredMap(binding.get("config"), "Skill 绑定配置");
            Map<String, Object> snapshot = requiredMap(
                config.get("resourceSnapshot"), "Skill 资源快照"
            );
            String skillKey = frozenSkillKey(snapshot.get("skillKey"));
            String name = optionalText(snapshot.get("name"));
            if (name == null || name.length() > MAX_SKILL_ID_LENGTH) {
                throw skillUnavailable(skillKey, "冻结 Skill 名称无效");
            }
            String scopeType = optionalText(snapshot.get("scopeType"));
            if (scopeType == null || !SKILL_SCOPES.contains(scopeType)) {
                throw skillUnavailable(skillKey, "冻结 Skill 作用域无效");
            }
            Map<String, Object> manifest = optionalMap(snapshot.get("manifest"));
            String description = optionalText(snapshot.get("description"));
            if (description == null) {
                description = optionalText(manifest.get("summary"));
            }
            String content = snapshot.get("content") instanceof String text ? text : null;
            result.add(new FrozenSkill(
                id, skillKey, name, description, skillScope(scopeType),
                positiveInteger(snapshot.get("versionNo"), "Skill 版本号"),
                optionalText(snapshot.get("contentHash")), content,
                manifest, optionalMap(snapshot.get("runtimeRequirements"))
            ));
        }
        return List.copyOf(result);
    }

    /**
     * 判断任务技能Grant是否满足要求。
     *
     * @param request 请求参数
     * @param skillId 资源标识
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean hasTaskSkillGrant(AgentRunRequest request, Long skillId) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Map<String, Object> snapshot = requiredMap(
            request.attributes().get("taskResourceSnapshot"), "任务资源快照"
        );
        Long agentVersionId = positiveLong(snapshot.get("agentVersionId"), "任务 Agent 版本 ID");
        if (!request.agentVersionId().equals(agentVersionId)) {
            throw new SecurityException("任务资源快照与 Agent 版本不一致");
        }
        if (!(snapshot.get("resources") instanceof List<?> resources)) {
            throw new SecurityException("任务资源快照缺少授权资源");
        }
        Set<String> permissions = request.taskId() == null
            ? SKILL_SESSION_PERMISSIONS : SKILL_TASK_PERMISSIONS;
        for (Object value : resources) {
            if (!(value instanceof Map<?, ?> raw) || !"skill".equals(raw.get("resourceType"))) {
                continue;
            }
            Map<String, Object> resource = stringMap(raw);
            Long id = optionalPositiveLong(resource.get("resourceId"), "冻结 Skill 资源 ID");
            if (id == null) {
                throw new SecurityException("冻结 Skill 授权无效");
            }
            String permission = optionalText(resource.get("permission"));
            if (skillId.equals(id) && permissions.contains(permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验技能Id，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String requiredSkillId(Object value) {
        String skillKey = optionalText(value);
        if (skillKey == null || skillKey.length() > MAX_SKILL_ID_LENGTH
            || !skillKey.matches("[a-z][a-z0-9._-]{0,127}")) {
            throw new ServiceException("skill_unavailable: skill_id 无效", HttpStatus.BAD_REQUEST);
        }
        return skillKey;
    }

    /**
     * 处理frozen技能Key并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String frozenSkillKey(Object value) {
        String skillKey = optionalText(value);
        if (skillKey == null || skillKey.length() > MAX_SKILL_ID_LENGTH
            || !skillKey.matches("[a-z][a-z0-9._-]{0,127}")) {
            throw skillUnavailable(null, "冻结 Skill 标识无效");
        }
        return skillKey;
    }

    /**
     * 处理技能范围并返回对应结果。
     *
     * @param scopeType 业务类型
     * @return 处理结果
     */
    private String skillScope(String scopeType) {
        return switch (scopeType) {
            case "system", "global" -> "global";
            case "user", "personal" -> "personal";
            case "project" -> "project";
            default -> throw skillUnavailable(null, "冻结 Skill 作用域无效");
        };
    }

    /**
 * 处理frozen资源目录并返回对应结果。
 * Compatibility fallback for non-Spring embedders; the production providers return a strict catalog. */
    private List<Map<String, Object>> frozenResourceCatalog(
        AgentRunRequest request, Set<String> resourceTypes
    ) {
        Object rawSnapshot = request.attributes().get("taskResourceSnapshot");
        if (!(rawSnapshot instanceof Map<?, ?> snapshot)
            || !(snapshot.get("resources") instanceof List<?> resources)) {
            throw new SecurityException("任务资源快照缺少可见资源");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object raw : resources) {
            if (!(raw instanceof Map<?, ?> source)
                || !resourceTypes.contains(String.valueOf(source.get("resourceType")))) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("resource_id", source.get("resourceId"));
            value.put("resource_type", source.get("resourceType"));
            value.put("permission", source.get("permission"));
            result.add(Map.copyOf(value));
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code myTasks}并返回对应结果。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> myTasks(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (taskMapper == null) {
            throw builtinUnavailable("get_my_tasks", "task catalog is not configured");
        }
        CurrentPrincipal principal = principalResolver.resolve(request);
        AuthorizationDecision listDecision = authorizationEnforcer.decide(
            principal, new PermissionContext(
                "task", null, null, "list", ResourceState.ACTIVE,
                principal.isHuman(), Set.of(), request.taskId()
            )
        );
        if (!listDecision.allowed()) {
            throw forbidden("当前主体没有任务目录权限：" + listDecision.reasonCode());
        }
        String status = optionalBuiltinText(firstArgument(arguments, "status"));
        if (status != null && !Set.of(
            "draft", "ready", "scheduled", "running", "verifying", "rework",
            "completed", "blocked", "cancelled", "archived"
        ).contains(status)) {
            throw new ServiceException("status 无效", HttpStatus.BAD_REQUEST);
        }
        int limit = optionalBuiltinInteger(
            firstArgument(arguments, "limit"), 1, 100, "limit", 50
        );
        String principalType = principal.type() == PrincipalType.HUMAN
            ? "user" : "service_account";
        List<AgentTask> tasks = taskMapper.selectVisibleTasks(
            principal.id(), principalType, principal.isHuman(),
            principal.hasRole(PlatformRole.MEMBER),
            principal.hasRole(PlatformRole.APPROVAL_USER),
            principal.hasRole(PlatformRole.PLATFORM_ADMIN),
            limit
        );
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentTask task : tasks) {
            if (status != null && !status.equals(task.getStatus())) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", task.getId());
            value.put("task_key", task.getTaskKey());
            value.put("title", task.getTitle());
            putIfPresent(value, "objective", task.getObjective());
            value.put("status", task.getStatus());
            value.put("visibility", task.getVisibility());
            putIfPresent(value, "category", task.getCategory());
            value.put("owned_by_current_principal", principal.id().equals(task.getOwnerId())
                && principalType.equals(task.getOwnerPrincipalType()));
            putIfPresent(value, "start_at", task.getStartAt());
            putIfPresent(value, "current_version_id", task.getCurrentVersionId());
            putIfPresent(value, "latest_run_id", task.getLatestRunId());
            putIfPresent(value, "created_at", task.getCreateTime());
            result.add(Map.copyOf(value));
        }
        return List.copyOf(result);
    }

    /**
     * 查询知识库Base列表。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Object searchKnowledgeBase(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        if (knowledgeProvider == null) {
            throw builtinUnavailable("search_knowledge_base", "local knowledge provider is not configured");
        }
        Long baseId = knowledgeBaseIdArgument(arguments);
        String query = requiredBuiltinText(
            firstArgument(arguments, "query", "question"), "知识检索问题"
        );
        Integer topK = optionalBuiltinInteger(
            firstArgument(arguments, "top_k", "topK"), 1, 20, "topK"
        );
        return knowledgeProvider.search(request, baseId, query, topK);
    }

    /**
     * 查询{@code QaExamples}列表。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Object searchQaExamples(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        if (exampleSearchService == null) {
            throw builtinUnavailable("search_qa_examples", "local example index is not configured");
        }
        String query = requiredBuiltinText(
            firstArgument(arguments, "query", "question", "user_query"), "案例检索问题"
        );
        Long datasetId = optionalBuiltinLong(
            firstArgument(arguments, "dataset_id", "datasetId")
        );
        Integer topK = optionalBuiltinInteger(
            firstArgument(arguments, "top_k", "topK"), 1, 20, "topK"
        );
        return exampleSearchService.search(request, query, datasetId, topK);
    }

    /**
     * 处理{@code webSearch}并返回对应结果。
     *
     * @param request 请求参数
     * @param toolKey 工具Key参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Object webSearch(
        AgentRunRequest request,
        String toolKey,
        Map<String, Object> arguments
    ) {
        if (webSearchService == null) {
            throw builtinUnavailable(toolKey, "web search provider is not configured");
        }
        String query = requiredBuiltinText(
            firstArgument(arguments, "query", "question", "q"), "联网搜索关键词"
        );
        Integer maximum = optionalBuiltinInteger(
            firstArgument(arguments, "max_results", "maxResults", "count"),
            1, 20, "maxResults"
        );
        CurrentPrincipal principal = principalResolver.resolve(request);
        return webSearchService.runtimeSearch(
            principal, toolKey, query, maximum,
            request.runId() == null ? null : String.valueOf(request.runId()),
            request.executionKey().traceId()
        );
    }

    /**
     * 处理知识库BaseIdArgument并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Long knowledgeBaseIdArgument(Map<String, Object> arguments) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Object raw = firstArgument(
            arguments, "knowledge_base_id", "knowledgeBaseId", "base_id", "baseId", "dataset_ids"
        );
        if (raw instanceof List<?> values) {
            if (values.size() != 1) {
                throw new ServiceException(
                    "本地知识检索一次只能指定一个知识库", HttpStatus.BAD_REQUEST
                );
            }
            raw = values.getFirst();
        }
        if (raw instanceof String text) {
            String normalized = text.strip();
            if (normalized.contains(",")) {
                throw new ServiceException(
                    "本地知识检索一次只能指定一个知识库", HttpStatus.BAD_REQUEST
                );
            }
            try {
                raw = Long.valueOf(normalized);
            } catch (NumberFormatException exception) {
                throw new ServiceException("knowledgeBaseId 无效", HttpStatus.BAD_REQUEST);
            }
        }
        return positiveBuiltinLong(raw, "knowledgeBaseId");
    }

    /**
     * 处理rejectForeign用户Id相关逻辑。
     *
     * @param principal 当前操作主体
     * @param arguments {@code arguments}参数
     */
    private void rejectForeignUserId(CurrentPrincipal principal, Map<String, Object> arguments) {
        Object raw = firstArgument(arguments, "user_id", "userId");
        if (raw == null) {
            return;
        }
        Long userId = optionalBuiltinLong(raw);
        if (userId == null || !principal.id().equals(userId)) {
            throw new ServiceException("运行主体不能访问其他用户的记忆", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 更新用户Preference。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> updateUserPreference(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (memoryMapper == null || memoryScopeAuthorization == null || idGenerator == null) {
            throw builtinUnavailable(
                "update_user_preference", "memory provider is not configured"
            );
        }
        requireMemoryEnabled("update_user_preference");
        CurrentPrincipal principal = principalResolver.resolve(request);
        rejectForeignUserId(principal, arguments);
        requireMemoryView(principal, "user", principal.id());
        String key = requiredMemoryKey(firstArgument(
            arguments, "memory_key", "memoryKey", "key", "preference"
        ));
        String content = requiredMemoryContent(firstArgument(arguments, "content", "value"));
        AgentMemory memory = memoryMapper.selectByScopeAndKey("user", principal.id(), key);
        boolean created = memory == null;
        LocalDateTime now = LocalDateTime.now();
        if (created) {
            memory = new AgentMemory();
            memory.setId(idGenerator.nextId());
            memory.setMemoryKey(key);
            memory.setScopeType("user");
            memory.setScopeId(principal.id());
            memory.setRevisionNo(1L);
            memory.setCreatedBy(principal.id());
            memory.setCreatedAt(now);
            memory.setDelFlag("0");
        } else {
            memoryMapper.lockById(memory.getId());
            memory = memoryMapper.selectById(memory.getId());
            if (memory == null || !"user".equals(memory.getScopeType())
                || !principal.id().equals(memory.getScopeId())) {
                throw new ServiceException("个人偏好不存在", HttpStatus.NOT_FOUND);
            }
        }
        memory.setMemoryType("preference");
        memory.setContent(content);
        memory.setContentHash(ContentHashing.sha256(content));
        memory.setSourceType("manual");
        memory.setSourceId(null);
        memory.setConfidence(1D);
        memory.setSensitiveLevel("internal");
        memory.setReviewStatus("approved");
        memory.setReviewedBy(principal.id());
        memory.setReviewedAt(now);
        memory.setReviewComment("运行时用户显式保存");
        memory.setExpiresAt(null);
        memory.setMetadataJson("{}");
        memory.setUpdatedAt(now);
        if (memory.getRevisionNo() == null || memory.getRevisionNo() <= 0) {
            memory.setRevisionNo(1L);
        }
        if (memory.getCreatedAt() == null) {
            memory.setCreatedAt(now);
        }
        if (created) {
            if (memoryMapper.insertMemory(memory) != 1) {
                throw new ServiceException("个人偏好写入失败", HttpStatus.CONFLICT);
            }
        } else if (memoryMapper.updateMemory(memory) != 1) {
            throw new ServiceException("个人偏好已被其他请求修改", HttpStatus.CONFLICT);
        }
        if (!created) {
            memory.setRevisionNo(memory.getRevisionNo() + 1);
        }
        return Map.of(
            "memory_key", key, "updated", true, "revision_no", memory.getRevisionNo()
        );
    }

    /**
     * 删除用户Preference。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> deleteUserPreference(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (memoryMapper == null || memoryScopeAuthorization == null) {
            throw builtinUnavailable(
                "delete_user_preference", "memory provider is not configured"
            );
        }
        requireMemoryEnabled("delete_user_preference");
        CurrentPrincipal principal = principalResolver.resolve(request);
        rejectForeignUserId(principal, arguments);
        requireMemoryView(principal, "user", principal.id());
        Long memoryId = optionalBuiltinLong(firstArgument(arguments, "memory_id", "memoryId", "id"));
        String key = optionalBuiltinText(firstArgument(
            arguments, "memory_key", "memoryKey", "key", "preference"
        ));
        AgentMemory memory = memoryId == null
            ? (key == null ? null : memoryMapper.selectByScopeAndKey("user", principal.id(), key))
            : memoryMapper.selectById(memoryId);
        if (memory == null || !"user".equals(memory.getScopeType())
            || !principal.id().equals(memory.getScopeId())) {
            throw new ServiceException("个人偏好不存在", HttpStatus.NOT_FOUND);
        }
        memoryMapper.lockById(memory.getId());
        Long expectedRevision = optionalBuiltinLong(firstArgument(
            arguments, "expected_revision", "expectedRevision", "revision_no", "revisionNo"
        ));
        if (expectedRevision == null) {
            expectedRevision = memory.getRevisionNo();
        }
        if (memoryMapper.softDelete(memory.getId(), expectedRevision, LocalDateTime.now()) != 1) {
            throw new ServiceException("个人偏好已被其他请求修改", HttpStatus.CONFLICT);
        }
        return Map.of("memory_id", memory.getId(), "deleted", true);
    }

    /**
     * 校验记忆Key，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String requiredMemoryKey(Object value) {
        String key = requiredBuiltinText(value, "偏好标识").toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z][a-z0-9._-]{0,127}")) {
            throw new ServiceException("偏好标识无效", HttpStatus.BAD_REQUEST);
        }
        return key;
    }

    /**
     * 校验记忆Content，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String requiredMemoryContent(Object value) {
        if (!(value instanceof String content) || content.isBlank()
            || content.length() > 4000 || content.indexOf('\0') >= 0) {
            throw new ServiceException("偏好内容为空或超过长度限制", HttpStatus.BAD_REQUEST);
        }
        return content.strip();
    }

    /**
     * 处理记忆Search并返回对应结果。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> memorySearch(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (memoryMapper == null || memoryScopeAuthorization == null) {
            throw builtinUnavailable("memory_search", "memory provider is not configured");
        }
        requireMemoryEnabled("memory_search");
        CurrentPrincipal principal = principalResolver.resolve(request);
        String query = requiredBuiltinText(
            firstArgument(arguments, "query", "keyword"), "记忆搜索词"
        );
        String scopeType = optionalBuiltinText(firstArgument(
            arguments, "scope_type", "scopeType", "scope"
        ));
        if (scopeType == null) {
            scopeType = "user";
        } else {
            scopeType = scopeType.toLowerCase(Locale.ROOT);
            if ("summary".equals(scopeType) || "preference".equals(scopeType)) {
                scopeType = "user";
            }
        }
        Long scopeId = optionalBuiltinLong(firstArgument(arguments, "scope_id", "scopeId"));
        if (scopeId == null) {
            if (!"user".equals(scopeType)) {
                throw new ServiceException("非用户 Memory 必须指定作用域 ID", HttpStatus.BAD_REQUEST);
            }
            scopeId = principal.id();
        }
        requireMemoryView(principal, scopeType, scopeId);
        int limit = optionalBuiltinInteger(
            firstArgument(arguments, "limit", "top_k", "topK"), 1, 50, "limit", 20
        );
        List<AgentMemory> memories = memoryMapper.selectScopeMemories(
            scopeType, scopeId, false, query, limit
        );
        Map<Long, Map<String, Object>> merged = new LinkedHashMap<>();
        if (memories != null) {
            for (AgentMemory memory : memories) {
                if (!runtimeMemoryUsable(memory)) {
                    continue;
                }
                Map<String, Object> row = memoryResult(memory);
                row.put("lexical_score", 1.0D);
                row.put("score", 1.0D);
                row.put("retrieval_mode", "lexical");
                merged.put(memory.getId(), row);
            }
        }

        boolean vectorUsed = false;
        boolean degraded = false;
        if (memoryVectorService != null) {
            try {
                MemoryVectorApplicationService.Settings settings = memoryVectorService.settings();
                if (settings.embeddingEnabled()) {
                    Map<String, Object> vectorStatus = memoryVectorService.vectorStoreStatus(
                        scopeType, scopeId
                    );
                    if (!Boolean.TRUE.equals(vectorStatus.get("available"))) {
                        degraded = true;
                    } else {
                        vectorUsed = true;
                        double vectorWeight = Math.max(0D, Math.min(1D, settings.vectorWeight()));
                        for (MemoryVectorApplicationService.SearchHit hit : memoryVectorService.search(
                            scopeType, scopeId, query, limit
                        )) {
                            Map<String, Object> row = merged.get(hit.id());
                            if (row == null) {
                                row = memoryVectorResult(hit, scopeType, scopeId);
                                merged.put(hit.id(), row);
                            }
                            double vectorScore = boundedScore(hit.finalScore());
                            double lexicalScore = numericScore(row.get("lexical_score"));
                            row.put("vector_score", boundedScore(hit.vectorScore()));
                            row.put("final_score", vectorScore);
                            row.put("score", vectorWeight * vectorScore
                                + (1D - vectorWeight) * lexicalScore);
                            row.put("retrieval_mode", lexicalScore > 0D ? "hybrid" : "vector");
                        }
                    }
                }
            } catch (ServiceException exception) {
                if (exception.getCode() != null && exception.getCode() < 500) {
                    throw exception;
                }
                degraded = true;
            } catch (RuntimeException exception) {
                // A provider/pgvector failure must remain visible as degraded lexical recall.
                degraded = true;
            }
        }

        for (Map<String, Object> row : merged.values()) {
            row.put("retrieval_degraded", degraded);
            if (degraded && !vectorUsed) {
                row.put("retrieval_mode", "lexical_degraded");
            }
        }
        if (degraded && merged.isEmpty()) {
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("status", "degraded");
            marker.put("retrieval_degraded", true);
            marker.put("retrieval_mode", "lexical_degraded");
            marker.put("message", "向量检索当前不可用，未找到词法匹配");
            return List.of(marker);
        }
        return merged.values().stream()
            .sorted((left, right) -> Double.compare(
                numericScore(right.get("score")), numericScore(left.get("score"))
            ))
            .limit(limit)
            .toList();
    }

    /**
     * 处理fetch用户LongTerm记忆并返回对应结果。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> fetchUserLongTermMemory(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        if (memoryMapper == null || memoryScopeAuthorization == null) {
            throw builtinUnavailable(
                "fetch_user_long_term_memory", "memory provider is not configured"
            );
        }
        requireMemoryEnabled("fetch_user_long_term_memory");
        CurrentPrincipal principal = principalResolver.resolve(request);
        rejectForeignUserId(principal, arguments);
        requireMemoryView(principal, "user", principal.id());
        int limit = optionalBuiltinInteger(
            firstArgument(arguments, "limit", "top_k", "topK"), 1, 50, "limit", 20
        );
        String query = optionalBuiltinText(firstArgument(arguments, "query", "keyword"));
        List<AgentMemory> memories = query == null
            ? memoryMapper.selectApprovedForSnapshot("user", principal.id(), limit)
            : memoryMapper.selectScopeMemories("user", principal.id(), false, query, limit);
        return memories == null ? List.of() : memories.stream()
            .filter(this::runtimeMemoryUsable)
            .map(this::memoryResult)
            .toList();
    }

    /**
     * 处理send门户通知并返回对应结果。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> sendPortalNotification(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        if (notificationService == null) {
            throw builtinUnavailable(
                "send_portal_notification", "notification provider is not configured"
            );
        }
        CurrentPrincipal principal = principalResolver.resolve(request);
        if (!principal.isHuman()) {
            throw new ServiceException("服务账号不能发送个人站内通知", HttpStatus.FORBIDDEN);
        }
        String eventKey = optionalBuiltinText(firstArgument(
            arguments, "event_key", "eventKey"
        ));
        if (eventKey == null) {
            eventKey = "runtime:" + request.executionKey().executionId()
                + ":" + String.valueOf(request.stepId()) + ":" + request.runId();
        }
        NotificationMessage message = new NotificationMessage(
            eventKey,
            optionalBuiltinText(firstArgument(arguments, "category")) == null
                ? "system" : optionalBuiltinText(firstArgument(arguments, "category")),
            optionalBuiltinText(firstArgument(arguments, "level")) == null
                ? "info" : optionalBuiltinText(firstArgument(arguments, "level")),
            requiredBuiltinText(firstArgument(arguments, "title"), "通知标题"),
            optionalBuiltinText(firstArgument(arguments, "content", "message")),
            optionalBuiltinText(firstArgument(arguments, "resource_type", "resourceType")),
            optionalBuiltinLong(firstArgument(arguments, "resource_id", "resourceId"))
        );
        notificationService.publish(
            new NotificationRecipient(principal.id(), principal.type()), message
        );
        return Map.of("event_key", eventKey, "recipient_id", principal.id(), "delivered", true);
    }

    /**
 * 处理sendExternal通知并返回对应结果。
 *
     * Sends a notification through the frozen human owner's personal channel configuration.
     * Credentials are loaded by UserNotificationConfigService and are never accepted from the
     * model/tool arguments. The outer invoke method records both delivery outcomes and failures.
     */
    private Map<String, Object> sendExternalNotification(
        AgentRunRequest request,
        String key,
        Map<String, Object> arguments
    ) {
        // 以下流程根据当前类型或状态选择处理分支，并保证每种分支返回明确结果。
        if (notificationConfigService == null) {
            throw builtinUnavailable(key, "notification configuration provider is not configured");
        }
        CurrentPrincipal principal = principalResolver.resolve(request);
        if (!principal.isHuman()) {
            throw new ServiceException("服务账号不能发送个人外部通知", HttpStatus.FORBIDDEN);
        }

        String channel;
        String title;
        String content;
        String recipient = null;
        switch (key) {
            case "send_dingtalk_message" -> {
                channel = "dingtalk";
                title = requiredBuiltinText(
                    firstArgument(arguments, "title"), "钉钉通知标题", 255
                );
                content = requiredBuiltinText(
                    firstArgument(arguments, "content", "message"), "钉钉通知内容", 16_384
                );
            }
            case "send_wechat_work_message" -> {
                channel = "wechat_work";
                title = "AI Agent 通知";
                content = requiredBuiltinText(
                    firstArgument(arguments, "content", "message"), "企业微信通知内容", 16_384
                );
            }
            case "send_email" -> {
                channel = "email";
                recipient = requiredBuiltinText(
                    firstArgument(arguments, "to_email", "toEmail", "recipient"),
                    "邮件收件人", 320
                );
                title = requiredBuiltinText(
                    firstArgument(arguments, "subject", "title"), "邮件主题", 255
                );
                content = requiredBuiltinText(
                    firstArgument(arguments, "content", "message"), "邮件正文", 16_384
                );
            }
            default -> throw new ServiceException("不支持的通知工具", HttpStatus.BAD_REQUEST);
        }

        NotificationChannelSender.SendResult sent = notificationConfigService.sendForUser(
            principal.id(), channel, title, content, recipient
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channel", sent.channelType());
        result.put("delivered", true);
        result.put("elapsed_ms", sent.elapsedMs());
        if (recipient != null) {
            result.put("recipient", recipient);
        }
        return result;
    }

    /**
     * 处理工作空间Read并返回对应结果。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> workspaceRead(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        if (workspaceService == null) {
            throw builtinUnavailable("read_file", "workspace provider is not configured");
        }
        String path = requiredWorkspacePath(firstArgument(arguments, "path", "file_path", "filePath"));
        CurrentPrincipal principal = principalResolver.resolve(request);
        return workspaceService.runAsRuntimePrincipal(principal, () -> workspaceService.preview(path));
    }

    /**
     * 处理工作空间Write并返回对应结果。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> workspaceWrite(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        if (workspaceService == null) {
            throw builtinUnavailable("write_file", "workspace provider is not configured");
        }
        String path = requiredWorkspacePath(firstArgument(arguments, "path", "file_path", "filePath"));
        Object rawContent = firstArgument(arguments, "content", "text");
        if (!(rawContent instanceof String content)
            || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 10 * 1024 * 1024) {
            throw new ServiceException("文件内容为空或超过10MB限制", HttpStatus.BAD_REQUEST);
        }
        CurrentPrincipal principal = principalResolver.resolve(request);
        return workspaceService.runAsRuntimePrincipal(
            principal, () -> workspaceService.write(path, content)
        );
    }

    /**
     * 处理工作空间List并返回对应结果。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> workspaceList(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        if (workspaceService == null) {
            throw builtinUnavailable(
                "directory_tree_navigator", "workspace provider is not configured"
            );
        }
        String path = optionalBuiltinText(firstArgument(arguments, "path", "directory"));
        CurrentPrincipal principal = principalResolver.resolve(request);
        return workspaceService.runAsRuntimePrincipal(
            principal, () -> workspaceService.list(path == null ? "" : path)
        );
    }

    /**
     * 处理工作空间SearchText并返回对应结果。
     *
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> workspaceSearchText(
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        if (workspaceService == null) {
            throw builtinUnavailable("search_text", "workspace provider is not configured");
        }
        String pattern = requiredBuiltinText(
            firstArgument(arguments, "pattern", "query", "keyword"), "文本搜索关键词"
        );
        String path = optionalBuiltinText(firstArgument(arguments, "path", "directory"));
        int limit = optionalBuiltinInteger(
            firstArgument(arguments, "max_results", "maxResults", "limit"),
            1, 100, "maxResults", 100
        );
        CurrentPrincipal principal = principalResolver.resolve(request);
        return workspaceService.runAsRuntimePrincipal(
            principal, () -> workspaceService.searchText(pattern, path == null ? "" : path, limit)
        );
    }

    /**
     * 校验记忆View，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     */
    private void requireMemoryView(CurrentPrincipal principal, String scopeType, Long scopeId) {
        if (!Set.of("user", "project", "task").contains(scopeType)
            || scopeId == null || scopeId <= 0) {
            throw new ServiceException("记忆作用域无效", HttpStatus.BAD_REQUEST);
        }
        if (!memoryScopeAuthorization.canView(principal, scopeType, scopeId)) {
            throw new ServiceException("记忆作用域没有查看权限", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 校验记忆Enabled，并在条件不满足时终止处理。
     *
     * @param toolKey 工具Key参数
     */
    private void requireMemoryEnabled(String toolKey) {
        var config = memoryMapper.selectRuntimeConfig();
        if (config != null && Boolean.FALSE.equals(config.getEnabled())) {
            throw builtinUnavailable(toolKey, "memory service is disabled by platform configuration");
        }
    }

    /**
     * 执行time记忆Usable相关的处理流程。
     *
     * @param memory 记忆参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean runtimeMemoryUsable(AgentMemory memory) {
        return memory != null
            && "approved".equals(memory.getReviewStatus())
            && Set.of("public", "internal").contains(memory.getSensitiveLevel())
            && (memory.getExpiresAt() == null || memory.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    /**
     * 处理记忆结果并返回对应结果。
     *
     * @param memory 记忆参数
     * @return 处理结果
     */
    private Map<String, Object> memoryResult(AgentMemory memory) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", memory.getId());
        result.put("memory_key", memory.getMemoryKey());
        result.put("scope_type", memory.getScopeType());
        result.put("scope_id", memory.getScopeId());
        result.put("memory_type", memory.getMemoryType());
        result.put("content", memory.getContent());
        result.put("confidence", memory.getConfidence());
        result.put("expires_at", memory.getExpiresAt());
        result.put("source_type", memory.getSourceType());
        result.put("source_id", memory.getSourceId());
        return result;
    }

    /**
     * 处理记忆Vector结果并返回对应结果。
     *
     * @param hit {@code hit}参数
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @return 处理结果
     */
    private Map<String, Object> memoryVectorResult(
        MemoryVectorApplicationService.SearchHit hit,
        String scopeType,
        Long scopeId
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", hit.id());
        result.put("memory_key", hit.memoryKey());
        result.put("scope_type", scopeType);
        result.put("scope_id", scopeId);
        result.put("memory_type", hit.memoryType());
        result.put("content", hit.content());
        result.put("confidence", hit.confidence());
        result.put("expires_at", hit.expiresAt());
        result.put("source_type", hit.sourceType());
        result.put("source_id", hit.sourceId());
        result.put("sensitive_level", hit.sensitiveLevel());
        result.put("metadata", hit.metadata());
        return result;
    }

    /**
     * 处理{@code boundedScore}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private double boundedScore(double value) {
        return Double.isFinite(value) ? Math.max(0D, Math.min(1D, value)) : 0D;
    }

    /**
     * 处理{@code numericScore}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private double numericScore(Object value) {
        if (value instanceof Number number) {
            return boundedScore(number.doubleValue());
        }
        if (value instanceof String text) {
            try {
                return boundedScore(Double.parseDouble(text));
            } catch (NumberFormatException ignored) {
                return 0D;
            }
        }
        return 0D;
    }

    /**
     * 处理{@code envelope}并返回对应结果。
     *
     * @param data 数据参数
     * @return 处理结果
     */
    private Map<String, Object> envelope(Object data) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        boolean success = true;
        String failureStatus = "failed";
        Object error = null;
        boolean retryable = false;
        if (data instanceof Map<?, ?> source) {
            Object explicitOk = source.get("ok");
            if (explicitOk instanceof Boolean value && !value) {
                success = false;
                error = source.get("error");
            }
            String status = normalizedResultStatus(source.get("status"));
            if (isFailureResultStatus(status)) {
                success = false;
                failureStatus = status;
            }
            if (error == null) {
                error = source.get("error");
            }
            if (source.get("retryable") instanceof Boolean value) {
                retryable = value;
            }
            if (success && Boolean.FALSE.equals(explicitOk)) {
                success = false;
            }
            if (!success && error == null) {
                error = status == null || status.isBlank()
                    ? "工具执行返回失败结果" : "工具执行返回状态：" + status;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", success);
        result.put("data", data);
        result.put("error", success ? null : error);
        String status = success && containsDegradedMemoryRows(data) ? "degraded" :
            success ? "success" : failureStatus;
        result.put("status", status);
        result.put("retryable", success ? false : retryable);
        return result;
    }

    /**
     * 处理containsDegraded记忆Rows并返回对应结果。
     *
     * @param data 数据参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean containsDegradedMemoryRows(Object data) {
        if (!(data instanceof List<?> rows)) {
            return false;
        }
        return rows.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .anyMatch(row -> Boolean.TRUE.equals(row.get("retrieval_degraded")));
    }

    /**
     * 判断Failure结果Status是否满足要求。
     *
     * @param status 目标状态
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isFailureResultStatus(String status) {
        return Set.of(
            "failed", "failure", "error", "unavailable", "timeout", "timed_out",
            "provider_error", "transport_error", "query_error", "tool_unavailable",
            "authorization_error", "invalid_arguments", "conflict", "rejected", "denied",
            "cancelled", "expired", "partial_failure"
        ).contains(status);
    }

    /**
     * 处理normalized结果Status并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizedResultStatus(Object value) {
        return value instanceof String text
            ? text.strip().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * 校验{@code dBuiltinText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredBuiltinText(Object value, String label) {
        return requiredBuiltinText(value, label, 256);
    }

    /**
     * 校验{@code dBuiltinText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @param maximumLength {@code maximumLength}参数
     * @return 处理结果
     */
    private String requiredBuiltinText(Object value, String label, int maximumLength) {
        String text = optionalText(value);
        if (text == null || text.length() > maximumLength) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
        return text;
    }

    /**
     * 处理通知Channels并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<String> notificationChannels(Object value) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> channels) || channels.size() > 4) {
            throw new ServiceException("通知渠道无效", HttpStatus.BAD_REQUEST);
        }
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (Object item : channels) {
            String channel = item instanceof String text
                ? text.strip().toLowerCase(Locale.ROOT) : "";
            if (!Set.of("portal", "dingtalk", "wechat_work", "email").contains(channel)) {
                throw new ServiceException("通知渠道无效", HttpStatus.BAD_REQUEST);
            }
            result.add(channel);
        }
        return List.copyOf(result);
    }

    /**
     * 校验工作空间Path，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String requiredWorkspacePath(Object value) {
        String path = optionalBuiltinText(value);
        if (path == null || path.length() > 4096) {
            throw new ServiceException("工作区路径无效", HttpStatus.BAD_REQUEST);
        }
        return path;
    }

    /**
     * 处理{@code optionalBuiltinText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String optionalBuiltinText(Object value) {
        String text = optionalText(value);
        if (text != null && text.length() > 256) {
            throw new ServiceException("文本参数超过长度限制", HttpStatus.BAD_REQUEST);
        }
        return text;
    }

    /**
     * 处理{@code firstArgument}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @param names 名称
     * @return 处理结果
     */
    private Object firstArgument(Map<String, Object> arguments, String... names) {
        if (arguments == null || names == null) {
            return null;
        }
        for (String name : names) {
            if (arguments.containsKey(name)) {
                return arguments.get(name);
            }
        }
        return null;
    }

    /**
     * 处理{@code positiveBuiltinLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Long positiveBuiltinLong(Object value, String label) {
        Long result = optionalBuiltinLong(value);
        if (result == null) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
        return result;
    }

    /**
     * 处理{@code optionalBuiltinLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Long optionalBuiltinLong(Object value) {
        if (!(value instanceof Number number) || number.longValue() <= 0
            || number.doubleValue() != number.longValue()) {
            return null;
        }
        return number.longValue();
    }

    /**
     * 处理{@code optionalBuiltinInteger}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param min {@code min}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Integer optionalBuiltinInteger(
        Object value, int min, int max, String label
    ) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)
            || number.doubleValue() != number.intValue()
            || number.intValue() < min || number.intValue() > max) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
        return number.intValue();
    }

    /**
     * 处理{@code optionalBuiltinInteger}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param min {@code min}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @param defaultValue {@code defaultValue}参数
     * @return 处理结果
     */
    private int optionalBuiltinInteger(
        Object value, int min, int max, String label, int defaultValue
    ) {
        Integer result = optionalBuiltinInteger(value, min, max, label);
        return result == null ? defaultValue : result;
    }

    /**
     * 处理{@code builtinUnavailable}并返回对应结果。
     *
     * @param key {@code key}参数
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    private ServiceException builtinUnavailable(String key, String reason) {
        return new ServiceException("tool_unavailable: " + key + " (" + reason + ")", 503);
    }

    /**
     * 处理技能Unavailable并返回对应结果。
     *
     * @param skillKey 技能Key参数
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    private ServiceException skillUnavailable(String skillKey, String reason) {
        String key = skillKey == null || skillKey.isBlank() ? "<snapshot>" : skillKey;
        return new ServiceException("skill_unavailable: " + key + " (" + reason + ")", 503);
    }

    /**
     * 校验沙箱技能工作空间，并在条件不满足时终止处理。
     *
     * @param request 请求参数
     */
    private void requireSandboxSkillWorkspace(AgentRunRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (request == null) {
            throw skillUnavailable(null, "运行快照缺失");
        }
        Object rawBindings = request.attributes().get("resourceBindings");
        if (!(rawBindings instanceof List<?> bindings)) {
            return;
        }
        boolean hasSkill = bindings.stream().anyMatch(raw -> raw instanceof Map<?, ?> binding
            && "skill".equals(String.valueOf(binding.get("resourceType"))));
        if (hasSkill) {
            try {
                SandboxSkillManifest.Normalized manifest = SandboxSkillManifest.fromAttributes(
                    request.attributes(), request.workspaceKey(), jsonMapper
                );
                if (manifest.empty()) {
                    throw skillUnavailable(null, "冻结 Skill manifest 为空");
                }
            } catch (ServiceException exception) {
                throw skillUnavailable(null, "冻结 Skill manifest 无效");
            }
        }
    }

    /**
     * 处理{@code putIfPresent}相关逻辑。
     *
     * @param target {@code target}参数
     * @param key {@code key}参数
     * @param value {@code value}参数
     */
    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
 * 处理enqueueExternal执行并返回对应结果。
 * Queues schema-only sandbox calls emitted by AgentScope after frozen authorization checks. */
    public List<SandboxJobQueueService.SandboxJobTicket> enqueueExternalExecution(
        AgentRunRequest request,
        RuntimeEvent source
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Objects.requireNonNull(request, "request must not be null");
        if (source == null || source.type() != RuntimeEventType.EXTERNAL_EXECUTION_REQUIRED) {
            throw new SecurityException("外部执行事件类型无效");
        }
        if (sandboxQueue == null) {
            throw new ServiceException("沙箱作业队列未启用", 503);
        }
        String replyId = requiredText(source.payload().get("replyId"), "外部执行回复ID");
        Object rawCalls = source.payload().get("toolCalls");
        if (!(rawCalls instanceof List<?> calls) || calls.isEmpty() || calls.size() > 16) {
            throw new SecurityException("外部执行工具调用无效");
        }
        CurrentPrincipal principal = principalResolver.resolve(request);
        List<SandboxJobQueueService.SandboxJobTicket> tickets = new ArrayList<>();
        for (Object rawCall : calls) {
            if (!(rawCall instanceof Map<?, ?> raw)) {
                throw new SecurityException("外部执行工具调用无效");
            }
            Map<String, Object> call = stringMap(raw);
            String toolCallId = requiredText(call.get("id"), "工具调用ID");
            String toolName = requiredText(call.get("name"), "工具调用名称");
            Long toolId = runtimeToolId(toolName);
            FrozenTool frozen = frozenTools(request).stream()
                .filter(item -> toolId.equals(item.id()))
                .findFirst()
                .orElseThrow(() -> forbidden("工具不在 Agent 冻结资源中"));
            requireTaskGrant(request, toolId);
            CurrentTool current = currentTool(frozen, principal);
            if (current == null || !"sandbox".equals(frozen.toolType())) {
                throw forbidden("外部执行工具不是可用的沙箱工具");
            }
            AuthorizationDecision decision = authorizationEnforcer.decide(
                principal, permissionContext(request, frozen)
            );
            if (!decision.allowed() && !decision.requiresApproval()) {
                throw forbidden("沙箱工具当前授权已失效：" + decision.reasonCode());
            }
            Map<String, Object> arguments = requiredMap(call.get("input"), "沙箱工具参数");
            String argumentsJson = argumentValidator.validate(arguments, frozen.inputSchema());
            try {
                requireSandboxSkillWorkspace(request);
                SandboxJobQueueService.SandboxJobTicket ticket = sandboxQueue.enqueue(
                    submission(request, frozen, replyId, toolCallId, toolName, arguments)
                );
                String resultJson = argumentValidator.boundedResultJson(Map.of(
                    "jobId", ticket.jobId(), "traceId", ticket.traceId(), "status", ticket.status()
                ));
                auditService.record(
                    request, toolId, argumentsJson, resultJson, true, "SANDBOX_JOB_QUEUED"
                );
                tickets.add(ticket);
            } catch (RuntimeException exception) {
                auditService.record(
                    request, toolId, argumentsJson, null, false,
                    "SANDBOX_QUEUE_EXCEPTION:" + exception.getClass().getSimpleName()
                );
                throw exception;
            }
        }
        return List.copyOf(tickets);
    }

    /**
     * 处理{@code submission}并返回对应结果。
     *
     * @param request 请求参数
     * @param frozen {@code frozen}参数
     * @param replyId 资源标识
     * @param toolCallId 资源标识
     * @param toolName 名称
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private SandboxJobSubmission submission(
        AgentRunRequest request,
        FrozenTool frozen,
        String replyId,
        String toolCallId,
        String toolName,
        Map<String, Object> arguments
    ) {
        Map<String, Object> policy = frozen.executionPolicy();
        Object rawArgv = arguments.get("argv");
        if (!(rawArgv instanceof List<?> values) || values.isEmpty()) {
            throw new ServiceException("沙箱工具参数必须包含非空argv数组", HttpStatus.BAD_REQUEST);
        }
        List<String> argv = values.stream().map(value -> {
            if (!(value instanceof String text)) {
                throw new ServiceException("沙箱argv只能包含字符串", HttpStatus.BAD_REQUEST);
            }
            return text;
        }).toList();
        int timeoutMs = boundedInteger(policy.get("timeoutMs"), 300000, 1000, 3600000, "超时时间");
        return new SandboxJobSubmission(
            request.taskId(), request.runId(), request.stepId(), frozen.id(),
            replyId, toolCallId, toolName,
            requiredText(policy.get("templateKey"), "沙箱模板键"), argv,
            textOrDefault(policy.get("workspacePath"), "."),
            textOrDefault(policy.get("workspaceAccess"), "read_write"),
            textOrDefault(policy.get("networkPolicy"), "none"),
            stringList(policy.get("allowedHosts"), "网络白名单"),
            (timeoutMs + 999) / 1000,
            boundedInteger(policy.get("memoryMb"), 512, 64, 32768, "内存上限"),
            boundedInteger(policy.get("cpuMillis"), 1000, 100, 16000, "CPU上限"),
            boundedInteger(policy.get("pidsLimit"), 128, 16, 2048, "PID上限"),
            boundedInteger(policy.get("maxOutputBytes"), 1048576, 1024, 10485760, "输出上限"),
            boundedInteger(policy.get("priority"), 0, -100, 100, "优先级"),
            request.workspaceKey(),
            SandboxSkillManifest.fromAttributes(
                request.attributes(), request.workspaceKey(), jsonMapper
            ).json()
        );
    }

    /**
     * 执行time工具Id相关的处理流程。
     *
     * @param toolName 名称
     * @return 处理结果
     */
    private Long runtimeToolId(String toolName) {
        if (!toolName.startsWith("platform_tool_")) {
            throw new SecurityException("外部执行工具名称无效");
        }
        try {
            long id = Long.parseLong(toolName.substring("platform_tool_".length()));
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new SecurityException("外部执行工具名称无效");
        }
    }

    /**
     * 处理{@code textOrDefault}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param defaultValue {@code defaultValue}参数
     * @return 处理结果
     */
    private String textOrDefault(Object value, String defaultValue) {
        String text = optionalText(value);
        return text == null ? defaultValue : text;
    }

    /**
     * 处理{@code boundedInteger}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param defaultValue {@code defaultValue}参数
     * @param min {@code min}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private int boundedInteger(Object value, int defaultValue, int min, int max, String label) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)
            || number.doubleValue() != number.intValue()
            || number.intValue() < min
            || number.intValue() > max) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
        return number.intValue();
    }

    /**
     * 处理{@code stringList}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 符合条件的数据集合
     */
    private List<String> stringList(Object value, String label) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values) || values.size() > 32) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
        return values.stream().map(item -> {
            if (!(item instanceof String text)) {
                throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
            }
            return text;
        }).toList();
    }

    /**
     * 校验{@code dBuiltinStringList}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 符合条件的数据集合
     */
    private List<String> requiredBuiltinStringList(Object value, String label) {
        List<String> values = stringList(value, label);
        if (values.isEmpty() || values.size() > 10 || values.stream().anyMatch(item -> item.isBlank() || item.length() > 512)) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
        return values;
    }

    /**
     * 执行{@code Remote}相关的处理流程。
     *
     * @param request 请求参数
     * @param connector 连接器参数
     * @param frozen {@code frozen}参数
     * @param arguments {@code arguments}参数
     * @param argumentsJson {@code argumentsJson}参数
     * @return 处理结果
     */
    private ToolResult invokeRemote(
        AgentRunRequest request,
        AgentConnector connector,
        FrozenTool frozen,
        Map<String, Object> arguments,
        String argumentsJson
    ) {
        if ("mcp".equals(frozen.toolType())) {
            McpRemoteClient.InvocationResult result = mcpLifecycle == null
                ? remoteClient.invoke(
                    connectionFactory.create(connector), frozen.externalName(), arguments
                )
                : mcpLifecycle.invoke(
                    request, connector, frozen.id(), frozen.externalName(), arguments, argumentsJson
                );
            return new ToolResult(
                result.error(), result.content(), result.structuredContent(),
                result.metadata() == null ? Map.of() : result.metadata()
            );
        }
        ApiToolExecutor.ApiInvocationResult result = apiToolExecutor.invoke(
            connector, frozen.executionPolicy(), arguments
        );
        return new ToolResult(
            result.error(), result.content(), null,
            Map.of("statusCode", result.statusCode(), "contentType", result.contentType())
        );
    }

    /**
     * 处理当前工具并返回对应结果。
     *
     * @param frozen {@code frozen}参数
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private CurrentTool currentTool(FrozenTool frozen, CurrentPrincipal principal) {
        AgentTool tool = mapper.selectToolById(frozen.id());
        if (tool == null || !"active".equals(tool.getStatus())
            || !Boolean.TRUE.equals(tool.getIsAvailable())
            || !Objects.equals(frozen.toolKey(), tool.getToolKey())
            || !Objects.equals(frozen.versionNo(), tool.getVersionNo())
            || !Objects.equals(frozen.toolType(), tool.getToolType())
            || !Objects.equals(frozen.riskLevel(), tool.getRiskLevel())
            || !Objects.equals(frozen.connectorId(), tool.getConnectorId())
            || !Objects.equals(frozen.externalName(), tool.getExternalName())) {
            return null;
        }
        if (tool.getConnectorId() == null) {
            return new CurrentTool(tool, null);
        }
        AgentConnector connector = mapper.selectConnectorById(tool.getConnectorId());
        if (connector == null || !"active".equals(connector.getStatus())
            || !Objects.equals(tool.getToolType(), connector.getProviderType())
            || ("personal".equals(connector.getScopeType())
                && !principal.id().equals(connector.getOwnerId()))) {
            return null;
        }
        return new CurrentTool(tool, connector);
    }

    /**
     * 处理{@code frozenTools}并返回对应结果。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    private List<FrozenTool> frozenTools(AgentRunRequest request) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Object rawBindings = request.attributes().get("resourceBindings");
        if (!(rawBindings instanceof List<?> bindings)) {
            throw new SecurityException("运行快照缺少 Agent 资源绑定");
        }
        List<FrozenTool> result = new ArrayList<>();
        for (Object value : bindings) {
            if (!(value instanceof Map<?, ?> raw) || !"tool".equals(raw.get("resourceType"))) {
                continue;
            }
            Map<String, Object> binding = stringMap(raw);
            Long id = positiveLong(binding.get("resourceId"), "工具资源 ID");
            Map<String, Object> config = requiredMap(binding.get("config"), "工具绑定配置");
            Map<String, Object> snapshot = requiredMap(
                config.get("resourceSnapshot"), "工具资源快照"
            );
            String risk = requiredText(snapshot.get("riskLevel"), "工具风险等级");
            if (!RISKS.contains(risk)) {
                throw new SecurityException("工具风险等级无效");
            }
            Map<String, Object> executionPolicy = optionalMap(snapshot.get("executionPolicy"));
            result.add(new FrozenTool(
                id,
                requiredText(snapshot.get("toolKey"), "工具标识"),
                requiredText(snapshot.get("name"), "工具名称"),
                optionalText(snapshot.get("description")),
                requiredText(snapshot.get("toolType"), "工具类型"),
                risk,
                positiveInteger(snapshot.get("versionNo"), "工具版本号"),
                optionalPositiveLong(snapshot.get("connectorId"), "连接器 ID"),
                optionalText(snapshot.get("externalName")),
                optionalMap(snapshot.get("parameterSchema")),
                optionalMap(executionPolicy.get("outputSchema")),
                executionPolicy,
                Boolean.TRUE.equals(executionPolicy.get("readOnly"))
            ));
        }
        return List.copyOf(result);
    }

    /**
     * 校验任务Grant，并在条件不满足时终止处理。
     *
     * @param request 请求参数
     * @param toolId 资源标识
     */
    private void requireTaskGrant(AgentRunRequest request, Long toolId) {
        Map<String, Object> snapshot = requiredMap(
            request.attributes().get("taskResourceSnapshot"), "任务资源快照"
        );
        Long agentVersionId = positiveLong(snapshot.get("agentVersionId"), "任务 Agent 版本 ID");
        if (!request.agentVersionId().equals(agentVersionId)) {
            throw new SecurityException("任务资源快照与 Agent 版本不一致");
        }
        if (!(snapshot.get("resources") instanceof List<?> resources)) {
            throw new SecurityException("任务资源快照缺少授权资源");
        }
        boolean granted = resources.stream().anyMatch(value -> {
            if (!(value instanceof Map<?, ?> resource)) {
                return false;
            }
            Object id = resource.get("resourceId");
            Object permission = resource.get("permission");
            Set<String> allowedPermissions = request.taskId() == null
                ? Set.of("invoke", "use", "admin") : Set.of("use", "admin");
            return "tool".equals(resource.get("resourceType"))
                && id instanceof Number number
                && number.doubleValue() == number.longValue()
                && toolId.longValue() == number.longValue()
                && allowedPermissions.contains(permission);
        });
        if (!granted) {
            throw new SecurityException(request.taskId() == null
                ? "工具不在会话冻结授权中" : "工具不在任务冻结授权中");
        }
    }

    /**
     * 处理权限上下文并返回对应结果。
     *
     * @param request 请求参数
     * @param frozen {@code frozen}参数
     * @return 处理结果
     */
    private PermissionContext permissionContext(AgentRunRequest request, FrozenTool frozen) {
        return new PermissionContext(
            "tool", frozen.id(), frozen.toolKey(), "invoke",
            ResourceState.ACTIVE, false, Set.of(), request.taskId()
        );
    }

    /**
     * 执行{@code timeName}相关的处理流程。
     *
     * @param frozen {@code frozen}参数
     * @return 处理结果
     */
    private String runtimeName(FrozenTool frozen) {
        if (Set.of("request_user_confirmation", "ask_user_question").contains(builtinKey(frozen))) {
            return "request_user_confirmation";
        }
        return "platform_tool_" + frozen.id();
    }

    /**
     * 执行{@code timeDescription}相关的处理流程。
     *
     * @param request 请求参数
     * @param frozen {@code frozen}参数
     * @return 处理结果
     */
    private String runtimeDescription(AgentRunRequest request, FrozenTool frozen) {
        String description = frozen.description();
        String base = description == null || description.isBlank()
            ? frozen.name() : frozen.name() + ": " + description;
        String unavailable = unavailableReason(request, frozen);
        return unavailable == null ? base : base + " [不可用: " + unavailable + "]";
    }

    /**
     * 处理{@code unavailableReason}并返回对应结果。
     *
     * @param request 请求参数
     * @param frozen {@code frozen}参数
     * @return 处理结果
     */
    private String unavailableReason(AgentRunRequest request, FrozenTool frozen) {
        if ("builtin".equals(frozen.toolType())) {
            String key = builtinKey(frozen);
            if (!builtinAvailable(key)) {
                return "依赖 Provider 或执行器未配置（tool_unavailable）";
            }
        }
        if ("sql".equals(frozen.toolType()) && (dataQueryProvider == null
            || !dataQueryProvider.configuredAvailable(
                request, frozen.inputSchema(), frozen.executionPolicy(), frozen.toolKey()
            ))) {
            return "数据查询 Provider 未配置（tool_unavailable）";
        }
        return null;
    }

    /**
     * 校验{@code dMap}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Map<String, Object> requiredMap(Object value, String label) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new SecurityException(label + "无效");
        }
        return stringMap(raw);
    }

    /**
     * 处理{@code optionalMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> optionalMap(Object value) {
        return value instanceof Map<?, ?> raw ? stringMap(raw) : Map.of();
    }

    /**
     * 处理{@code stringMap}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredText(Object value, String label) {
        String text = optionalText(value);
        if (text == null) {
            throw new SecurityException(label + "无效");
        }
        return text;
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
     * 处理{@code positiveLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Long positiveLong(Object value, String label) {
        Long result = optionalPositiveLong(value, label);
        if (result == null) {
            throw new SecurityException(label + "无效");
        }
        return result;
    }

    /**
     * 处理{@code optionalPositiveLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Long optionalPositiveLong(Object value, String label) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number) || number.longValue() <= 0
            || number.doubleValue() != number.longValue()) {
            throw new SecurityException(label + "无效");
        }
        return number.longValue();
    }

    /**
     * 处理{@code positiveInteger}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Integer positiveInteger(Object value, String label) {
        Long number = positiveLong(value, label);
        if (number > Integer.MAX_VALUE) {
            throw new SecurityException(label + "无效");
        }
        return number.intValue();
    }

    /**
     * 处理{@code forbidden}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException forbidden(String message) {
        return new ServiceException(message, HttpStatus.FORBIDDEN);
    }

    /**
     * 封装Frozen工具相关的不可变数据。
     */
    private record FrozenTool(
        Long id,
        String toolKey,
        String name,
        String description,
        String toolType,
        String riskLevel,
        Integer versionNo,
        Long connectorId,
        String externalName,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        Map<String, Object> executionPolicy,
        boolean readOnly
    ) {
    }

    /**
     * 封装当前工具相关的不可变数据。
     */
    private record CurrentTool(AgentTool tool, AgentConnector connector) {
    }

    /**
     * 封装Frozen技能相关的不可变数据。
     */
    private record FrozenSkill(
        Long id,
        String skillKey,
        String name,
        String description,
        String scope,
        Integer versionNo,
        String contentHash,
        String content,
        Map<String, Object> manifest,
        Map<String, Object> runtimeRequirements
    ) {
    }

    /**
     * 封装工具相关的不可变数据。
     */
    private record ToolResult(
        boolean error,
        Object content,
        Object structuredContent,
        Map<String, Object> metadata
    ) {
    }
}
