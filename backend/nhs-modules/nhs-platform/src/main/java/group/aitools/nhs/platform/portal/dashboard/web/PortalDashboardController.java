package group.aitools.nhs.platform.portal.dashboard.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import group.aitools.nhs.platform.portal.dashboard.service.PortalDashboardService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 提供门户Dashboard相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs Portal dashboard URL compatibility backed by platform facts. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/api/portal/dashboard", "/api/portal"})
public class PortalDashboardController {

    private final PortalDashboardService dashboardService;

    public PortalDashboardController(PortalDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * 处理{@code adminStats}并返回对应结果。
     *
     * @param period {@code period}参数
     * @return 处理结果
     */
    @GetMapping("/admin-stats")
    public R<Map<String, Object>> adminStats(
        @RequestParam(defaultValue = "today")
        @Pattern(regexp = "today|week|month") String period
    ) {
        return R.ok(dashboardService.adminStats(period));
    }

    /**
     * 处理用户Stats并返回对应结果。
     *
     * @param period {@code period}参数
     * @return 处理结果
     */
    @GetMapping("/user-stats")
    public R<Map<String, Object>> userStats(
        @RequestParam(defaultValue = "today")
        @Pattern(regexp = "today|week|month") String period
    ) {
        return R.ok(dashboardService.userStats(period));
    }

    /**
     * 处理{@code recentActivities}并返回对应结果。
     *
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/recent-activities")
    public R<Map<String, Object>> recentActivities(
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit
    ) {
        return R.ok(dashboardService.recentActivities(limit));
    }

    /**
     * 处理智能体Stats并返回对应结果。
     *
     * @param period {@code period}参数
     * @return 处理结果
     */
    @GetMapping("/agent-stats")
    public R<Map<String, Object>> agentStats(
        @RequestParam(defaultValue = "today")
        @Pattern(regexp = "today|week|month") String period
    ) {
        return R.ok(dashboardService.agentStats(period));
    }

    /**
     * 将输入数据转换为{@code kenStatsTrends}。
     *
     * @param days {@code days}参数
     * @param startDate {@code startDate}参数
     * @param endDate {@code endDate}参数
     * @return 处理结果
     */
    @GetMapping("/token-stats/trends")
    public R<List<Map<String, Object>>> tokenStatsTrends(
        @RequestParam(defaultValue = "7") @Min(1) @Max(90) int days,
        @RequestParam(name = "start_date", required = false) String startDate,
        @RequestParam(name = "end_date", required = false) String endDate
    ) {
        return R.ok(dashboardService.tokenStatsTrends(days, startDate, endDate));
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
    @GetMapping("/token-stats/records")
    public R<Map<String, Object>> tokenStatsRecords(
        @RequestParam(defaultValue = "7") @Min(1) @Max(90) int days,
        @RequestParam(name = "start_date", required = false) String startDate,
        @RequestParam(name = "end_date", required = false) String endDate,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return R.ok(dashboardService.tokenStatsRecords(days, startDate, endDate, page, size));
    }

    /**
     * 将输入数据转换为{@code kenStatsAgents}。
     *
     * @param period {@code period}参数
     * @return 处理结果
     */
    @GetMapping("/token-stats/agents")
    public R<List<Map<String, Object>>> tokenStatsAgents(
        @RequestParam(defaultValue = "today")
        @Pattern(regexp = "today|week|month") String period
    ) {
        return R.ok(dashboardService.tokenStatsAgents(period));
    }

    /**
     * 将输入数据转换为{@code kenStatsUsers}。
     *
     * @param period {@code period}参数
     * @return 处理结果
     */
    @GetMapping("/token-stats/users")
    public R<List<Map<String, Object>>> tokenStatsUsers(
        @RequestParam(defaultValue = "today")
        @Pattern(regexp = "today|week|month") String period
    ) {
        return R.ok(dashboardService.tokenStatsUsers(period));
    }

    /**
     * 处理接口Trends并返回对应结果。
     *
     * @param days {@code days}参数
     * @return 处理结果
     */
    @GetMapping("/api-trends")
    public R<List<Map<String, Object>>> apiTrends(
        @RequestParam(defaultValue = "7") @Min(1) @Max(90) int days
    ) {
        return R.ok(dashboardService.apiTrends(days));
    }

    /**
     * 处理接口Trends24h并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/api-trends-24h")
    public R<List<Map<String, Object>>> apiTrends24h() {
        return R.ok(dashboardService.apiTrends24h());
    }

    /**
     * 处理{@code onlineUsers}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/online-users")
    public R<Map<String, Object>> onlineUsers() {
        return R.ok(dashboardService.onlineUsers());
    }
}
