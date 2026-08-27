package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.domain.AgentTool;
import group.aitools.nhs.platform.connector.mapper.ConnectorCatalogMapper;
import group.aitools.nhs.platform.connector.web.CreateToolRequest;
import group.aitools.nhs.platform.connector.web.CreateToolVersionRequest;
import group.aitools.nhs.platform.connector.web.ToolView;
import group.aitools.nhs.platform.connector.web.UpdateToolStatusRequest;
import group.aitools.nhs.platform.data.service.DataQueryExecutionService;
import group.aitools.nhs.platform.data.web.DataQueryRequest;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 负责工具目录相关的业务编排与领域规则处理。
 * Immutable tool-version registry and user-visible authorized catalog. */
@Service
public class ToolCatalogService {

    private static final Pattern TOOL_KEY = Pattern.compile("[a-z][a-z0-9._-]{0,127}");
    private static final Set<String> TOOL_TYPES = Set.of("builtin", "api", "mcp", "search", "sql", "sandbox");
    private static final Set<String> MANUAL_TOOL_TYPES = Set.of("builtin", "api", "search", "sql", "sandbox");
    private static final Set<String> RISK_LEVELS = Set.of("R0", "R1", "R2", "R3");
    private static final Set<String> STATUSES = Set.of("active", "disabled", "deprecated");
    private static final Set<String> POLICY_KEYS = Set.of(
        "handlerKey", "method", "path", "contentType", "timeoutMs", "retryCount",
        "maxOutputBytes", "readOnly", "networkPolicy", "workspaceAccess",
        "templateKey", "workspacePath", "allowedHosts", "memoryMb", "cpuMillis",
        "pidsLimit", "priority", "datasetId", "sqlTemplate", "queryPurpose"
    );

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final ConnectorCatalogMapper mapper;
    private final ConnectorConfigurationValidator validator;
    private final SqlToolTemplateEngine sqlTemplateEngine;
    private final DataQueryExecutionService queryExecutionService;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code ToolCatalogService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param mapper {@code mapper}参数
     * @param validator {@code validator}参数
     * @param sqlTemplateEngine sql模板Engine参数
     * @param queryExecutionService 查询执行Service参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public ToolCatalogService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        ConnectorCatalogMapper mapper,
        ConnectorConfigurationValidator validator,
        SqlToolTemplateEngine sqlTemplateEngine,
        DataQueryExecutionService queryExecutionService,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.validator = validator;
        this.sqlTemplateEngine = sqlTemplateEngine;
        this.queryExecutionService = queryExecutionService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param toolType 业务类型
     * @param connectorId 资源标识
     * @param search {@code search}参数
     * @param includeInactive {@code includeInactive}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ToolView> list(
        String toolType,
        Long connectorId,
        String search,
        boolean includeInactive,
        int limit
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(null, null, "list"));
        return mapper.selectLatestTools(
                optionalEnum(toolType, TOOL_TYPES, "工具类型"), connectorId,
                normalizeSearch(search), includeInactive, principal.id(), limit
            ).stream()
            .map(tool -> ToolView.from(tool, jsonMapper))
            .toList();
    }

    /**
 * 处理{@code available}并返回对应结果。
 * Returns only tools the current principal may use or request approval for. */
    public List<ToolView> available(String toolType, Long connectorId, String search, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        return mapper.selectLatestTools(
                optionalEnum(toolType, Set.of("builtin", "api", "mcp", "search", "sql", "sandbox"), "工具类型"),
                connectorId, normalizeSearch(search), false, principal.id(), limit
            ).stream()
            .filter(this::runtimeExecutable)
            .filter(tool -> visibleDecision(principal, tool))
            .map(tool -> ToolView.from(tool, jsonMapper))
            .toList();
    }

    /**
     * 获取{@code get}。
     *
     * @param toolId 资源标识
     * @return 处理结果
     */
    public ToolView get(Long toolId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(toolId, null, "view"));
        AgentTool tool = requireTool(toolId);
        requireConnectorVisible(principal, tool);
        return ToolView.from(tool, jsonMapper);
    }

    /**
     * 处理{@code versions}并返回对应结果。
     *
     * @param toolKey 工具Key参数
     * @return 符合条件的数据集合
     */
    public List<ToolView> versions(String toolKey) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String normalized = normalizeToolKey(toolKey);
        authorizationEnforcer.requireAllowed(principal, context(null, normalized, "view"));
        return mapper.selectToolVersions(normalized).stream()
            .filter(tool -> connectorVisible(principal, tool))
            .map(tool -> ToolView.from(tool, jsonMapper))
            .toList();
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ToolView create(CreateToolRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String toolKey = normalizeToolKey(request.toolKey());
        authorizationEnforcer.requireAllowed(principal, context(null, toolKey, "create"));
        mapper.lockToolKey(toolKey);
        if (!mapper.selectToolVersions(toolKey).isEmpty()) {
            throw conflict("工具标识已存在：" + toolKey);
        }
        AgentTool tool = prepare(
            toolKey, 1, request.name(), request.description(), request.connectorId(),
            request.toolType(), request.riskLevel(), request.parameterSchema(),
            request.executionPolicy(), request.externalName(), request.status(), principal
        );
        insert(tool);
        return ToolView.from(tool, jsonMapper);
    }

    /**
     * 创建并保存版本。
     *
     * @param toolKey 工具Key参数
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ToolView createVersion(String toolKey, CreateToolVersionRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String normalized = normalizeToolKey(toolKey);
        authorizationEnforcer.requireAllowed(principal, context(null, normalized, "create"));
        mapper.lockToolKey(normalized);
        List<AgentTool> versions = mapper.selectToolVersions(normalized);
        if (versions.isEmpty()) {
            throw new ServiceException("工具不存在", HttpStatus.NOT_FOUND);
        }
        requireConnectorManageable(principal, versions.getFirst());
        if (versions.stream().anyMatch(tool -> "mcp".equals(tool.getToolType()))) {
            throw conflict("MCP 工具版本只能由服务发现生成");
        }
        AgentTool tool = prepare(
            normalized, mapper.selectNextToolVersion(normalized), request.name(), request.description(),
            request.connectorId(), request.toolType(), request.riskLevel(), request.parameterSchema(),
            request.executionPolicy(), request.externalName(), request.status(), principal
        );
        insert(tool);
        return ToolView.from(tool, jsonMapper);
    }

    /**
     * 更新{@code Status}。
     *
     * @param toolId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ToolView updateStatus(Long toolId, UpdateToolStatusRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(toolId, null, "update"));
        AgentTool tool = requireTool(toolId);
        requireConnectorManageable(principal, tool);
        String expected = requiredEnum(request.expectedStatus(), STATUSES, "原工具状态");
        String status = requiredEnum(request.status(), STATUSES, "工具状态");
        if (!expected.equals(tool.getStatus())) {
            throw conflict("工具状态已被其他请求修改");
        }
        if ("active".equals(status) && !Boolean.TRUE.equals(tool.getIsAvailable())) {
            throw conflict("远端已不可用的工具不能重新启用，请先执行 MCP 发现");
        }
        if ("active".equals(status) && "mcp".equals(tool.getToolType())) {
            AgentConnector connector = mapper.selectConnectorById(tool.getConnectorId());
            if (connector == null || !"active".equals(connector.getStatus())) {
                throw conflict("MCP 服务已停用，不能发布工具");
            }
        }
        if ("active".equals(status) && !runtimeExecutable(tool)) {
            throw conflict("内置处理器尚未实现，不能启用");
        }
        if (expected.equals(status)) {
            return ToolView.from(tool, jsonMapper);
        }
        LocalDateTime now = LocalDateTime.now();
        if (mapper.updateToolStatus(toolId, expected, status, principal.id(), now) != 1) {
            throw conflict("工具状态已被其他请求修改");
        }
        tool.setStatus(status);
        tool.setUpdateBy(principal.id());
        tool.setUpdateTime(now);
        return ToolView.from(tool, jsonMapper);
    }

    /**
 * 删除{@code delete}。
 * Deletes the complete immutable version family after reference checks. */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long toolId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(toolId, null, "delete"));
        AgentTool tool = requireTool(toolId);
        requireConnectorManageable(principal, tool);
        if ("mcp".equals(tool.getToolType())) {
            throw conflict("MCP 工具由服务发现维护，不能手工删除；请停用连接器");
        }
        mapper.lockToolKey(tool.getToolKey());
        tool = requireTool(toolId);
        if (mapper.countActiveToolFamilyReferences(tool.getToolKey()) > 0) {
            throw conflict("工具仍被草稿或已发布 Agent 版本引用，不能删除");
        }
        if (mapper.softDeleteToolFamily(
            tool.getToolKey(), principal.id(), LocalDateTime.now()
        ) < 1) {
            throw conflict("工具已被其他请求删除");
        }
    }

    /**
     * 处理{@code prepare}并返回对应结果。
     *
     * @param toolKey 工具Key参数
     * @param versionNo 版本No参数
     * @param name 名称
     * @param description {@code description}参数
     * @param connectorId 资源标识
     * @param toolType 业务类型
     * @param riskLevel 风险Level参数
     * @param parameterSchema {@code parameterSchema}参数
     * @param executionPolicy 执行策略参数
     * @param externalName 名称
     * @param status 目标状态
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private AgentTool prepare(
        String toolKey,
        int versionNo,
        String name,
        String description,
        Long connectorId,
        String toolType,
        String riskLevel,
        Map<String, Object> parameterSchema,
        Map<String, Object> executionPolicy,
        String externalName,
        String status,
        CurrentPrincipal principal
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String normalizedType = requiredEnum(toolType, MANUAL_TOOL_TYPES, "工具类型");
        AgentConnector connector = validateConnector(connectorId, normalizedType, principal.id());
        Map<String, Object> schema = validator.toolSchema(parameterSchema, "工具参数 Schema");
        Map<String, Object> policy = validator.document(executionPolicy, POLICY_KEYS, "工具执行策略");
        String normalizedRisk = requiredEnum(riskLevel, RISK_LEVELS, "工具风险等级");
        validatePolicy(normalizedType, policy, normalizedRisk);
        if ("sql".equals(normalizedType)) {
            SqlToolTemplateEngine.Configuration configuration = sqlTemplateEngine.validate(schema, policy);
            queryExecutionService.validateForPrincipal(principal, new DataQueryRequest(
                configuration.datasetId(), null, null, null,
                configuration.queryPurpose(), configuration.validationSql()
            ));
        }
        String normalizedExternalName = optionalText(externalName, 255);
        if (Set.of("api", "search").contains(normalizedType) && normalizedExternalName == null) {
            throw badRequest("API/搜索工具必须配置外部动作名称");
        }
        AgentTool tool = new AgentTool();
        tool.setId(idGenerator.nextId());
        tool.setToolKey(toolKey);
        tool.setName(requiredText(name, 128, "工具名称"));
        tool.setDescription(optionalText(description, 12000));
        tool.setConnectorId(connector == null ? null : connector.getId());
        tool.setToolType(normalizedType);
        tool.setRiskLevel(normalizedRisk);
        tool.setParameterSchemaJson(validator.boundedJson(schema, "工具参数 Schema"));
        tool.setExecutionPolicyJson(validator.boundedJson(policy, "工具执行策略"));
        tool.setExternalName(normalizedExternalName);
        String normalizedStatus = requiredEnum(status, STATUSES, "工具状态");
        if ("builtin".equals(normalizedType)) {
            String handlerKey = optionalText(policy.get("handlerKey"), 128);
            if (!BuiltinToolCatalog.contains(handlerKey)) {
                throw badRequest("内置处理器不在平台目录中：" + handlerKey);
            }
            if ("active".equals(normalizedStatus) && !BuiltinToolCatalog.implemented(handlerKey)) {
                throw conflict("内置处理器尚未实现，不能启用：" + handlerKey);
            }
        }
        tool.setStatus(normalizedStatus);
        tool.setVersionNo(versionNo);
        tool.setIsAvailable(true);
        tool.setCreateBy(principal.id());
        tool.setCreateTime(LocalDateTime.now());
        tool.setDelFlag("0");
        tool.setExtraJson("{}");
        return tool;
    }

    /**
     * 校验连接器，并在条件不满足时终止处理。
     *
     * @param connectorId 资源标识
     * @param toolType 业务类型
     * @param actorId 资源标识
     * @return 处理结果
     */
    private AgentConnector validateConnector(Long connectorId, String toolType, Long actorId) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (Set.of("api", "search").contains(toolType)) {
            if (connectorId == null) {
                throw badRequest("API/搜索工具必须绑定连接器");
            }
            AgentConnector connector = mapper.selectConnectorById(connectorId);
            if (connector == null || !toolType.equals(connector.getProviderType())) {
                throw badRequest("工具类型与连接器类型不匹配");
            }
            if (!"active".equals(connector.getStatus())) {
                throw conflict("工具只能绑定已启用连接器");
            }
            if ("personal".equals(connector.getScopeType()) && !actorId.equals(connector.getOwnerId())) {
                throw new ServiceException("连接器不存在", HttpStatus.NOT_FOUND);
            }
            return connector;
        }
        if (connectorId != null) {
            throw badRequest("该工具类型不能绑定连接器");
        }
        return null;
    }

    /**
     * 校验策略，并在条件不满足时终止处理。
     *
     * @param toolType 业务类型
     * @param policy 策略参数
     * @param riskLevel 风险Level参数
     */
    private void validatePolicy(String toolType, Map<String, Object> policy, String riskLevel) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if ("sql".equals(toolType)) {
            if (!"R1".equals(riskLevel)) {
                throw badRequest("SQL 只读查询工具必须使用 R1 风险等级");
            }
            Set<String> allowed = Set.of("datasetId", "sqlTemplate", "queryPurpose", "readOnly");
            if (!allowed.containsAll(policy.keySet())) {
                throw badRequest("SQL 工具执行策略包含无效字段");
            }
        }
        if (Set.of("api", "search").contains(toolType)) {
            Object method = policy.get("method");
            Object path = policy.get("path");
            if (!(method instanceof String methodText)
                || !Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(methodText.toUpperCase(Locale.ROOT))) {
                throw badRequest("API 工具执行策略必须包含有效 method");
            }
            String normalizedMethod = methodText.toUpperCase(Locale.ROOT);
            if (!(path instanceof String pathText) || !pathText.startsWith("/") || pathText.contains("..")
                || pathText.contains("\\") || pathText.contains("?") || pathText.contains("#")
                || pathText.toLowerCase(Locale.ROOT).matches(".*%(2e|2f|5c).*")
                || pathText.length() > 512) {
                throw badRequest("API 工具执行策略必须包含安全 path");
            }
            boolean readOnly = optionalBoolean(policy.get("readOnly"), false, "readOnly");
            if ("DELETE".equals(normalizedMethod) && readOnly) {
                throw badRequest("DELETE 工具不能声明为只读");
            }
            if ("R0".equals(riskLevel)) {
                throw badRequest("外部 API/搜索工具不能使用 R0 风险等级");
            }
            if (!readOnly && Set.of("R0", "R1").contains(riskLevel)) {
                throw badRequest("非只读 API 工具必须使用 R2 或 R3 风险等级");
            }
            if ("DELETE".equals(normalizedMethod) && !"R3".equals(riskLevel)) {
                throw badRequest("DELETE 工具必须使用 R3 风险等级");
            }
            optionalInteger(policy.get("timeoutMs"), 1_000, 120_000, "timeoutMs");
            optionalInteger(policy.get("maxOutputBytes"), 1_024, 10 * 1024 * 1024, "maxOutputBytes");
            int retries = optionalInteger(policy.get("retryCount"), 0, 2, "retryCount");
            if (retries > 0 && (!readOnly || !"GET".equals(normalizedMethod))) {
                throw badRequest("只有只读 GET 工具可以配置重试");
            }
            String contentType = optionalText(policy.get("contentType"), 64);
            if (contentType != null && !"application/json".equalsIgnoreCase(contentType)) {
                throw badRequest("API 工具仅支持 application/json 请求体");
            }
            String networkPolicy = optionalText(policy.get("networkPolicy"), 64);
            if (networkPolicy != null && !"connector_origin".equals(networkPolicy)) {
                throw badRequest("API 工具 networkPolicy 仅支持 connector_origin");
            }
        }
        if ("sandbox".equals(toolType)) {
            String templateKey = optionalText(policy.get("templateKey"), 64);
            if (templateKey == null || !templateKey.matches("[a-z][a-z0-9._-]{1,63}")) {
                throw badRequest("沙箱工具必须配置有效 templateKey");
            }
            if (!Set.of("R2", "R3").contains(riskLevel)) {
                throw badRequest("沙箱工具必须使用 R2 或 R3 风险等级");
            }
            String networkPolicy = optionalText(policy.get("networkPolicy"), 16);
            if (networkPolicy != null && !"none".equals(networkPolicy)) {
                throw badRequest("一期沙箱工具 networkPolicy 仅支持 none");
            }
            if (policy.containsKey("allowedHosts")) {
                throw badRequest("禁网沙箱工具不能配置 allowedHosts");
            }
            String workspaceAccess = optionalText(policy.get("workspaceAccess"), 16);
            if (workspaceAccess != null
                && !Set.of("read_only", "read_write").contains(workspaceAccess)) {
                throw badRequest("沙箱工具 workspaceAccess 无效");
            }
            String workspacePath = optionalText(policy.get("workspacePath"), 512);
            if (workspacePath != null
                && (workspacePath.startsWith("/") || workspacePath.contains("..")
                    || workspacePath.contains("\\") || workspacePath.contains(":"))) {
                throw badRequest("沙箱工具 workspacePath 无效");
            }
            optionalInteger(policy.get("timeoutMs"), 1_000, 3_600_000, "timeoutMs");
            optionalInteger(policy.get("memoryMb"), 64, 32_768, "memoryMb");
            optionalInteger(policy.get("cpuMillis"), 100, 16_000, "cpuMillis");
            optionalInteger(policy.get("pidsLimit"), 16, 2_048, "pidsLimit");
            optionalInteger(
                policy.get("maxOutputBytes"), 1_024, 10 * 1024 * 1024, "maxOutputBytes"
            );
            optionalInteger(policy.get("priority"), -100, 100, "priority");
            if (policy.containsKey("retryCount") || policy.containsKey("method")
                || policy.containsKey("path") || policy.containsKey("handlerKey")) {
                throw badRequest("沙箱工具不能配置网络或进程处理器字段");
            }
        }
    }

    /**
     * 处理{@code optionalInteger}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private int optionalInteger(Object value, int minimum, int maximum, String label) {
        if (value == null) {
            return 0;
        }
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()
            || number.longValue() < minimum || number.longValue() > maximum) {
            throw badRequest(label + "无效");
        }
        return Math.toIntExact(number.longValue());
    }

    /**
     * 处理{@code optionalBoolean}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param defaultValue {@code defaultValue}参数
     * @param label {@code label}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean optionalBoolean(Object value, boolean defaultValue, String label) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean bool)) {
            throw badRequest(label + "必须是布尔值");
        }
        return bool;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String optionalText(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw badRequest("工具执行策略文本字段无效");
        }
        return optionalText(text, maxLength);
    }

    /**
     * 创建并保存{@code insert}。
     *
     * @param tool 工具参数
     */
    private void insert(AgentTool tool) {
        try {
            mapper.insertTool(tool);
        } catch (DuplicateKeyException exception) {
            throw conflict("工具版本已存在或发生并发创建");
        }
    }

    /**
     * 处理{@code visibleDecision}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param tool 工具参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean visibleDecision(CurrentPrincipal principal, AgentTool tool) {
        AuthorizationDecision decision = authorizationEnforcer.decide(
            principal, context(tool.getId(), tool.getToolKey(), "use")
        );
        return decision.effect() == PermissionEffect.ALLOW
            || decision.effect() == PermissionEffect.APPROVAL_REQUIRED;
    }

    /**
     * 执行{@code timeExecutable}相关的处理流程。
     *
     * @param tool 工具参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean runtimeExecutable(AgentTool tool) {
        if (!"builtin".equals(tool.getToolType())) {
            return Boolean.TRUE.equals(tool.getIsAvailable());
        }
        Map<String, Object> policy = tool.getExecutionPolicyJson() == null
            ? Map.of()
            : jsonMapper.readValue(tool.getExecutionPolicyJson(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        Object handler = policy.get("handlerKey");
        String key = handler instanceof String text && !text.isBlank()
            ? text : tool.getExternalName() == null ? tool.getToolKey() : tool.getExternalName();
        return Boolean.TRUE.equals(tool.getIsAvailable()) && BuiltinToolCatalog.implemented(key);
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
     * 处理连接器Visible并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param tool 工具参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean connectorVisible(CurrentPrincipal principal, AgentTool tool) {
        if (tool.getConnectorId() == null) {
            return true;
        }
        AgentConnector connector = mapper.selectConnectorById(tool.getConnectorId());
        return connector != null && (connector.getScopeType() == null
            || "global".equals(connector.getScopeType())
            || ("personal".equals(connector.getScopeType()) && principal.id().equals(connector.getOwnerId())));
    }

    /**
     * 校验连接器Visible，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param tool 工具参数
     */
    private void requireConnectorVisible(CurrentPrincipal principal, AgentTool tool) {
        if (!connectorVisible(principal, tool)) {
            throw new ServiceException("工具不存在", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 校验连接器Manageable，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param tool 工具参数
     */
    private void requireConnectorManageable(CurrentPrincipal principal, AgentTool tool) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (tool.getConnectorId() == null) {
            return;
        }
        AgentConnector connector = mapper.selectConnectorById(tool.getConnectorId());
        if (connector == null) {
            throw new ServiceException("工具不存在", HttpStatus.NOT_FOUND);
        }
        if ("personal".equals(connector.getScopeType())) {
            if (!principal.id().equals(connector.getOwnerId())) {
                throw new ServiceException("工具不存在", HttpStatus.NOT_FOUND);
            }
            return;
        }
        if (connector.getScopeType() != null
            && !principal.hasRole(group.aitools.nhs.platform.iam.domain.PlatformRole.PLATFORM_ADMIN)) {
            throw new ServiceException("只有平台管理员可以维护企业共享 MCP 工具", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 处理上下文并返回对应结果。
     *
     * @param id 资源标识
     * @param key {@code key}参数
     * @param action {@code action}参数
     * @return 处理结果
     */
    private PermissionContext context(Long id, String key, String action) {
        return new PermissionContext("tool", id, key, action, ResourceState.ACTIVE, true, Set.of(), null);
    }

    /**
     * 处理normalize工具Key并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeToolKey(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!TOOL_KEY.matcher(normalized).matches()) {
            throw badRequest("工具标识格式无效");
        }
        return normalized;
    }

    /**
     * 处理{@code normalizeSearch}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeSearch(String value) {
        return value == null || value.isBlank() ? null : requiredText(value, 128, "搜索条件");
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredText(String value, int maxLength, String label) {
        String normalized = optionalText(value, maxLength);
        if (normalized == null) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength || normalized.chars().anyMatch(ch -> ch == 0 || ch == '\r')) {
            throw badRequest("文本内容无效或超过长度限制");
        }
        return normalized;
    }

    /**
     * 处理{@code optionalEnum}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String optionalEnum(String value, Set<String> allowed, String label) {
        return value == null || value.isBlank() ? null : requiredEnum(value, allowed, label);
    }

    /**
     * 校验{@code dEnum}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredEnum(String value, Set<String> allowed, String label) {
        String normalized = value == null ? "" : value.strip();
        if (!allowed.contains(normalized)) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
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
