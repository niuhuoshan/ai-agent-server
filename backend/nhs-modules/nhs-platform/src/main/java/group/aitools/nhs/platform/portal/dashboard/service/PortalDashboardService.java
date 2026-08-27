package group.aitools.nhs.platform.portal.dashboard.service;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.portal.dashboard.persistence.PortalDashboardMapper;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardAgentHealthRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardAgentPerformanceRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardApiCallRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardApiHourRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardApiSummaryRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardApiTrendRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardHourRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardOnlineUserRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardRecentErrorRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardRecentRunRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardRecentUserRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardSummaryRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardTokenAgentRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardTokenRecordRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardTokenTotalsRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardTokenTrendRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardTokenUserRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardToolUsageRow;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 负责门户Dashboard相关的业务编排与领域规则处理。
 *
 * Nhs Portal dashboard compatibility service backed by platform facts.
 *
 * <p>Nhs's HTTP access-log metrics are mapped to the platform's redacted
 * machine-API audit facts. Task runs, run steps, unified Token facts and NHS
 * online sessions supply the remaining operational views.</p>
 */
@Service
public class PortalDashboardService {

    private static final int MAX_DAYS = 90;
    private static final String TOKEN_COVERAGE = "agent_conversation_message|agent_task_run";
    private static final String API_SOURCE = "agent_api_call";
    private static final DateTimeFormatter HOUR_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CurrentPrincipalProvider principalProvider;
    private final PortalDashboardMapper dashboardMapper;
    private final PortalOnlineSessionProvider onlineSessionProvider;

    /**
     * 创建 {@code PortalDashboardService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param dashboardMapper {@code dashboardMapper}参数
     * @param onlineSessionProvider online会话提供方参数
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PortalDashboardService(
        CurrentPrincipalProvider principalProvider,
        PortalDashboardMapper dashboardMapper,
        PortalOnlineSessionProvider onlineSessionProvider
    ) {
        this.principalProvider = principalProvider;
        this.dashboardMapper = dashboardMapper;
        this.onlineSessionProvider = onlineSessionProvider;
    }

    /**
 * 创建 {@code PortalDashboardService} 实例并初始化所需依赖。
 * Lightweight constructor retained for focused unit tests and embedders. */
    public PortalDashboardService(
        CurrentPrincipalProvider principalProvider,
        PortalDashboardMapper dashboardMapper
    ) {
        this(principalProvider, dashboardMapper,
            () -> PortalOnlineSessionProvider.SessionSnapshot.available(List.of()));
    }

    /**
     * 处理{@code adminStats}并返回对应结果。
     *
     * @param period {@code period}参数
     * @return 处理结果
     */
    public Map<String, Object> adminStats(String period) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        Range range = periodRange(period);
        Long scopeUserId = isAdmin(principal) ? null : principal.id();
        DashboardSummaryRow summary = dashboardMapper.selectSummary(
            range.from(), range.to(), scopeUserId
        );
        DashboardTokenTotalsRow tokens = dashboardMapper.selectTokenTotals(
            range.from(), range.to(), scopeUserId
        );
        DashboardApiSummaryRow api = dashboardMapper.selectApiSummary(
            range.from(), range.to(), scopeUserId
        );

