package group.aitools.nhs.platform.report.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.platform.data.web.DataQueryResultView;
import group.aitools.nhs.platform.report.service.ReportApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供平台报表相关的 HTTP 接口，并负责请求校验与结果返回。
 * Saved report definitions, execution history and delivery subscriptions. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/reports")
public class PlatformReportController {

    private final ReportApplicationService service;

    public PlatformReportController(ReportApplicationService service) {
        this.service = service;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param status 目标状态
     * @param search {@code search}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<ReportView>> list(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) @Size(max = 255) String search,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return R.ok(service.list(status, search, limit));
    }

    /**
     * 获取{@code get}。
     *
     * @param reportId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{reportId}")
    public R<ReportView> get(@PathVariable @Positive Long reportId) {
        return R.ok(service.get(reportId));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<ReportView> create(@Valid @RequestBody CreateReportRequest request) {
        return R.ok(service.create(request));
    }

    /**
     * 更新{@code update}。
     *
     * @param reportId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{reportId}")
    public R<ReportView> update(
        @PathVariable @Positive Long reportId,
        @Valid @RequestBody UpdateReportRequest request
    ) {
        return R.ok(service.update(reportId, request));
    }

    /**
     * 处理{@code archive}并返回对应结果。
     *
     * @param reportId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/{reportId}")
    public R<Void> archive(@PathVariable @Positive Long reportId) {
        service.archive(reportId);
        return R.ok();
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param reportId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{reportId}/execute")
    public R<DataQueryResultView> execute(
        @PathVariable @Positive Long reportId,
        @Valid @RequestBody ExecuteReportRequest request
    ) {
        return R.ok(service.execute(reportId, request));
    }

    /**
     * 执行{@code s}相关的处理流程。
     *
     * @param reportId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{reportId}/runs")
    public R<List<ReportRunView>> runs(
        @PathVariable @Positive Long reportId,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return R.ok(service.runs(reportId, limit));
    }

    /**
     * 处理{@code subscriptions}并返回对应结果。
     *
     * @param reportId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{reportId}/subscriptions")
    public R<List<ReportSubscriptionView>> subscriptions(@PathVariable @Positive Long reportId) {
        return R.ok(service.subscriptions(reportId));
    }

    /**
     * 创建并保存{@code Subscription}。
     *
     * @param reportId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{reportId}/subscriptions")
    public R<ReportSubscriptionView> createSubscription(
        @PathVariable @Positive Long reportId,
        @Valid @RequestBody CreateReportSubscriptionRequest request
    ) {
        return R.ok(service.createSubscription(reportId, request));
    }

    /**
     * 更新{@code SubscriptionStatus}。
     *
     * @param reportId 资源标识
     * @param subscriptionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{reportId}/subscriptions/{subscriptionId}")
    public R<ReportSubscriptionView> updateSubscriptionStatus(
        @PathVariable @Positive Long reportId,
        @PathVariable @Positive Long subscriptionId,
        @Valid @RequestBody UpdateReportSubscriptionStatusRequest request
    ) {
        return R.ok(service.updateSubscriptionStatus(reportId, subscriptionId, request));
    }

    /**
     * 执行{@code Subscription}相关的处理流程。
     *
     * @param reportId 资源标识
     * @param subscriptionId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{reportId}/subscriptions/{subscriptionId}/run")
    public R<DataQueryResultView> executeSubscription(
        @PathVariable @Positive Long reportId,
        @PathVariable @Positive Long subscriptionId
    ) {
        return R.ok(service.executeSubscription(reportId, subscriptionId));
    }

    /**
     * 删除{@code Subscription}。
     *
     * @param reportId 资源标识
     * @param subscriptionId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/{reportId}/subscriptions/{subscriptionId}")
    public R<Void> deleteSubscription(
        @PathVariable @Positive Long reportId,
        @PathVariable @Positive Long subscriptionId
    ) {
        service.deleteSubscription(reportId, subscriptionId);
        return R.ok();
    }
}
