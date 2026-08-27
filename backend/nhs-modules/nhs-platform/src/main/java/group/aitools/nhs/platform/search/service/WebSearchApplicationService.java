package group.aitools.nhs.platform.search.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.service.ConnectorConfigurationValidator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.search.domain.SearchProviderState;
import group.aitools.nhs.platform.search.mapper.SearchProviderMapper;
import group.aitools.nhs.platform.search.service.SearchRuntimePersistenceService.InvocationAudit;
import group.aitools.nhs.platform.search.web.SearchProviderView;
import group.aitools.nhs.platform.search.web.WebSearchRequest;
import group.aitools.nhs.platform.search.web.WebSearchResultView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 负责{@code WebSearch}相关的业务编排与领域规则处理。
 * Governs provider selection, authorization, rate limiting, circuit state and audit. */
@Service
public class WebSearchApplicationService {

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final SearchProviderMapper mapper;
    private final ConnectorConfigurationValidator validator;
    private final JsonMapper jsonMapper;
    private final WebSearchClient client;
    private final SearchRuntimePersistenceService persistence;

    public WebSearchApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        SearchProviderMapper mapper,
        ConnectorConfigurationValidator validator,
        JsonMapper jsonMapper,
        WebSearchClient client,
        SearchRuntimePersistenceService persistence
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.mapper = mapper;
        this.validator = validator;
        this.jsonMapper = jsonMapper;
        this.client = client;
        this.persistence = persistence;
    }

    /**
     * 处理{@code providers}并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    public List<SearchProviderView> providers() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(null, null, "list", true));
        return mapper.selectVisibleActiveProviders(principal.id()).stream()
            .map(connector -> view(connector, principal))
            .toList();
    }

    /**
     * 处理{@code preview}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public WebSearchResultView preview(WebSearchRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentConnector connector = requireVisibleConnector(request.connectorId(), principal);
        authorizationEnforcer.requireAllowed(
            principal, context(connector.getId(), connector.getConnectorKey(), "operate", true)
        );
        if (!canManage(principal, connector)) {
            throw new ServiceException("只有连接器维护者可以执行联网搜索测试", HttpStatus.FORBIDDEN);
        }
        return execute(
            principal.id(), connector, normalizeQuery(request.query()), request.maxResults(),
            null, null
        );
    }

    /**
     * 执行{@code timeSearch}相关的处理流程。
     *
     * @param principal 当前操作主体
     * @param preferredEngine {@code preferredEngine}参数
     * @param query 查询参数
     * @param maxResults {@code maxResults}参数
     * @param runId 资源标识
     * @param traceId 资源标识
     * @return 处理结果
     */
    public Object runtimeSearch(
        CurrentPrincipal principal,
        String preferredEngine,
        String query,
        Integer maxResults,
        String runId,
        String traceId
    ) {
        String normalizedQuery = normalizeQuery(query);
        AgentConnector connector = selectRuntimeProvider(principal, preferredEngine);
        WebSearchResultView result = execute(
            principal.id(), connector, normalizedQuery, maxResults, runId, traceId
        );
        return java.util.Map.of(
            "provider", result.connectorName(),
            "engine", result.engine(),
            "query", result.query(),
            "result_count", result.resultCount(),
            "latency_ms", result.latencyMs(),
            "results", result.results()
        );
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param actorId 资源标识
     * @param connector 连接器参数
     * @param query 查询参数
     * @param requestedMaximum {@code requestedMaximum}参数
     * @param runId 资源标识
     * @param traceId 资源标识
     * @return 处理结果
     */
    private WebSearchResultView execute(
        Long actorId,
        AgentConnector connector,
        String query,
        Integer requestedMaximum,
        String runId,
        String traceId
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (!"active".equals(connector.getStatus())) {
            throw unavailable("search_unavailable", "搜索Provider未启用");
        }
        SearchProviderConfig config = SearchProviderConfig.from(connector, validator, jsonMapper);
        int maximum = requestedMaximum == null ? config.maxResults() : requestedMaximum;
        if (maximum < 1 || maximum > config.maxResults()) {
            throw new ServiceException(
                "maxResults必须在1到" + config.maxResults() + "之间", HttpStatus.BAD_REQUEST
            );
        }
        LocalDateTime now = LocalDateTime.now();
        InvocationAudit pendingAudit = new InvocationAudit(
            actorId, bounded(runId, 128), bounded(traceId, 128), ContentHashing.sha256(query), 0
        );
        requireCircuitAvailable(connector, now, pendingAudit);
        if (persistence.recentInvocations(connector.getId(), now.minusMinutes(1))
            >= config.rateLimitPerMinute()) {
            persistence.rejected(
                connector.getId(), "rate_limited", "search_rate_limited", now, pendingAudit
            );
            throw new ServiceException("search_rate_limited: 搜索Provider已达到每分钟限额", 429);
        }

        Instant started = Instant.now();
        try {
            List<WebSearchClient.SearchHit> hits = client.search(
                connector, config, query, maximum
            );
            int latency = latency(started);
            InvocationAudit successAudit = new InvocationAudit(
                actorId, bounded(runId, 128), bounded(traceId, 128),
                pendingAudit.querySha256(), hits.size()
            );
            persistence.success(
                connector.getId(), connector.getRevisionNo(), latency, LocalDateTime.now(),
                successAudit
            );
            return new WebSearchResultView(
                connector.getId(), connector.getName(), config.engine(), query,
                hits.size(), latency, hits
            );
        } catch (RuntimeException exception) {
            int latency = latency(started);
            String errorCode = exception instanceof SearchProviderException provider
                ? provider.errorCode() : "search_provider_failed";
            String error = safeError(exception);
            persistence.failure(
                connector.getId(), connector.getRevisionNo(), config.failureThreshold(),
                config.cooldownSeconds(), latency, errorCode, error, LocalDateTime.now(),
                pendingAudit
            );
            if (exception instanceof ServiceException) {
                throw exception;
            }
            throw unavailable(errorCode, error);
        }
    }

    /**
     * 校验{@code CircuitAvailable}，并在条件不满足时终止处理。
     *
     * @param connector 连接器参数
     * @param now {@code now}参数
     * @param audit 审计参数
     */
    private void requireCircuitAvailable(
        AgentConnector connector,
        LocalDateTime now,
        InvocationAudit audit
    ) {
        SearchProviderState state = persistence.state(connector.getId());
        if (state == null || "closed".equals(state.getCircuitState())) {
            return;
        }
        if ("open".equals(state.getCircuitState())
            && (state.getNextProbeAt() == null || !state.getNextProbeAt().isAfter(now))
            && persistence.acquireHalfOpenProbe(connector.getId(), now)) {
            return;
        }
        persistence.rejected(
            connector.getId(), "circuit_open", "search_circuit_open", now, audit
        );
        throw unavailable("search_circuit_open", "搜索Provider熔断中，请稍后重试");
    }

    /**
     * 获取运行时提供方。
     *
     * @param principal 当前操作主体
     * @param preferredEngine {@code preferredEngine}参数
     * @return 处理结果
     */
    private AgentConnector selectRuntimeProvider(CurrentPrincipal principal, String preferredEngine) {
        List<AgentConnector> candidates = mapper.selectVisibleActiveProviders(principal.id());
        if (candidates.isEmpty()) {
            throw unavailable("search_unavailable", "未配置可用的联网搜索Provider");
        }
        String preferred = normalizePreferredEngine(preferredEngine);
        return candidates.stream()
            .sorted(Comparator.comparingInt(connector -> preference(connector, preferred)))
            .findFirst()
            .orElseThrow(() -> unavailable("search_unavailable", "未配置可用的联网搜索Provider"));
    }

    /**
     * 处理{@code preference}并返回对应结果。
     *
     * @param connector 连接器参数
     * @param preferred {@code preferred}参数
     * @return 处理结果
     */
    private int preference(AgentConnector connector, String preferred) {
        SearchProviderConfig config = SearchProviderConfig.from(connector, validator, jsonMapper);
        return preferred != null && preferred.equals(config.engine()) ? 0 : 1;
    }

    /**
     * 处理{@code normalizePreferredEngine}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizePreferredEngine(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("bing")) {
            return "bing";
        }
        if (normalized.contains("brave")) {
            return "brave";
        }
        return null;
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param connector 连接器参数
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private SearchProviderView view(AgentConnector connector, CurrentPrincipal principal) {
        SearchProviderConfig config = SearchProviderConfig.from(connector, validator, jsonMapper);
        SearchProviderState state = persistence.state(connector.getId());
        return new SearchProviderView(
            connector.getId(), connector.getConnectorKey(), connector.getName(),
            connector.getScopeType(), canManage(principal, connector), config.engine(),
            connector.getEndpointUrl(), connector.getStatus(),
            state == null ? "closed" : state.getCircuitState(),
            state == null ? 0 : state.getConsecutiveFailures(),
            state == null ? 0 : state.getTotalRequests(),
            state == null ? 0 : state.getTotalFailures(),
            state == null ? null : state.getLastLatencyMs(), connector.getLastCheckAt(),
            state == null ? null : state.getLastSuccessAt(),
            state == null ? null : state.getLastFailureAt(),
            state == null ? null : state.getNextProbeAt(),
            state == null ? connector.getLastError() : state.getLastError(),
            config.maxResults(), config.rateLimitPerMinute(), config.failureThreshold(),
            config.cooldownSeconds()
        );
    }

    /**
     * 校验Visible连接器，并在条件不满足时终止处理。
     *
     * @param connectorId 资源标识
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private AgentConnector requireVisibleConnector(Long connectorId, CurrentPrincipal principal) {
        if (connectorId == null) {
            throw new ServiceException("connectorId不能为空", HttpStatus.BAD_REQUEST);
        }
        AgentConnector connector = mapper.selectConnector(connectorId);
        if (connector == null || !"search".equals(connector.getProviderType())
            || !("global".equals(connector.getScopeType())
            || ("personal".equals(connector.getScopeType())
            && principal.id().equals(connector.getOwnerId())))) {
            throw new ServiceException("搜索Provider不存在", HttpStatus.NOT_FOUND);
        }
        return connector;
    }

    /**
     * 判断{@code Manage}是否满足要求。
     *
     * @param principal 当前操作主体
     * @param connector 连接器参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean canManage(CurrentPrincipal principal, AgentConnector connector) {
        return "personal".equals(connector.getScopeType())
            ? principal.id().equals(connector.getOwnerId())
            : principal.hasRole(PlatformRole.PLATFORM_ADMIN);
    }

    /**
     * 处理上下文并返回对应结果。
     *
     * @param id 资源标识
     * @param key {@code key}参数
     * @param action {@code action}参数
     * @param ui {@code ui}参数
     * @return 处理结果
     */
    private PermissionContext context(Long id, String key, String action, boolean ui) {
        return new PermissionContext(
            "connector", id, key, action, ResourceState.ACTIVE, ui, Set.of(), null
        );
    }

    /**
     * 处理normalize查询并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeQuery(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > 2000 || normalized.indexOf('\0') >= 0) {
            throw new ServiceException("搜索关键词为空、过长或包含非法字符", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code latency}并返回对应结果。
     *
     * @param started {@code started}参数
     * @return 处理结果
     */
    private int latency(Instant started) {
        long value = Duration.between(started, Instant.now()).toMillis();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    /**
     * 处理{@code safeError}并返回对应结果。
     *
     * @param throwable {@code throwable}参数
     * @return 处理结果
     */
    private String safeError(Throwable throwable) {
        String value = throwable.getMessage();
        if (value == null || value.isBlank()) {
            value = throwable.getClass().getSimpleName();
        }
        String normalized = value.replaceAll("(?i)(bearer|api[-_ ]?key|token)\\s+[^\\s,;]+", "$1 [redacted]")
            .replace('\0', ' ').strip();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace('\0', ' ').strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param code {@code code}参数
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException unavailable(String code, String message) {
        return new ServiceException(code + ": " + message, 503);
    }
}