        Map<String, Object> result = scope(period, principal, scopeUserId);
        result.put("api_calls", apiSummary(period, api));
        result.put("avg_response_time", api == null ? 0D : round(api.getAverageDurationMs()));
        result.put("success_rate", apiRate(api, true));
        result.put("error_rate", apiRate(api, false));
        result.put("execution_runs", executionSummary(summary));
        result.put("token_usage", tokenTotals(tokens));
        result.put("prompt_tokens", number(tokens == null ? null : tokens.getPromptTokens()));
        result.put("completion_tokens", number(tokens == null ? null : tokens.getCompletionTokens()));
        result.put("total_tokens", number(tokens == null ? null : tokens.getTotalTokens()));
        if (isAdmin(principal)) {
            result.put("total_users", number(dashboardMapper.countActiveUsers()));
            result.put("active_users", number(dashboardMapper.countActiveUsersInRange(
                range.from(), range.to(), null
            )));
        } else {
            result.put("total_users", unavailable("sys_user", "普通用户不可查看企业用户总数"));
            result.put("active_users", unavailable("activity", "普通用户不可查看企业活跃用户总数"));
        }
        return result;
    }

    /**
     * 处理用户Stats并返回对应结果。
     *
     * @param period {@code period}参数
     * @return 处理结果
     */
    public Map<String, Object> userStats(String period) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        Range range = periodRange(period);
        DashboardSummaryRow summary = dashboardMapper.selectSummary(
            range.from(), range.to(), principal.id()
        );
        DashboardTokenTotalsRow tokens = dashboardMapper.selectTokenTotals(
            range.from(), range.to(), principal.id()
        );
        DashboardApiSummaryRow api = dashboardMapper.selectApiSummary(
            range.from(), range.to(), principal.id()
        );

        Map<String, Object> result = scope(period, principal, principal.id());
        result.put("api_key_status", dashboardMapper.selectApiKeyStatus(principal.id()));
        result.put("api_calls", apiSummary(period, api));
        result.put("avg_response_time", api == null ? 0D : round(api.getAverageDurationMs()));
        result.put("success_rate", apiRate(api, true));
        result.put("error_rate", apiRate(api, false));
        result.put("execution_runs", executionSummary(summary));
        result.put("token_usage", tokenTotals(tokens));
        result.put("prompt_tokens", number(tokens == null ? null : tokens.getPromptTokens()));
        result.put("completion_tokens", number(tokens == null ? null : tokens.getCompletionTokens()));
        result.put("total_tokens", number(tokens == null ? null : tokens.getTotalTokens()));
        result.put("total_users", unavailable("sys_user", "用户统计仅适用于管理员企业视角"));
        result.put("active_users", unavailable("activity", "用户统计仅适用于管理员企业视角"));
        result.put("last_call_time", api == null ? null : iso(api.getLastCallAt()));
        return result;
    }

    /**
     * 处理{@code recentActivities}并返回对应结果。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    public Map<String, Object> recentActivities(int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        Long scopeUserId = isAdmin(principal) ? null : principal.id();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", isAdmin(principal) ? "enterprise" : "self");
        result.put("recent_users", recentUsers(scopeUserId, boundedLimit));
        result.put("recent_runs", recentRuns(scopeUserId, boundedLimit));
        result.put("recent_calls", recentApiCalls(scopeUserId, boundedLimit));
        result.put("recent_errors", recentApiErrors(scopeUserId, Math.min(boundedLimit, 5)));
        result.put("recent_run_errors", recentErrors(scopeUserId, Math.min(boundedLimit, 5)));
        return result;
    }

    /**
     * 处理智能体Stats并返回对应结果。
     *
     * @param period {@code period}参数
     * @return 处理结果
     */
    public Map<String, Object> agentStats(String period) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        Range range = periodRange(period);
        Long scopeUserId = isAdmin(principal) ? null : principal.id();
        DashboardAgentHealthRow health = dashboardMapper.selectAgentHealth(
            range.from(), range.to(), scopeUserId
        );

        Map<String, Object> result = scope(period, principal, scopeUserId);
        result.put("health_stats", healthStats(health));
        result.put("tool_usage", toolUsage(range, scopeUserId));
        result.put("performance_trend", hourlyTrend(range, scopeUserId));
        result.put("recent_errors", recentErrors(scopeUserId, 5));
        result.put("agent_performance", agentPerformance(range, scopeUserId));
        result.put("api_metrics", apiSummary(period, dashboardMapper.selectApiSummary(
            range.from(), range.to(), scopeUserId
        )));
        return result;
    }

    /**
     * 将输入数据转换为{@code kenStatsTrends}。
     *
     * @param days {@code days}参数
     * @param startDate {@code startDate}参数
     * @param endDate {@code endDate}参数
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> tokenStatsTrends(
        int days,
        String startDate,
        String endDate
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        DateRange range = tokenRange(days, startDate, endDate);
        Long scopeUserId = isAdmin(principal) ? null : principal.id();
        List<DashboardTokenTrendRow> rows = dashboardMapper.selectTokenTrends(
            range.from(), range.to(), scopeUserId
        );
        Map<LocalDate, DashboardTokenTrendRow> byDate = new LinkedHashMap<>();
        for (DashboardTokenTrendRow row : rows) {
            if (row != null && row.getDayBucket() != null) {
                byDate.put(row.getDayBucket().toLocalDate(), row);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (LocalDate date = range.startDate(); !date.isAfter(range.endDate()); date = date.plusDays(1)) {
            DashboardTokenTrendRow row = byDate.get(date);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.toString());
            item.put("calls", number(row == null ? null : row.getCalls()));
            item.put("prompt_tokens", number(row == null ? null : row.getPromptTokens()));
            item.put("completion_tokens", number(row == null ? null : row.getCompletionTokens()));
            item.put("total_tokens", number(row == null ? null : row.getTotalTokens()));
            item.put("source", TOKEN_COVERAGE);
            result.add(item);
        }
        return result;
    }

    /**
     * 将输入数据转换为{@code kenStatsRecords}。
     *
     * @param days {@code days}参数
     * @param startDate {@code startDate}参数
     * @param endDate {@code endDate}参数
     * @param page {@code page}参数
     * @param size 数量上限
     * @return 处理结果
     */
    public Map<String, Object> tokenStatsRecords(
        int days,
        String startDate,
        String endDate,
        int page,
        int size
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        DateRange range = tokenRange(days, startDate, endDate);
        int boundedPage = Math.max(1, page);
        int boundedSize = Math.max(1, Math.min(size, 100));
        Long scopeUserId = isAdmin(principal) ? null : principal.id();
        List<DashboardTokenRecordRow> rows = dashboardMapper.selectTokenRecords(
            range.from(), range.to(), scopeUserId,
            (boundedPage - 1) * boundedSize, boundedSize
        );
        List<Map<String, Object>> items = new ArrayList<>();
        for (DashboardTokenRecordRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("created_at", iso(row.getCreatedAt()));
            item.put("user_id", row.getUserId());
            item.put("username", row.getUsername());
            item.put("real_name", row.getDisplayName() == null ? row.getUsername() : row.getDisplayName());
            item.put("agent_id", row.getAgentId());
            item.put("agent_name", row.getAgentName());
            item.put("model_id", row.getModelId());
            item.put("model_name", row.getModelName());
            item.put("prompt_tokens", number(row.getPromptTokens()));
            item.put("completion_tokens", number(row.getCompletionTokens()));
            item.put("total_tokens", number(row.getTotalTokens()));
            item.put("status", row.getStatus());
            item.put("source", row.getSource());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", number(dashboardMapper.countTokenRecords(
            range.from(), range.to(), scopeUserId
        )));
        result.put("page", boundedPage);
        result.put("size", boundedSize);
        result.put("coverage", TOKEN_COVERAGE);
        result.put("unavailable_sources", List.of());
        return result;
    }

    /**
     * 将输入数据转换为{@code kenStatsAgents}。
     *
     * @param period {@code period}参数
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> tokenStatsAgents(String period) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        Range range = periodRange(period);
        Long scopeUserId = isAdmin(principal) ? null : principal.id();
        List<Map<String, Object>> result = new ArrayList<>();
        for (DashboardTokenAgentRow row : dashboardMapper.selectTokenAgents(
            range.from(), range.to(), scopeUserId
        )) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agent_id", row.getAgentId());
            item.put("name", row.getAgentName());
            item.put("calls", number(row.getCalls()));
            item.put("prompt_tokens", number(row.getPromptTokens()));
            item.put("completion_tokens", number(row.getCompletionTokens()));
            item.put("total_tokens", number(row.getTotalTokens()));
            item.put("source", TOKEN_COVERAGE);
            result.add(item);
        }
        return result;
    }

    /**
     * 将输入数据转换为{@code kenStatsUsers}。
     *
     * @param period {@code period}参数
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> tokenStatsUsers(String period) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        Range range = periodRange(period);
        Long scopeUserId = isAdmin(principal) ? null : principal.id();
        List<Map<String, Object>> result = new ArrayList<>();
        long total = 0;
        List<DashboardTokenUserRow> rows = dashboardMapper.selectTokenUsers(
            range.from(), range.to(), scopeUserId
        );
        for (DashboardTokenUserRow row : rows) {
            total += number(row.getTotalTokens());
        }
        for (DashboardTokenUserRow row : rows) {
            long tokens = number(row.getTotalTokens());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("user_id", row.getUserId());
            item.put("username", row.getUsername());
            item.put("real_name", row.getDisplayName() == null ? row.getUsername() : row.getDisplayName());
            item.put("calls", number(row.getCalls()));
            item.put("prompt_tokens", number(row.getPromptTokens()));
            item.put("completion_tokens", number(row.getCompletionTokens()));
            item.put("total_tokens", tokens);
            item.put("ratio", total > 0 ? Math.round(tokens * 10000D / total) / 100D : 0D);
            item.put("source", TOKEN_COVERAGE);
            result.add(item);
        }
        return result;
    }

    /**
     * 处理接口Trends并返回对应结果。
     *
     * @param days {@code days}参数
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> apiTrends(int days) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        int boundedDays = Math.max(1, Math.min(days, MAX_DAYS));
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(boundedDays - 1L);
        Long scopeUserId = isAdmin(principal) ? null : principal.id();
        List<DashboardApiTrendRow> rows = dashboardMapper.selectApiTrends(
            startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay(), scopeUserId
        );
        Map<LocalDate, DashboardApiTrendRow> byDate = new LinkedHashMap<>();
        for (DashboardApiTrendRow row : rows) {
            if (row != null && row.getDayBucket() != null) {
                byDate.put(row.getDayBucket().toLocalDate(), row);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            DashboardApiTrendRow row = byDate.get(date);
            long total = row == null ? 0L : number(row.getTotalCalls());
            long succeeded = row == null ? 0L : number(row.getSucceededCalls());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.toString());
            item.put("total_calls", total);
            item.put("success_calls", succeeded);
            item.put("error_calls", row == null ? 0L : number(row.getErrorCalls()));
            item.put("success_rate", total == 0 ? 0D : round(succeeded * 100D / total));
            item.put("avg_response_time", row == null ? 0D : round(row.getAverageDurationMs()));
            item.put("source", API_SOURCE);
            result.add(item);
        }
        return result;
    }

    /**
     * 处理接口Trends24h并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> apiTrends24h() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        Long scopeUserId = isAdmin(principal) ? null : principal.id();
        LocalDateTime start = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minusHours(23);
        LocalDateTime end = start.plusHours(24);
        Map<LocalDateTime, DashboardApiHourRow> byHour = new LinkedHashMap<>();
        for (DashboardApiHourRow row : dashboardMapper.selectApiTrends24h(start, end, scopeUserId)) {
            if (row != null && row.getHourBucket() != null) {
                byHour.put(row.getHourBucket().truncatedTo(ChronoUnit.HOURS), row);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            LocalDateTime hour = start.plusHours(i);
            DashboardApiHourRow row = byHour.get(hour);
            long total = row == null ? 0L : number(row.getTotalCalls());
            long succeeded = row == null ? 0L : number(row.getSucceededCalls());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hour", String.format("%02d:00", hour.getHour()));
            item.put("timestamp", hour.format(HOUR_TIMESTAMP));
            item.put("total_calls", total);
            item.put("success_calls", succeeded);
            item.put("error_calls", row == null ? 0L : number(row.getErrorCalls()));
            item.put("success_rate", total == 0 ? 0D : round(succeeded * 100D / total));
            item.put("avg_response_time", row == null ? 0D : round(row.getAverageDurationMs()));
            item.put("source", API_SOURCE);
            result.add(item);
        }
        return result;
    }

    /**
     * 处理{@code onlineUsers}并返回对应结果。
     *
     * @return 处理结果
     */
    public Map<String, Object> onlineUsers() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        PortalOnlineSessionProvider.SessionSnapshot snapshot = onlineSessionProvider.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", snapshot.available() ? "available" : "unavailable");
        result.put("source", "nhs_online_session");
        result.put("as_of", LocalDateTime.now().toString());
        if (!snapshot.available()) {
            result.put("count", 0);
            result.put("user_count", 0);
            result.put("users", List.of());
            result.put("reason", snapshot.reason());
            return result;
        }

        List<PortalOnlineSessionProvider.OnlineSession> sessions = new ArrayList<>(snapshot.sessions());
        sessions.sort(Comparator.comparingLong(
            (PortalOnlineSessionProvider.OnlineSession session) ->
                session.loginTime() == null ? Long.MIN_VALUE : session.loginTime()
        ).reversed());
        Set<String> usernames = sessions.stream()
            .map(PortalOnlineSessionProvider.OnlineSession::userName)
            .filter(name -> name != null && !name.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        result.put("count", sessions.size());
        result.put("user_count", usernames.size());
        result.put("users", isAdmin(principal) ? onlineUserDetails(sessions, usernames) : List.of());
        return result;
    }

    /**
     * 处理online用户Details并返回对应结果。
     *
     * @param sessions {@code sessions}参数
     * @param usernames 名称
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> onlineUserDetails(
        List<PortalOnlineSessionProvider.OnlineSession> sessions,
        Set<String> usernames
    ) {
        Map<String, DashboardOnlineUserRow> labels = usernames.isEmpty()
            ? Map.of()
            : dashboardMapper.selectOnlineUserLabels(List.copyOf(usernames)).stream()
                .collect(Collectors.toMap(
                    DashboardOnlineUserRow::getUsername,
                    Function.identity(),
                    (first, ignored) -> first,
                    LinkedHashMap::new
                ));
        List<Map<String, Object>> result = new ArrayList<>();
        for (PortalOnlineSessionProvider.OnlineSession session : sessions) {
            DashboardOnlineUserRow label = labels.get(session.userName());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("user_id", label == null ? null : label.getUserId());
            item.put("user_name", session.userName());
            item.put("real_name", label == null || label.getDisplayName() == null
                ? session.userName() : label.getDisplayName());
            item.put("role", label == null ? null : label.getRoleKeys());
            item.put("department", session.deptName());
            item.put("client_key", session.clientKey());
            item.put("device_type", session.deviceType());
            item.put("login_time", epochIso(session.loginTime()));
            item.put("last_active", epochIso(session.loginTime()));
            result.add(item);
        }
        return result;
    }

    /**
     * 处理{@code recentUsers}并返回对应结果。
     *
     * @param scopeUserId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> recentUsers(Long scopeUserId, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DashboardRecentUserRow row : dashboardMapper.selectRecentUsers(scopeUserId, Math.min(limit, 5))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("user_id", row.getUserId());
            item.put("user_name", row.getUsername());
            item.put("real_name", row.getDisplayName() == null ? row.getUsername() : row.getDisplayName());
            item.put("last_active", iso(row.getLastActive()));
            item.put("source", "agent_task_run|agent_conversation_message");
            result.add(item);
        }
        return result;
    }

    /**
     * 处理recent接口Calls并返回对应结果。
     *
     * @param scopeUserId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> recentApiCalls(Long scopeUserId, int limit) {
        return dashboardMapper.selectRecentApiCalls(scopeUserId, limit).stream()
            .map(this::apiCall)
            .toList();
    }

    /**
     * 处理recent接口Errors并返回对应结果。
     *
     * @param scopeUserId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> recentApiErrors(Long scopeUserId, int limit) {
        return dashboardMapper.selectRecentApiErrors(scopeUserId, limit).stream()
            .map(this::apiCall)
            .toList();
    }

    /**
     * 处理接口Call并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    private Map<String, Object> apiCall(DashboardApiCallRow row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.getId());
        item.put("user_name", row.getUsername());
        item.put("endpoint", row.getEndpointKey());
        item.put("method", row.getHttpMethod());
        item.put("status_code", row.getStatusCode());
        item.put("process_time_ms", number(row.getDurationMs()));
        item.put("outcome", row.getOutcome());
        item.put("error_code", row.getErrorCode());
        item.put("error_message", row.getErrorCode());
        item.put("created_at", iso(row.getCreatedAt()));
        item.put("source", API_SOURCE);
        return item;
    }

    /**
     * 处理{@code recentRuns}并返回对应结果。
     *
     * @param scopeUserId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> recentRuns(Long scopeUserId, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DashboardRecentRunRow row : dashboardMapper.selectRecentRuns(scopeUserId, limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getRunId());
            item.put("run_id", row.getRunId());
            item.put("task_id", row.getTaskId());
            item.put("created_by", row.getCreatedBy());
            item.put("trace_id", row.getTraceId());
            item.put("status", row.getStatus());
            item.put("task_title", row.getTaskTitle());
            item.put("agent_name", row.getAgentName());
            item.put("created_at", iso(row.getCreatedAt()));
            item.put("started_at", iso(row.getStartedAt()));
            item.put("finished_at", iso(row.getFinishedAt()));
            item.put("source", "agent_task_run");
            result.add(item);
        }
        return result;
    }

    /**
     * 处理{@code recentErrors}并返回对应结果。
     *
     * @param scopeUserId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> recentErrors(Long scopeUserId, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DashboardRecentErrorRow row : dashboardMapper.selectRecentErrors(scopeUserId, limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("run_id", row.getRunId());
            item.put("task_id", row.getTaskId());
            item.put("trace_id", row.getTraceId());
            item.put("agent", row.getAgentName());
            item.put("step", row.getStepKey());
            item.put("message", row.getErrorSummary());
            item.put("time", iso(row.getCreatedAt()));
            item.put("source", "agent_run_step");
            result.add(item);
        }
        return result;
    }

    /**
     * 处理健康状态Stats并返回对应结果。
     *
     * @param health 健康状态参数
     * @return 处理结果
     */
    private Map<String, Object> healthStats(DashboardAgentHealthRow health) {
        long total = health == null ? 0 : number(health.getTotalSteps());
        long succeeded = health == null ? 0 : number(health.getSucceededSteps());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success_rate", total == 0 ? 0D : round(succeeded * 100D / total));
        result.put("total_steps", total);
        result.put("total_tool_calls", health == null ? 0 : number(health.getToolCalls()));
        result.put("avg_latency", health == null ? 0D : round(health.getAverageLatencyMs()));
        result.put("source", "agent_run_step");
        return result;
    }

    /**
     * 将输入数据转换为{@code olUsage}。
     *
     * @param range {@code range}参数
     * @param scopeUserId 资源标识
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> toolUsage(Range range, Long scopeUserId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DashboardToolUsageRow row : dashboardMapper.selectToolUsage(
            range.from(), range.to(), scopeUserId
        )) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool_id", row.getToolId());
            item.put("name", row.getToolName());
            item.put("value", number(row.getInvocationCount()));
            item.put("source", "agent_run_step");
            result.add(item);
        }
        return result;
    }

    /**
     * 处理{@code hourlyTrend}并返回对应结果。
     *
     * @param ignoredRange {@code ignoredRange}参数
     * @param scopeUserId 资源标识
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> hourlyTrend(Range ignoredRange, Long scopeUserId) {
        LocalDateTime start = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minusHours(23);
        LocalDateTime end = start.plusHours(24);
        Map<LocalDateTime, DashboardHourRow> rows = new LinkedHashMap<>();
        for (DashboardHourRow row : dashboardMapper.selectHourlyHealth(start, end, scopeUserId)) {
            if (row != null && row.getHourBucket() != null) {
                rows.put(row.getHourBucket().truncatedTo(ChronoUnit.HOURS), row);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            LocalDateTime hour = start.plusHours(i);
            DashboardHourRow row = rows.get(hour);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hour", String.format("%02d:00", hour.getHour()));
            item.put("timestamp", hour.toString());
            item.put("avg_ms", row == null ? 0D : round(row.getAverageLatencyMs()));
            item.put("steps", row == null ? 0L : number(row.getTotalSteps()));
            item.put("source", "agent_run_step");
            result.add(item);
        }
        return result;
    }

    /**
     * 处理智能体Performance并返回对应结果。
     *
     * @param range {@code range}参数
     * @param scopeUserId 资源标识
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> agentPerformance(Range range, Long scopeUserId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DashboardAgentPerformanceRow row : dashboardMapper.selectAgentPerformance(
            range.from(), range.to(), scopeUserId
        )) {
            long calls = number(row.getCalls());
            long succeeded = number(row.getSucceededCalls());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agent_id", row.getAgentId());
            item.put("name", row.getAgentName());
            item.put("version", row.getVersionNo());
            item.put("calls", calls);
            item.put("avg_latency", round(row.getAverageLatencyMs()));
            item.put("success_rate", calls == 0 ? 0D : round(succeeded * 100D / calls));
            item.put("source", "agent_task_run");
            result.add(item);
        }
        return result;
    }

    /**
     * 处理范围并返回对应结果。
     *
     * @param period {@code period}参数
     * @param principal 当前操作主体
     * @param scopeUserId 资源标识
     * @return 处理结果
     */
    private Map<String, Object> scope(String period, CurrentPrincipal principal, Long scopeUserId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("scope", scopeUserId == null ? "enterprise" : "self");
        result.put("principal_id", principal.id());
        result.put("principal_name", principal.username());
        result.put("as_of", LocalDateTime.now().toString());
        return result;
    }

    /**
     * 处理执行Summary并返回对应结果。
     *
     * @param summary {@code summary}参数
     * @return 处理结果
     */
    private Map<String, Object> executionSummary(DashboardSummaryRow summary) {
        long total = summary == null ? 0 : number(summary.getTotalRuns());
        long succeeded = summary == null ? 0 : number(summary.getSucceededRuns());
        long failed = summary == null ? 0 : number(summary.getFailedRuns());
        long cancelled = summary == null ? 0 : number(summary.getCancelledRuns());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("success", succeeded);
        result.put("errors", failed);
        result.put("cancelled", cancelled);
        result.put("success_rate", total == 0 ? 0D : round(succeeded * 100D / total));
        result.put("avg_latency_ms", summary == null ? 0D : round(summary.getAverageLatencyMs()));
        result.put("source", "agent_task_run");
        return result;
    }

    /**
     * 处理接口Summary并返回对应结果。
     *
     * @param period {@code period}参数
     * @param summary {@code summary}参数
     * @return 处理结果
     */
    private Map<String, Object> apiSummary(String period, DashboardApiSummaryRow summary) {
        long total = summary == null ? 0L : number(summary.getTotalCalls());
        long succeeded = summary == null ? 0L : number(summary.getSucceededCalls());
        long errors = summary == null ? 0L : number(summary.getErrorCalls());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("total", total);
        result.put("success", succeeded);
        result.put("errors", errors);
        result.put("pending", Math.max(0L, total - succeeded - errors));
        result.put("avg_response_time", summary == null ? 0D : round(summary.getAverageDurationMs()));
        result.put("success_rate", total == 0 ? 0D : round(succeeded * 100D / total));
        result.put("error_rate", total == 0 ? 0D : round(errors * 100D / total));
        result.put("source", API_SOURCE);
        return result;
    }

    /**
     * 处理接口Rate并返回对应结果。
     *
     * @param summary {@code summary}参数
     * @param success {@code success}参数
     * @return 处理结果
     */
    private double apiRate(DashboardApiSummaryRow summary, boolean success) {
        long total = summary == null ? 0L : number(summary.getTotalCalls());
        if (total == 0) {
            return 0D;
        }
        long value = success
            ? number(summary.getSucceededCalls())
            : number(summary.getErrorCalls());
        return round(value * 100D / total);
    }

    /**
     * 将输入数据转换为{@code kenTotals}。
     *
     * @param tokens {@code tokens}参数
     * @return 处理结果
     */
    private Map<String, Object> tokenTotals(DashboardTokenTotalsRow tokens) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("calls", number(tokens == null ? null : tokens.getMessageCount()));
        result.put("prompt_tokens", number(tokens == null ? null : tokens.getPromptTokens()));
        result.put("completion_tokens", number(tokens == null ? null : tokens.getCompletionTokens()));
        result.put("total_tokens", number(tokens == null ? null : tokens.getTotalTokens()));
        result.put("coverage", TOKEN_COVERAGE);
        result.put("unavailable_sources", List.of());
        return result;
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param source 数据源参数
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    private Map<String, Object> unavailable(String source, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "unavailable");
        result.put("source", source);
        result.put("reason", reason);
        return result;
    }

    /**
     * 处理{@code periodRange}并返回对应结果。
     *
     * @param period {@code period}参数
     * @return 处理结果
     */
    private Range periodRange(String period) {
        String normalized = period == null ? "today" : period.strip().toLowerCase();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = switch (normalized) {
            case "today" -> LocalDate.now().atStartOfDay();
            case "week" -> now.minusDays(7);
            case "month" -> now.minusDays(30);
            default -> throw new ServiceException("统计周期无效", HttpStatus.BAD_REQUEST);
        };
        return new Range(normalized, start, now.plusNanos(1));
    }

    /**
     * 将输入数据转换为{@code kenRange}。
     *
     * @param days {@code days}参数
     * @param startDate {@code startDate}参数
     * @param endDate {@code endDate}参数
     * @return 处理结果
     */
    private DateRange tokenRange(int days, String startDate, String endDate) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try {
            LocalDate start;
            LocalDate end;
            if (startDate != null && !startDate.isBlank() && endDate != null && !endDate.isBlank()) {
                start = LocalDate.parse(startDate);
                end = LocalDate.parse(endDate);
            } else {
                int boundedDays = Math.max(1, Math.min(days, MAX_DAYS));
                end = LocalDate.now();
                start = end.minusDays(boundedDays - 1L);
            }
            if (end.isBefore(start)) {
                throw new ServiceException("结束日期不能早于开始日期", HttpStatus.BAD_REQUEST);
            }
            long span = ChronoUnit.DAYS.between(start, end) + 1;
            if (span > MAX_DAYS) {
                throw new ServiceException("时间范围不能超过90天", HttpStatus.BAD_REQUEST);
            }
            return new DateRange(start, end, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        } catch (java.time.format.DateTimeParseException exception) {
            throw new ServiceException("日期格式必须为 YYYY-MM-DD", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 判断{@code Admin}是否满足要求。
     *
     * @param principal 当前操作主体
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isAdmin(CurrentPrincipal principal) {
        return principal.hasRole(PlatformRole.PLATFORM_ADMIN);
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static long number(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 处理{@code round}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static double round(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return 0D;
        }
        return Math.round(value * 100D) / 100D;
    }

    /**
     * 判断{@code o}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static String iso(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    /**
     * 处理{@code epochIso}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static String epochIso(Long value) {
        return value == null ? null : LocalDateTime.ofInstant(
            Instant.ofEpochMilli(value), ZoneId.systemDefault()
        ).toString();
    }

    /**
     * 封装{@code Range}相关的不可变数据。
     */
    private record Range(String period, LocalDateTime from, LocalDateTime to) {
    }

    /**
     * 封装{@code DateRange}相关的不可变数据。
     */
    private record DateRange(
        LocalDate startDate,
        LocalDate endDate,
        LocalDateTime from,
        LocalDateTime to
    ) {
    }
}
