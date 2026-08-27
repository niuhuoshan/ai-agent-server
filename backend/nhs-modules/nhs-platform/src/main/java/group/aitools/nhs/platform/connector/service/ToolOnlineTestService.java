package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.platform.audit.service.ToolInvocationAuditService;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.domain.AgentTool;
import group.aitools.nhs.platform.connector.mapper.ConnectorCatalogMapper;
import group.aitools.nhs.platform.connector.web.ToolOnlineTestRequest;
import group.aitools.nhs.platform.connector.web.ToolOnlineTestView;
import group.aitools.nhs.platform.data.service.DataQueryExecutionService;
import group.aitools.nhs.platform.data.web.DataQueryRequest;
import group.aitools.nhs.platform.data.web.DataQueryResultView;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 负责工具OnlineTest相关的业务编排与领域规则处理。
 * Executes a bounded, authorized online test through the production remote-tool boundary. */
@Service
public class ToolOnlineTestService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> SUPPORTED_TYPES = Set.of("builtin", "mcp", "api", "search", "sql");
    private static final Set<String> SECRET_KEYS = Set.of(
        "secret", "password", "token", "apikey", "authorization", "credential",
        "privatekey", "accesskey", "clientsecret"
    );
    private static final String REDACTED = "[redacted]";
    private static final String TRUNCATED = "[truncated]";

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final ConnectorCatalogMapper mapper;
    private final ConnectorMcpConnectionFactory connectionFactory;
    private final McpRemoteClient remoteClient;
    private final ApiToolExecutor apiToolExecutor;
    private final ToolArgumentValidator argumentValidator;
    private final SqlToolTemplateEngine sqlTemplateEngine;
    private final DataQueryExecutionService queryExecutionService;
    private final ToolInvocationAuditService auditService;
    private final JsonMapper jsonMapper;
    /**
 * 创建 {@code ToolOnlineTestService} 实例并初始化所需依赖。
 * Uses the same frozen-resource and provider checks as a real Agent turn. */
    private PlatformRuntimeToolProvider runtimeToolProvider;

    public ToolOnlineTestService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        ConnectorCatalogMapper mapper,
        ConnectorMcpConnectionFactory connectionFactory,
        McpRemoteClient remoteClient,
        ApiToolExecutor apiToolExecutor,
        ToolArgumentValidator argumentValidator,
        SqlToolTemplateEngine sqlTemplateEngine,
        DataQueryExecutionService queryExecutionService,
        ToolInvocationAuditService auditService,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.mapper = mapper;
        this.connectionFactory = connectionFactory;
        this.remoteClient = remoteClient;
        this.apiToolExecutor = apiToolExecutor;
        this.argumentValidator = argumentValidator;
        this.sqlTemplateEngine = sqlTemplateEngine;
        this.queryExecutionService = queryExecutionService;
        this.auditService = auditService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 设置平台运行时工具提供方。
     *
     * @param runtimeToolProvider 运行时工具提供方参数
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlatformRuntimeToolProvider(PlatformRuntimeToolProvider runtimeToolProvider) {
        this.runtimeToolProvider = runtimeToolProvider;
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param toolId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    public ToolOnlineTestView execute(Long toolId, ToolOnlineTestRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentTool tool = requireTool(toolId);
        AuthorizationDecision decision = authorizationEnforcer.decide(
            principal,
            new PermissionContext(
                "tool", tool.getId(), tool.getToolKey(), "invoke",
                ResourceState.ACTIVE, true, Set.of(), null
            )
        );
        if (decision.requiresApproval()) {
            throw new ServiceException("该工具需要审批，请通过任务执行流程申请", HttpStatus.CONFLICT);
        }
        if (!decision.allowed()) {
            throw new ServiceException("没有权限执行此工具：" + decision.reasonCode(), HttpStatus.FORBIDDEN);
        }
        if (!"active".equals(tool.getStatus()) || !Boolean.TRUE.equals(tool.getIsAvailable())) {
            throw new ServiceException("工具当前不可用", HttpStatus.CONFLICT);
        }
        if (!SUPPORTED_TYPES.contains(tool.getToolType())) {
            throw new ServiceException("在线测试仅支持内置、MCP、API、搜索和 SQL 工具", HttpStatus.BAD_REQUEST);
        }
        if (Set.of("R2", "R3").contains(tool.getRiskLevel()) && !request.confirmRisk()) {
            throw new ServiceException("高风险工具执行前必须显式确认", HttpStatus.CONFLICT);
        }
        Map<String, Object> schema = document(tool.getParameterSchemaJson(), "工具参数 Schema");
        Map<String, Object> policy = document(tool.getExecutionPolicyJson(), "工具执行策略");
        String argumentsJson = argumentValidator.validate(request.arguments(), schema);
        AgentConnector connector = null;
        if (!"sql".equals(tool.getToolType())) {
            connector = requireActiveConnector(tool);
            if ("personal".equals(connector.getScopeType())
                && !principal.id().equals(connector.getOwnerId())) {
                throw new ServiceException("工具不存在", HttpStatus.NOT_FOUND);
            }
        }
        Instant started = Instant.now();
        LocalDateTime checkedAt = LocalDateTime.now();
        ProviderResult providerResult;
        try {
            providerResult = "builtin".equals(tool.getToolType())
                ? invokeBuiltin(tool, principal, schema, policy, request.arguments())
                : "sql".equals(tool.getToolType())
                ? invokeSql(tool, schema, policy, request.arguments())
                : invoke(tool, connector, policy, request.arguments());
        } catch (RuntimeException exception) {
            boolean sql = "sql".equals(tool.getToolType());
            String error = safeError(exception, sql ? "只读查询执行失败" : "连接远端服务失败");
            String status = failureStatus(exception, sql);
            boolean retryable = retryable(exception, status, sql);
            auditService.recordUiTest(
                principal, toolId, argumentsJson, null, false,
                "UI_TOOL_TEST_" + status.toUpperCase(Locale.ROOT) + ":"
                    + exception.getClass().getSimpleName()
            );
            return new ToolOnlineTestView(
                toolId, false, null, error, status, retryable,
                Duration.between(started, Instant.now()).toMillis(), checkedAt
            );
        }
        Object safeData;
        String resultJson;
        try {
            safeData = scrub(providerResult.data(), 0);
            resultJson = argumentValidator.boundedResultJson(safeData);
        } catch (RuntimeException exception) {
            String error = safeError(exception, "工具响应处理失败");
            auditService.recordUiTest(
                principal, toolId, argumentsJson, null, false,
                "UI_TOOL_TEST_RESPONSE_ERROR:" + exception.getClass().getSimpleName()
            );
            return new ToolOnlineTestView(
                toolId, false, null, error, "transport_error", false,
                Duration.between(started, Instant.now()).toMillis(), checkedAt
            );
        }
        long latencyMs = Duration.between(started, Instant.now()).toMillis();
        if (providerResult.error()) {
            String error = providerError(safeData);
            auditService.recordUiTest(
                principal, toolId, argumentsJson, resultJson, false, "UI_TOOL_TEST_PROVIDER_ERROR"
            );
            return new ToolOnlineTestView(
                toolId, false, null, error, "provider_error", providerResult.retryable(),
                latencyMs, checkedAt
            );
        }
        auditService.recordUiTest(
            principal, toolId, argumentsJson, resultJson, true, "UI_TOOL_TEST_SUCCEEDED"
        );
        return new ToolOnlineTestView(
            toolId, true, safeData, null, "succeeded", false, latencyMs, checkedAt
        );
    }

    /**
 * 执行{@code Builtin}相关的处理流程。
 *
     * Runs a builtin through the exact runtime provider used by AgentScope. The
     * synthetic request contains only a bounded tool and authorization snapshot;
     * it cannot enlarge the caller's resource scope or access another principal's
     * workspace.
     */
    private ProviderResult invokeBuiltin(
        AgentTool tool,
        CurrentPrincipal principal,
        Map<String, Object> schema,
        Map<String, Object> policy,
        Map<String, Object> arguments
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (runtimeToolProvider == null) {
            throw new ServiceException("tool_unavailable: 内置工具运行时未配置", 503);
        }
        String executionId = "tool-test-" + UUID.randomUUID();
        String traceId = "tool-test-" + UUID.randomUUID();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("toolKey", tool.getToolKey());
        snapshot.put("name", tool.getName());
        snapshot.put("description", tool.getDescription());
        snapshot.put("toolType", tool.getToolType());
        snapshot.put("riskLevel", tool.getRiskLevel());
        snapshot.put("versionNo", tool.getVersionNo());
        snapshot.put("connectorId", tool.getConnectorId());
        snapshot.put("externalName", tool.getExternalName());
        snapshot.put("parameterSchema", schema);
        snapshot.put("executionPolicy", policy);
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("resourceType", "tool");
        binding.put("resourceId", tool.getId());
        binding.put("permission", "invoke");
        binding.put("config", Map.of("resourceSnapshot", snapshot));
        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("principalId", principal.id());
        authorization.put("principalType", principal.type().name().toLowerCase(Locale.ROOT));
        authorization.put("roles", principal.roles().stream().map(role -> role.key()).toList());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("resourceBindings", List.of(binding));
        attributes.put("taskResourceSnapshot", Map.of(
            "agentVersionId", 1L,
            "resources", List.of(Map.of(
                "resourceType", "tool", "resourceId", tool.getId(), "permission", "invoke"
            ))
        ));
        AgentRunRequest runtimeRequest = new AgentRunRequest(
            new RuntimeExecutionKey(executionId, traceId), principal.id(), tool.getId(), null,
            null, null, 1L, "tool-online-test", "tool-test-" + tool.getId(),
            "在线测试：" + tool.getToolKey(), "", new RuntimeModelConfig(
                "platform", "tool-online-test", null, "platform:tool-test", Map.of()
            ), null, 1, authorization, attributes
        );
        Object raw = runtimeToolProvider.invoke(runtimeRequest, tool.getId(), arguments);
        if (!(raw instanceof Map<?, ?> envelope)) {
            return new ProviderResult(false, raw, false);
        }
        boolean ok = !Boolean.FALSE.equals(envelope.get("ok"));
        Object data = envelope.get("data");
        if (!ok) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("error", envelope.get("error"));
            failure.put("status", envelope.get("status"));
            failure.put("data", data);
            return new ProviderResult(true, failure, Boolean.TRUE.equals(envelope.get("retryable")));
        }
        return new ProviderResult(false, data, false);
    }

    /**
     * 执行{@code Sql}相关的处理流程。
     *
     * @param tool 工具参数
     * @param schema {@code schema}参数
     * @param policy 策略参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private ProviderResult invokeSql(
        AgentTool tool,
        Map<String, Object> schema,
        Map<String, Object> policy,
        Map<String, Object> arguments
    ) {
        SqlToolTemplateEngine.Configuration configuration = sqlTemplateEngine.validate(schema, policy);
        String sql = sqlTemplateEngine.render(configuration, arguments);
        DataQueryResultView result = queryExecutionService.executeWithTrace(new DataQueryRequest(
            configuration.datasetId(), null, null, null,
            configuration.queryPurpose() + "（工具：" + tool.getToolKey() + "）", sql
        ), "tool-test-" + UUID.randomUUID());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("queryId", result.queryId());
        data.put("columns", result.columns());
        data.put("rows", result.rows());
        data.put("rowCount", result.rowCount());
        data.put("resultBytes", result.resultBytes());
        data.put("truncated", result.truncated());
        data.put("elapsedMs", result.elapsedMs());
        return new ProviderResult(false, Map.copyOf(data), false);
    }

    /**
     * 执行{@code invoke}相关的处理流程。
     *
     * @param tool 工具参数
     * @param connector 连接器参数
     * @param policy 策略参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private ProviderResult invoke(
        AgentTool tool,
        AgentConnector connector,
        Map<String, Object> policy,
        Map<String, Object> arguments
    ) {
        if ("mcp".equals(tool.getToolType())) {
            McpRemoteClient.InvocationResult result = remoteClient.invoke(
                connectionFactory.create(connector), tool.getExternalName(), arguments
            );
            if (result == null) {
                throw new McpRemoteException("MCP 服务返回了空结果");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content", result.content());
            data.put("structuredContent", result.structuredContent());
            data.put("metadata", result.metadata() == null ? Map.of() : result.metadata());
            return new ProviderResult(result.error(), data, false);
        }
        ApiToolExecutor.ApiInvocationResult result = apiToolExecutor.invoke(
            connector, policy, arguments
        );
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", result.content());
        data.put("statusCode", result.statusCode());
        data.put("contentType", result.contentType());
        boolean retryable = result.statusCode() == 429 || result.statusCode() >= 500;
        return new ProviderResult(result.error(), data, retryable);
    }

    /**
     * 校验工具，并在条件不满足时终止处理。
     *
     * @param toolId 资源标识
     * @return 处理结果
     */
    private AgentTool requireTool(Long toolId) {
        AgentTool tool = mapper.selectToolById(toolId);
        if (tool == null) {
            throw new ServiceException("工具不存在", HttpStatus.NOT_FOUND);
        }
        return tool;
    }

    /**
     * 校验Active连接器，并在条件不满足时终止处理。
     *
     * @param tool 工具参数
     * @return 处理结果
     */
    private AgentConnector requireActiveConnector(AgentTool tool) {
        if (tool.getConnectorId() == null) {
            throw new ServiceException("工具未绑定连接器", HttpStatus.CONFLICT);
        }
        AgentConnector connector = mapper.selectConnectorById(tool.getConnectorId());
        if (connector == null || !"active".equals(connector.getStatus())
            || !tool.getToolType().equals(connector.getProviderType())) {
            throw new ServiceException("工具连接器当前不可用", HttpStatus.CONFLICT);
        }
        return connector;
    }

    /**
     * 处理文档并返回对应结果。
     *
     * @param json {@code json}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Map<String, Object> document(String json, String label) {
        try {
            Map<String, Object> value = jsonMapper.readValue(json, MAP_TYPE);
            return value == null ? Map.of() : value;
        } catch (RuntimeException exception) {
            throw new ServiceException(label + "已损坏", HttpStatus.CONFLICT);
        }
    }

    /**
     * 处理{@code scrub}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param depth {@code depth}参数
     * @return 处理结果
     */
    private Object scrub(Object value, int depth) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (depth > 24) {
            return TRUNCATED;
        }
        if (value instanceof String text) {
            return text
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer " + REDACTED)
                .replaceAll("agk_[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", REDACTED);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                result.put(name, isSecretKey(name) ? REDACTED : scrub(item, depth + 1));
            });
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(scrub(item, depth + 1)));
            return result;
        }
        return String.valueOf(value);
    }

    /**
     * 判断{@code SecretKey}是否满足要求。
     *
     * @param key {@code key}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isSecretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SECRET_KEYS.stream().anyMatch(normalized::contains);
    }

    /**
     * 处理提供方Error并返回对应结果。
     *
     * @param data 数据参数
     * @return 处理结果
     */
    private String providerError(Object data) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (data instanceof Map<?, ?> map) {
            for (String key : List.of("error", "message", "detail")) {
                Object value = map.get(key);
                if (value instanceof String text && !text.isBlank()) {
                    return truncate(text.strip(), 1000);
                }
            }
            Object content = map.get("content");
            if (content instanceof String text && !text.isBlank()) {
                return truncate(text.strip(), 1000);
            }
            if (content instanceof Map<?, ?> contentMap) {
                for (String key : List.of("message", "error", "detail")) {
                    Object value = contentMap.get(key);
                    if (value instanceof String text && !text.isBlank()) {
                        return truncate(text.strip(), 1000);
                    }
                }
            }
        }
        return "远端服务返回了错误";
    }

    /**
     * 处理{@code safeError}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private String safeError(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return truncate(String.valueOf(scrub(message, 0)), 1000);
    }

    /**
     * 处理{@code failureStatus}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @param sql {@code sql}参数
     * @return 处理结果
     */
    private String failureStatus(RuntimeException exception, boolean sql) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (exception instanceof ServiceException serviceException) {
            Integer code = serviceException.getCode();
            String message = serviceException.getMessage();
            if ((message != null && message.startsWith("tool_unavailable:")) || Integer.valueOf(503).equals(code)) {
                return "tool_unavailable";
            }
            if (Integer.valueOf(HttpStatus.FORBIDDEN).equals(code)) {
                return "authorization_error";
            }
            if (Integer.valueOf(HttpStatus.CONFLICT).equals(code)) {
                return "conflict";
            }
            if (Integer.valueOf(HttpStatus.BAD_REQUEST).equals(code)) {
                return "invalid_arguments";
            }
        }
        return sql ? "query_error" : "transport_error";
    }

    /**
     * 处理{@code retryable}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @param status 目标状态
     * @param sql {@code sql}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean retryable(RuntimeException exception, String status, boolean sql) {
        if (Set.of("authorization_error", "conflict", "invalid_arguments").contains(status)) {
            return false;
        }
        if ("tool_unavailable".equals(status)) {
            return false;
        }
        if (exception instanceof ServiceException serviceException && serviceException.getCode() != null) {
            return serviceException.getCode() == 429 || serviceException.getCode() >= 500;
        }
        return !sql;
    }

    /**
     * 处理{@code truncate}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    private String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /**
     * 封装提供方相关的不可变数据。
     */
    private record ProviderResult(boolean error, Object data, boolean retryable) {
    }
}
