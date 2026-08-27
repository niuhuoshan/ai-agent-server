package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.mapper.ConnectorCatalogMapper;
import group.aitools.nhs.platform.connector.service.McpDiscoveryPersistenceService.DiscoveryWork;
import group.aitools.nhs.platform.connector.service.McpDiscoveryPersistenceService.PreparedDiscovery;
import group.aitools.nhs.platform.connector.service.McpDiscoveryPersistenceService.PreparedRemoteTool;
import group.aitools.nhs.platform.connector.web.ConnectorView;
import group.aitools.nhs.platform.connector.web.CreateConnectorRequest;
import group.aitools.nhs.platform.connector.web.McpDiscoveryView;
import group.aitools.nhs.platform.connector.web.McpConnectionTestView;
import group.aitools.nhs.platform.connector.web.McpConnectionTestRequest;
import group.aitools.nhs.platform.connector.web.McpConnectionToolPreviewView;
import group.aitools.nhs.platform.connector.web.McpImportEntryView;
import group.aitools.nhs.platform.connector.web.McpServersImportPreviewRequest;
import group.aitools.nhs.platform.connector.web.McpServersImportPreviewView;
import group.aitools.nhs.platform.connector.web.McpServersImportRequest;
import group.aitools.nhs.platform.connector.web.McpWizardPublishRequest;
import group.aitools.nhs.platform.connector.web.McpWizardValidationRequest;
import group.aitools.nhs.platform.connector.web.McpWizardValidationView;
import group.aitools.nhs.platform.connector.web.UpdateConnectorRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 负责连接器目录相关的业务编排与领域规则处理。
 * Connector CRUD and explicitly triggered MCP discovery. */
@Service
public class ConnectorCatalogService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Pattern REMOTE_TOOL_NAME = Pattern.compile("[A-Za-z0-9._:/-]{1,255}");
    private static final Set<String> SERVER_INFO_KEYS = Set.of(
        "name", "title", "version", "description", "websiteUrl"
    );
    private static final Set<String> TOOL_ANNOTATION_KEYS = Set.of(
        "title", "readOnly", "destructive", "idempotent", "openWorld", "returnDirect"
    );

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final ConnectorCatalogMapper mapper;
    private final ConnectorConfigurationValidator validator;
    private final ConnectorEndpointPolicy endpointPolicy;
    private final McpServersImportParser importParser;
    private final ConnectorMcpConnectionFactory connectionFactory;
    private final McpRemoteClient remoteClient;
    private final McpDiscoveryPersistenceService discoveryPersistence;
    private final McpRuntimeLifecycleService runtimeLifecycle;
    private final AgentAuditEventMapper auditMapper;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code ConnectorCatalogService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param mapper {@code mapper}参数
     * @param validator {@code validator}参数
     * @param endpointPolicy endpoint策略参数
     * @param importParser 导入Parser参数
     * @param connectionFactory {@code connectionFactory}参数
     * @param remoteClient remote客户端参数
     * @param discoveryPersistence {@code discoveryPersistence}参数
     * @param runtimeLifecycle 运行时Lifecycle参数
     * @param auditMapper 审计Mapper参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public ConnectorCatalogService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        ConnectorCatalogMapper mapper,
        ConnectorConfigurationValidator validator,
        ConnectorEndpointPolicy endpointPolicy,
        McpServersImportParser importParser,
        ConnectorMcpConnectionFactory connectionFactory,
        McpRemoteClient remoteClient,
        McpDiscoveryPersistenceService discoveryPersistence,
        McpRuntimeLifecycleService runtimeLifecycle,
        AgentAuditEventMapper auditMapper,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.validator = validator;
        this.endpointPolicy = endpointPolicy;
        this.importParser = importParser;
        this.connectionFactory = connectionFactory;
        this.remoteClient = remoteClient;
        this.discoveryPersistence = discoveryPersistence;
        this.runtimeLifecycle = runtimeLifecycle;
        this.auditMapper = auditMapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param providerType 业务类型
     * @param search {@code search}参数
     * @param includeInactive {@code includeInactive}参数
     * @param scope 范围参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ConnectorView> list(
        String providerType,
        String search,
        boolean includeInactive,
        String scope,
        int limit
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(null, null, "list"));
        String normalizedProvider = providerType == null ? null : validator.providerType(providerType);
        String normalizedScope = scope == null || scope.isBlank() ? null : validator.scope(scope);
        return mapper.selectConnectors(
                normalizedProvider, normalizeSearch(search), includeInactive,
                normalizedScope, principal.id(), limit
            ).stream()
            .map(value -> view(value, principal))
            .toList();
    }

    /**
     * 获取{@code get}。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    public ConnectorView get(Long connectorId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(connectorId, null, "view"));
        return view(requireVisibleConnector(connectorId, principal), principal);
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ConnectorView create(CreateConnectorRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String connectorKey = validator.connectorKey(request.connectorKey());
        authorizationEnforcer.requireAllowed(principal, context(null, connectorKey, "create"));
        String scope = validator.scope(request.scope());
        requireScopeCreation(principal, scope);
        AgentConnector connector = new AgentConnector();
        connector.setId(idGenerator.nextId());
        connector.setConnectorKey(connectorKey);
        connector.setScopeType(scope);
        connector.setOwnerId("personal".equals(scope) ? principal.id() : null);
        apply(
            connector, request.name(), request.providerType(), request.endpointUrl(),
            request.credentialRef(), request.config(), request.status()
        );
        connector.setRevisionNo(1L);
        connector.setCreateBy(principal.id());
        connector.setCreateTime(LocalDateTime.now());
        connector.setDelFlag("0");
        connector.setExtraJson("{}");
        try {
            mapper.insertConnector(connector);
        } catch (DuplicateKeyException exception) {
            throw conflict("连接器标识已存在：" + connectorKey);
        }
        return view(connector, principal);
    }

    /**
     * 更新{@code update}。
     *
     * @param connectorId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ConnectorView update(Long connectorId, UpdateConnectorRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(connectorId, null, "update"));
        mapper.lockConnector(connectorId);
        AgentConnector connector = requireVisibleConnector(connectorId, principal);
        requireManage(principal, connector);
        if (!validator.scope(request.scope()).equals(connector.getScopeType())) {
            throw badRequest("连接器范围创建后不可变；请新建目标范围连接器");
        }
        if (connector.getRevisionNo() == null || request.expectedRevision() != connector.getRevisionNo()) {
            throw conflict("连接器已被其他请求修改");
        }
        String oldProvider = connector.getProviderType();
        String oldEndpoint = connector.getEndpointUrl();
        String oldCredential = connector.getCredentialRef();
        String oldConfig = connector.getConfigJson();
        String oldStatus = connector.getStatus();
        apply(
            connector, request.name(), request.providerType(), request.endpointUrl(),
            request.credentialRef(), request.config(), request.status()
        );
        boolean connectionChanged = !Objects.equals(oldProvider, connector.getProviderType())
            || !Objects.equals(oldEndpoint, connector.getEndpointUrl())
            || !Objects.equals(oldCredential, connector.getCredentialRef())
            || !Objects.equals(oldConfig, connector.getConfigJson());
        connector.setUpdateBy(principal.id());
        connector.setUpdateTime(LocalDateTime.now());
        if (mapper.updateConnector(connector) != 1) {
            throw conflict("连接器已被其他请求修改");
        }
        if (connectionChanged) {
            mapper.invalidateConnectorTools(connectorId, principal.id(), connector.getUpdateTime());
            mapper.clearConnectorDiscovery(connectorId);
            connector.setLastDiscoveryId(null);
        }
        if ((connectionChanged || !Objects.equals(oldStatus, connector.getStatus()))
            && ("mcp".equals(oldProvider) || "mcp".equals(connector.getProviderType()))) {
            runtimeLifecycle.invalidateConnector(
                connectorId,
                "MCP 连接器配置或启用状态已变更，运行挂载已释放"
            );
        }
        connector.setRevisionNo(connector.getRevisionNo() + 1);
        return view(connector, principal);
    }

    /**
     * 删除{@code delete}。
     *
     * @param connectorId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long connectorId, Long expectedRevision) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(connectorId, null, "delete"));
        mapper.lockConnector(connectorId);
        AgentConnector connector = requireVisibleConnector(connectorId, principal);
        requireManage(principal, connector);
        if (!expectedRevision.equals(connector.getRevisionNo())) {
            throw conflict("连接器已被其他请求修改");
        }
        int toolCount = mapper.countConnectorTools(connectorId);
        if (toolCount > 0 && mapper.countConnectorManagedTools(connectorId) > 0) {
            throw conflict("连接器已有工具版本，不能删除；请停用连接器");
        }
        LocalDateTime now = LocalDateTime.now();
        if (toolCount > 0) {
            mapper.softDeleteConnectorTools(connectorId, principal.id(), now);
        }
        if (mapper.softDeleteConnector(
            connectorId, expectedRevision, principal.id(), now
        ) != 1) {
            throw conflict("连接器已被其他请求修改");
        }
        if ("mcp".equals(connector.getProviderType())) {
            runtimeLifecycle.invalidateConnector(connectorId, "MCP 连接器已删除，运行挂载已释放");
        }
    }

    /**
     * 处理{@code discoveries}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<McpDiscoveryView> discoveries(Long connectorId, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(connectorId, null, "view"));
        AgentConnector connector = requireVisibleConnector(connectorId, principal);
        if (!"mcp".equals(connector.getProviderType())) {
            throw badRequest("只有 MCP 连接器拥有发现记录");
        }
        return mapper.selectDiscoveries(connectorId, limit).stream()
            .map(value -> McpDiscoveryView.from(value, jsonMapper))
            .toList();
    }

    /**
     * 处理{@code discover}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    public McpDiscoveryView discover(Long connectorId) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(connectorId, null, "operate"));
        requireManage(principal, requireVisibleConnector(connectorId, principal));
        DiscoveryWork work = discoveryPersistence.begin(connectorId, principal.id());
        try {
            McpRemoteClient.DiscoveryResult result = remoteClient.discover(
                connectionFactory.create(work.connector())
            );
            PreparedDiscovery prepared = prepare(work, result);
            if (!discoveryPersistence.complete(work, prepared)) {
                throw conflict("连接器配置在发现期间发生变化，发现结果未生效");
            }
            work.discovery().setStatus("succeeded");
            work.discovery().setProtocolVersion(prepared.protocolVersion());
            work.discovery().setServerInfoJson(prepared.serverInfoJson());
            work.discovery().setToolCount(prepared.tools().size());
            work.discovery().setContentHash(prepared.contentHash());
            work.discovery().setCompletedAt(LocalDateTime.now());
            return McpDiscoveryView.from(work.discovery(), jsonMapper);
        } catch (ServiceException exception) {
            if (exception.getCode() != HttpStatus.CONFLICT
                || "running".equals(work.discovery().getStatus())) {
                discoveryPersistence.fail(work, safeError(exception));
            }
            throw exception;
        } catch (RuntimeException exception) {
            discoveryPersistence.fail(work, safeError(exception));
            throw new ServiceException("MCP 工具发现失败：" + safeError(exception), 502);
        }
    }

    /**
 * 处理{@code testConnection}并返回对应结果。
 * Performs a real MCP handshake without changing the discovered tool catalog. */
    public McpConnectionTestView testConnection(Long connectorId) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(connectorId, null, "operate"));
        AgentConnector connector = requireVisibleConnector(connectorId, principal);
        requireManage(principal, connector);
        if (!"mcp".equals(connector.getProviderType())) {
            throw badRequest("只有 MCP 连接器支持协议连接测试");
        }
        if (!"active".equals(connector.getStatus())) {
            throw conflict("请先启用 MCP 连接器再测试连接");
        }
        Instant started = Instant.now();
        LocalDateTime checkedAt = LocalDateTime.now();
        try {
            McpConnectionTestView result = performConnectionTest(connector, started, checkedAt);
            if (mapper.markConnectorCheckSucceeded(
                connectorId, connector.getRevisionNo(), checkedAt
            ) != 1) {
                throw conflict("连接器配置在测试期间发生变化，测试结果未写入");
            }
            return result;
        } catch (ServiceException exception) {
            persistCheckFailure(connector, checkedAt, exception);
            throw exception;
        } catch (RuntimeException exception) {
            String error = safeError(exception);
            mapper.markConnectorCheckFailed(
                connectorId, connector.getRevisionNo(), error, checkedAt
            );
            throw new ServiceException("MCP 连接测试失败：" + error, 502);
        }
    }

    /**
 * 处理{@code testDraftConnection}并返回对应结果。
 * Tests an unsaved MCP form without persisting either credentials or discovery results. */
    public McpConnectionTestView testDraftConnection(McpConnectionTestRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (request.connectorId() == null) {
            authorizationEnforcer.requireAllowed(principal, context(null, null, "create"));
        } else {
            authorizationEnforcer.requireAllowed(principal, context(request.connectorId(), null, "update"));
            requireManage(principal, requireVisibleConnector(request.connectorId(), principal));
        }
        AgentConnector connector = new AgentConnector();
        connector.setId(0L);
        connector.setRevisionNo(1L);
        apply(
            connector, request.name(), "mcp", request.endpointUrl(),
            request.credentialRef(), request.config(), "active"
        );
        Instant started = Instant.now();
        LocalDateTime checkedAt = LocalDateTime.now();
        try {
            return performConnectionTest(connector, started, checkedAt);
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ServiceException("MCP 连接测试失败：" + safeError(exception), 502);
        }
    }

    /**
     * 校验{@code McpWizard}，并在条件不满足时终止处理。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public McpWizardValidationView validateMcpWizard(McpWizardValidationRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(null, request.connectorKey(), "create"));
        validator.connectorKey(request.connectorKey());
        String namespace = request.namespace().strip().toLowerCase(Locale.ROOT);
        Map<String, Object> config = new LinkedHashMap<>(request.config());
        config.put("transport", request.transport());
        config.put("authType", request.authType());
        config.put("namespace", namespace);
        validator.config("mcp", config);
        endpointPolicy.validateNetworkTarget(endpointPolicy.normalize(request.endpointUrl()));
        if (request.step() >= 2 && request.authType().equalsIgnoreCase("none") == false
            && (request.credentialRef() == null || request.credentialRef().isBlank())) {
            return new McpWizardValidationView(request.step(), false, request.step(), namespace,
                List.of("第二步需要提供 env: 凭据引用"));
        }
        if (request.step() >= 2) {
            AgentConnector draft = new AgentConnector();
            draft.setId(0L);
            draft.setRevisionNo(1L);
            try {
                apply(
                    draft, request.name(), "mcp", request.endpointUrl(), request.credentialRef(),
                    config, "active"
                );
                McpConnectionTestView handshake = performConnectionTest(
                    draft, Instant.now(), LocalDateTime.now()
                );
                return new McpWizardValidationView(
                    request.step(), true, request.step() < 3 ? request.step() + 1 : null,
                    namespace,
                    List.of(
                        "MCP 握手成功：协议 " + handshake.protocolVersion()
                            + "，已发现 " + handshake.toolCount() + " 个工具"
                    )
                );
            } catch (RuntimeException exception) {
                return new McpWizardValidationView(
                    request.step(), false, request.step(), namespace,
                    List.of("MCP 握手失败：" + safeError(exception))
                );
            }
        }
        return new McpWizardValidationView(
            request.step(), true, request.step() < 3 ? request.step() + 1 : null, namespace, List.of()
        );
    }

    /**
     * 处理{@code publishMcpWizard}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ConnectorView publishMcpWizard(Long connectorId, McpWizardPublishRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        mapper.lockConnector(connectorId);
        AgentConnector connector = requireVisibleConnector(connectorId, principal);
        requireManage(principal, connector);
        if (!"mcp".equals(connector.getProviderType())) {
            throw badRequest("只有 MCP 连接器可以发布向导结果");
        }
        if (!"active".equals(connector.getStatus())) {
            throw conflict("MCP 服务已停用，不能发布工具");
        }
        String namespace = request.namespace().strip().toLowerCase(Locale.ROOT);
        Map<String, Object> config = config(connector);
        String currentNamespace = String.valueOf(config.getOrDefault("namespace", "default"))
            .strip().toLowerCase(Locale.ROOT);
        if (!currentNamespace.equals(namespace)) {
            throw conflict("MCP 命名空间已变化，请先保存并重新发现工具");
        }
        if (connector.getLastDiscoveryId() == null) {
            throw conflict("请先完成 MCP 握手和工具发现，再发布工具");
        }
        if (connector.getRevisionNo() == null || request.expectedRevision() != connector.getRevisionNo()) {
            throw conflict("连接器已被其他请求修改");
        }
        LocalDateTime now = LocalDateTime.now();
        int published = mapper.publishAvailableConnectorTools(connectorId, principal.id(), now);
        int audited = auditMapper.insertEvent(
            idGenerator.nextId(),
            principal.isHuman() ? "user" : "service_account",
            principal.id(),
            "mcp_wizard_publish",
            "connector",
            connectorId,
            null,
            "success",
            "mcp_wizard_publish",
            "published_tools=" + published,
            now
        );
        if (audited != 1) {
            throw new ServiceException("MCP 工具发布审计写入失败", 503);
        }
        return view(connector, principal);
    }

    /**
     * 处理{@code previewMcpServers}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public McpServersImportPreviewView previewMcpServers(McpServersImportPreviewRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(null, null, "create"));
        if (!principal.isHuman()) {
            throw forbidden("服务账号不能导入个人 MCP 配置");
        }
        return importParser.preview(request.document());
    }

    /**
     * 处理导入McpServer并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ConnectorView importMcpServer(McpServersImportRequest request) {
        McpImportEntryView entry = importParser.requireImportable(request.document(), request.sourceKey());
        String credentialRef = request.credentialRef();
        if (credentialRef == null || credentialRef.isBlank()) {
            credentialRef = entry.credentialRef();
        }
        if (!"none".equals(entry.authType()) && (credentialRef == null || credentialRef.isBlank())) {
            throw badRequest(
                "MCP 配置包含内联或未解析凭据；请先把 secret 配置为环境变量，并提交 env:NAME 凭据引用"
            );
        }
        return create(new CreateConnectorRequest(
            request.connectorKey(), request.name(), "mcp", request.scope(), entry.endpointUrl(),
            credentialRef, importParser.connectorConfig(entry), request.status()
        ));
    }

    /**
     * 处理{@code persistCheckFailure}相关逻辑。
     *
     * @param connector 连接器参数
     * @param checkedAt {@code checkedAt}参数
     * @param exception {@code exception}参数
     */
    private void persistCheckFailure(
        AgentConnector connector,
        LocalDateTime checkedAt,
        RuntimeException exception
    ) {
        if (!(exception instanceof ServiceException serviceException)
            || !Integer.valueOf(HttpStatus.CONFLICT).equals(serviceException.getCode())) {
            mapper.markConnectorCheckFailed(
                connector.getId(), connector.getRevisionNo(), safeError(exception), checkedAt
            );
        }
    }

    /**
     * 处理{@code performConnectionTest}并返回对应结果。
     *
     * @param connector 连接器参数
     * @param started {@code started}参数
     * @param checkedAt {@code checkedAt}参数
     * @return 处理结果
     */
    private McpConnectionTestView performConnectionTest(
        AgentConnector connector,
        Instant started,
        LocalDateTime checkedAt
    ) {
        McpRemoteClient.DiscoveryResult result = remoteClient.discover(connectionFactory.create(connector));
        if (result == null || result.tools() == null || result.protocolVersion() == null
            || result.protocolVersion().isBlank()) {
            throw new McpRemoteException("MCP 服务返回了无效握手结果");
        }
        if (result.tools().size() > ConnectorConfigurationValidator.MAX_TOOLS) {
            throw badRequest("MCP 工具数量超过 500 个限制");
        }
        String protocolVersion = requiredText(result.protocolVersion(), 32, "MCP 协议版本");
        Map<String, Object> info = result.serverInfo() == null ? Map.of() : result.serverInfo();
        List<McpConnectionToolPreviewView> tools = connectionToolPreviews(result.tools());
        return new McpConnectionTestView(
            true, protocolVersion, serverName(info, connector.getName()), tools.size(), tools,
            Duration.between(started, Instant.now()).toMillis(), checkedAt
        );
    }

    /**
     * 处理connection工具Previews并返回对应结果。
     *
     * @param discovered {@code discovered}参数
     * @return 符合条件的数据集合
     */
    private List<McpConnectionToolPreviewView> connectionToolPreviews(
        List<McpRemoteClient.DiscoveredTool> discovered
    ) {
        Set<String> names = new java.util.HashSet<>();
        List<McpConnectionToolPreviewView> tools = new ArrayList<>();
        for (McpRemoteClient.DiscoveredTool source : discovered) {
            String externalName = source.name() == null ? "" : source.name().strip();
            if (!REMOTE_TOOL_NAME.matcher(externalName).matches()) {
                throw badRequest("MCP 工具名称无效");
            }
            if (!names.add(externalName)) {
                throw badRequest("MCP 服务返回重复工具名称：" + externalName);
            }
            String name = optionalText(source.title(), 128);
            tools.add(new McpConnectionToolPreviewView(
                externalName,
                name == null ? externalName : name,
                optionalText(source.description(), 12000)
            ));
        }
        tools.sort(Comparator.comparing(McpConnectionToolPreviewView::externalName));
        return List.copyOf(tools);
    }

    /**
     * 处理{@code prepare}并返回对应结果。
     *
     * @param work {@code work}参数
     * @param result 结果参数
     * @return 处理结果
     */
    private PreparedDiscovery prepare(
        DiscoveryWork work,
        McpRemoteClient.DiscoveryResult result
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (result == null || result.tools() == null) {
            throw badRequest("MCP 发现结果无效");
        }
        if (result.tools().size() > ConnectorConfigurationValidator.MAX_TOOLS) {
            throw badRequest("MCP 工具数量超过 500 个限制");
        }
        Map<String, Object> serverInfo = validator.document(
            result.serverInfo(), SERVER_INFO_KEYS, "MCP 服务信息"
        );
        Map<String, Object> connectorConfig = config(work.connector());
        String namespace = String.valueOf(connectorConfig.getOrDefault("namespace", "default"));
        Set<String> names = new java.util.HashSet<>();
        List<PreparedRemoteTool> tools = new ArrayList<>();
        for (McpRemoteClient.DiscoveredTool source : result.tools()) {
            String externalName = source.name() == null ? "" : source.name().strip();
            if (!REMOTE_TOOL_NAME.matcher(externalName).matches()) {
                throw badRequest("MCP 工具名称无效");
            }
            if (!names.add(externalName)) {
                throw badRequest("MCP 服务返回重复工具名称：" + externalName);
            }
            String name = optionalText(source.title(), 128);
            if (name == null) {
                name = externalName.length() <= 128 ? externalName : externalName.substring(0, 128);
            }
            String description = optionalText(source.description(), 12000);
            Map<String, Object> input = validator.toolSchema(source.inputSchema(), "MCP 输入 Schema");
            Map<String, Object> output = validator.optionalSchema(source.outputSchema(), "MCP 输出 Schema");
            Map<String, Object> annotations = validator.document(
                source.annotations(), TOOL_ANNOTATION_KEYS, "MCP 工具注解"
            );
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("namespace", namespace);
            identity.put("externalName", externalName);
            identity.put("name", name);
            identity.put("description", description == null ? "" : description);
            identity.put("inputSchema", input);
            identity.put("outputSchema", output);
            identity.put("annotations", annotations);
            String identityJson = validator.boundedJson(identity, "MCP 工具定义");
            String schemaHash = ContentHashing.sha256(identityJson);
            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("connectorRevision", work.connector().getRevisionNo());
            policy.put("namespace", namespace);
            policy.put("outputSchema", output);
            policy.put("transport", connectorConfig.get("transport"));
            policy.putAll(annotations);
            tools.add(new PreparedRemoteTool(
                toolKey(work.connector().getId(), namespace, externalName), externalName, name,
                description, riskLevel(annotations), validator.boundedJson(input, "MCP 输入 Schema"),
                validator.boundedJson(policy, "MCP 执行策略"), schemaHash
            ));
        }
        tools.sort(Comparator.comparing(PreparedRemoteTool::externalName));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("protocolVersion", requiredText(result.protocolVersion(), 32, "MCP 协议版本"));
        snapshot.put("namespace", namespace);
        snapshot.put("serverInfo", serverInfo);
        snapshot.put("tools", tools.stream().map(tool -> Map.of(
            "externalName", tool.externalName(), "schemaHash", tool.schemaHash()
        )).toList());
        return new PreparedDiscovery(
            result.protocolVersion().strip(), validator.boundedJson(serverInfo, "MCP 服务信息"),
            ContentHashing.sha256(validator.boundedJson(snapshot, "MCP 发现快照")), List.copyOf(tools)
        );
    }

    /**
     * 处理{@code apply}相关逻辑。
     *
     * @param connector 连接器参数
     * @param name 名称
     * @param providerType 业务类型
     * @param endpointUrl {@code endpointUrl}参数
     * @param credentialRef 凭据Ref参数
     * @param config {@code config}参数
     * @param status 目标状态
     */
    private void apply(
        AgentConnector connector,
        String name,
        String providerType,
        String endpointUrl,
        String credentialRef,
        Map<String, Object> config,
        String status
    ) {
        String normalizedProvider = validator.providerType(providerType);
        Map<String, Object> normalizedConfig = validator.config(normalizedProvider, config);
        connector.setName(requiredText(name, 128, "连接器名称"));
        connector.setProviderType(normalizedProvider);
        connector.setEndpointUrl(endpointPolicy.normalize(endpointUrl).toString());
        connector.setCredentialRef(validator.credentialRef(credentialRef, normalizedConfig));
        connector.setConfigJson(validator.boundedJson(normalizedConfig, "连接器配置"));
        connector.setStatus(validator.status(status));
    }

    /**
     * 处理{@code config}并返回对应结果。
     *
     * @param connector 连接器参数
     * @return 处理结果
     */
    private Map<String, Object> config(AgentConnector connector) {
        try {
            Map<String, Object> value = jsonMapper.readValue(connector.getConfigJson(), MAP_TYPE);
            return value == null ? Map.of() : value;
        } catch (RuntimeException exception) {
            throw conflict("连接器配置快照无效");
        }
    }

    /**
     * 校验连接器，并在条件不满足时终止处理。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    private AgentConnector requireConnector(Long connectorId) {
        AgentConnector connector = mapper.selectConnectorById(connectorId);
        if (connector == null) {
            throw new ServiceException("连接器不存在", HttpStatus.NOT_FOUND);
        }
        return connector;
    }

    /**
     * 校验Visible连接器，并在条件不满足时终止处理。
     *
     * @param connectorId 资源标识
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private AgentConnector requireVisibleConnector(Long connectorId, CurrentPrincipal principal) {
        AgentConnector connector = requireConnector(connectorId);
        if (!isVisible(principal, connector)) {
            throw new ServiceException("连接器不存在", HttpStatus.NOT_FOUND);
        }
        return connector;
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param connector 连接器参数
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private ConnectorView view(AgentConnector connector, CurrentPrincipal principal) {
        return ConnectorView.from(connector, jsonMapper, principal.id(), canManage(principal, connector));
    }

    /**
     * 判断{@code Visible}是否满足要求。
     *
     * @param principal 当前操作主体
     * @param connector 连接器参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isVisible(CurrentPrincipal principal, AgentConnector connector) {
        return "global".equals(connector.getScopeType())
            || ("personal".equals(connector.getScopeType()) && principal.id().equals(connector.getOwnerId()));
    }

    /**
     * 判断{@code Manage}是否满足要求。
     *
     * @param principal 当前操作主体
     * @param connector 连接器参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean canManage(CurrentPrincipal principal, AgentConnector connector) {
        if ("personal".equals(connector.getScopeType())) {
            return principal.id().equals(connector.getOwnerId());
        }
        return "global".equals(connector.getScopeType())
            && principal.hasRole(PlatformRole.PLATFORM_ADMIN);
    }

    /**
     * 校验{@code Manage}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param connector 连接器参数
     */
    private void requireManage(CurrentPrincipal principal, AgentConnector connector) {
        if (!canManage(principal, connector)) {
            throw forbidden("只有平台管理员可以维护企业共享连接器");
        }
    }

    /**
     * 校验范围Creation，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param scope 范围参数
     */
    private void requireScopeCreation(CurrentPrincipal principal, String scope) {
        if ("global".equals(scope) && !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            throw forbidden("只有平台管理员可以创建企业共享连接器");
        }
        if ("personal".equals(scope) && !principal.isHuman()) {
            throw forbidden("服务账号不能创建个人连接器");
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
        return new PermissionContext(
            "connector", id, key, action, ResourceState.ACTIVE, true, Set.of(), null
        );
    }

    /**
     * 将输入数据转换为{@code olKey}。
     *
     * @param connectorId 资源标识
     * @param namespace 名称
     * @param externalName 名称
     * @return 处理结果
     */
    private String toolKey(Long connectorId, String namespace, String externalName) {
        return "mcp." + ContentHashing.sha256(
            connectorId + ":" + namespace + ":" + externalName
        ).substring(0, 32);
    }

    /**
     * 处理风险Level并返回对应结果。
     *
     * @param annotations {@code annotations}参数
     * @return 处理结果
     */
    private String riskLevel(Map<String, Object> annotations) {
        if (Boolean.TRUE.equals(annotations.get("destructive"))) {
            return "R3";
        }
        if (Boolean.TRUE.equals(annotations.get("readOnly"))) {
            return "R1";
        }
        return "R2";
    }

    /**
     * 处理{@code normalizeSearch}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeSearch(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requiredText(value, 128, "搜索条件");
    }

    /**
     * 处理{@code serverName}并返回对应结果。
     *
     * @param serverInfo {@code serverInfo}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private String serverName(Map<String, Object> serverInfo, String fallback) {
        for (String key : List.of("title", "name")) {
            Object value = serverInfo.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return requiredText(text, 128, "MCP 服务名称");
            }
        }
        return requiredText(fallback, 128, "连接器名称");
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
        if (normalized.length() > maxLength
            || normalized.chars().anyMatch(ch -> ch == 0 || ch == '\r')) {
            throw badRequest("文本内容无效或超过长度限制");
        }
        return normalized;
    }

    /**
     * 处理{@code safeError}并返回对应结果。
     *
     * @param throwable {@code throwable}参数
     * @return 处理结果
     */
    private String safeError(Throwable throwable) {
        String message = throwable instanceof McpRemoteException
            ? throwable.getMessage() : throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "MCP 服务连接或协议处理失败";
        }
        String safe = message
            .replaceAll("(?i)bearer\\s+[^\\s,;]+", "Bearer [REDACTED]")
            .replaceAll("(?i)(token|secret|password|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
        return safe.length() <= 1000 ? safe : safe.substring(0, 1000);
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

    /**
     * 处理{@code forbidden}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException forbidden(String message) {
        return new ServiceException(message, HttpStatus.FORBIDDEN);
    }
}
