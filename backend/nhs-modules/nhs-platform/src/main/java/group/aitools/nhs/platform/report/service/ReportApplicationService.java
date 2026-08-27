package group.aitools.nhs.platform.report.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.service.DataQueryExecutionService;
import group.aitools.nhs.platform.data.web.DataQueryRequest;
import group.aitools.nhs.platform.data.web.DataQueryResultView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.nhs.portal.chatbi.PortalChatBIFederatedService;
import group.aitools.nhs.platform.report.domain.AgentReport;
import group.aitools.nhs.platform.report.domain.AgentReportRun;
import group.aitools.nhs.platform.report.domain.AgentReportSubscription;
import group.aitools.nhs.platform.report.mapper.AgentReportMapper;
import group.aitools.nhs.platform.report.web.CreateReportRequest;
import group.aitools.nhs.platform.report.web.CreateReportSubscriptionRequest;
import group.aitools.nhs.platform.report.web.ExecuteReportRequest;
import group.aitools.nhs.platform.report.web.ReportRunView;
import group.aitools.nhs.platform.report.web.ReportSubscriptionView;
import group.aitools.nhs.platform.report.web.ReportView;
import group.aitools.nhs.platform.report.web.UpdateReportRequest;
import group.aitools.nhs.platform.report.web.UpdateReportSubscriptionStatusRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责报表相关的业务编排与领域规则处理。
 * Saved report CRUD, safe parameter rendering and run/subscription history. */
@Service
public class ReportApplicationService {

    private static final Set<String> VISIBILITIES = Set.of("private", "enterprise_shared", "restricted");
    private static final Set<String> STATUSES = Set.of("draft", "active", "disabled", "archived");
    private static final Pattern PARAMETER_TOKEN = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)}}" );
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformIdGenerator idGenerator;
    private final AgentReportMapper mapper;
    private final DataQueryExecutionService queryService;
    private final JsonMapper jsonMapper;
    private final ReportScheduleCalculator scheduleCalculator;
    private final ReportExecutionPrincipalResolver executionPrincipalResolver;
    private final PortalChatBIFederatedService federatedService;

    /**
 * 创建 {@code ReportApplicationService} 实例并初始化所需依赖。
 * Compatibility constructor retained for focused unit tests and older callers. */
    public ReportApplicationService(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        AgentReportMapper mapper,
        DataQueryExecutionService queryService,
        JsonMapper jsonMapper,
        ReportScheduleCalculator scheduleCalculator,
        ReportExecutionPrincipalResolver executionPrincipalResolver
    ) {
        this(
            principalProvider, idGenerator, mapper, queryService, jsonMapper,
            scheduleCalculator, executionPrincipalResolver, null
        );
    }

    /**
     * 创建 {@code ReportApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param idGenerator {@code idGenerator}参数
     * @param mapper {@code mapper}参数
     * @param queryService 查询Service参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param scheduleCalculator 调度Calculator参数
     * @param executionPrincipalResolver 执行操作主体Resolver参数
     * @param federatedService {@code federatedService}参数
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ReportApplicationService(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        AgentReportMapper mapper,
        DataQueryExecutionService queryService,
        JsonMapper jsonMapper,
        ReportScheduleCalculator scheduleCalculator,
        ReportExecutionPrincipalResolver executionPrincipalResolver,
        PortalChatBIFederatedService federatedService
    ) {
        this.principalProvider = principalProvider;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.queryService = queryService;
        this.jsonMapper = jsonMapper;
        this.scheduleCalculator = scheduleCalculator;
        this.executionPrincipalResolver = executionPrincipalResolver;
        this.federatedService = federatedService;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param status 目标状态
     * @param search {@code search}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ReportView> list(String status, String search, int limit) {
        CurrentPrincipal actor = human();
        String normalizedStatus = status == null || status.isBlank() ? null : enumValue(status, STATUSES, "报表状态");
        String normalizedSearch = search == null || search.isBlank() ? null : text(search, 255, "搜索词");
        return mapper.selectVisible(
            actor.id(), actor.hasRole(PlatformRole.PLATFORM_ADMIN), normalizedStatus, normalizedSearch,
            Math.min(limit, 500)
        ).stream().map(ReportView::from).toList();
    }

    /**
     * 获取{@code get}。
     *
     * @param reportId 资源标识
     * @return 处理结果
     */
    public ReportView get(Long reportId) {
        AgentReport report = requireReadable(reportId);
        return ReportView.from(report);
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ReportView create(CreateReportRequest request) {
        CurrentPrincipal actor = human();
        String visibility = enumValue(request.visibility(), VISIBILITIES, "报表可见性");
        String schema = normalizeJsonObject(request.paramsSchemaJson(), "参数 Schema");
        validateSqlTemplate(request.sqlTemplate());
        AgentReport report = new AgentReport();
        LocalDateTime now = LocalDateTime.now();
        report.setId(idGenerator.nextId());
        report.setReportKey(request.reportKey().strip());
        report.setName(text(request.name(), 255, "报表名称"));
        report.setDatasetId(request.datasetId());
        report.setSqlTemplate(request.sqlTemplate().strip());
        report.setParamsSchemaJson(schema);
        report.setVisibility(visibility);
        report.setOwnerId(actor.id());
        report.setStatus("draft");
        report.setCreateBy(actor.id());
        report.setCreateTime(now);
        report.setDelFlag("0");
        report.setExtraJson("{}");
        try {
            mapper.insert(report);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("报表标识已存在", HttpStatus.CONFLICT);
        }
        return ReportView.from(report);
    }

    /**
     * 更新{@code update}。
     *
     * @param reportId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ReportView update(Long reportId, UpdateReportRequest request) {
        CurrentPrincipal actor = human();
        AgentReport report = requireManageable(reportId, actor);
        String status = enumValue(request.status(), STATUSES, "报表状态");
        String visibility = enumValue(request.visibility(), VISIBILITIES, "报表可见性");
        String schema = normalizeJsonObject(request.paramsSchemaJson(), "参数 Schema");
        validateSqlTemplate(request.sqlTemplate());
        report.setName(text(request.name(), 255, "报表名称"));
        report.setDatasetId(request.datasetId());
        report.setSqlTemplate(request.sqlTemplate().strip());
        report.setParamsSchemaJson(schema);
        report.setVisibility(visibility);
        report.setStatus(status);
        report.setUpdateBy(actor.id());
        report.setUpdateTime(LocalDateTime.now());
        if (mapper.update(report) != 1) {
            throw conflict("报表已被删除或状态已变化");
        }
        return ReportView.from(mapper.selectById(reportId));
    }

    /**
     * 处理{@code archive}相关逻辑。
     *
     * @param reportId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void archive(Long reportId) {
        CurrentPrincipal actor = human();
        AgentReport report = requireManageable(reportId, actor);
        if (mapper.archive(reportId, actor.id(), LocalDateTime.now()) != 1) {
            throw conflict("报表已被删除或状态已变化");
        }
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param reportId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    public DataQueryResultView execute(Long reportId, ExecuteReportRequest request) {
        human();
        AgentReport report = requireReadable(reportId);
        return executeReport(report, request.parameters(), "manual").result();
    }

    /**
     * 执行报表相关的处理流程。
     *
     * @param report 报表参数
     * @param parameters {@code parameters}参数
     * @param triggerType 业务类型
     * @return 处理结果
     */
    private ScheduledReportExecution executeReport(
        AgentReport report,
        Map<String, Object> parameters,
        String triggerType
    ) {
        return executeReport(report, parameters, triggerType, null);
    }

    /**
     * 执行报表相关的处理流程。
     *
     * @param report 报表参数
     * @param parameters {@code parameters}参数
     * @param triggerType 业务类型
     * @param backgroundPrincipal 当前操作主体
     * @return 处理结果
     */
    private ScheduledReportExecution executeReport(
        AgentReport report,
        Map<String, Object> parameters,
        String triggerType,
        CurrentPrincipal backgroundPrincipal
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (!"active".equals(report.getStatus())) {
            throw conflict("只有启用状态的报表可以执行");
        }
        Map<String, Object> extra = readJsonObject(report.getExtraJson(), "报表扩展");
        boolean federated = "federated".equals(extra.get("executionMode"));
        String sql = federated
            ? textValue(extra.get("joinSql"), 65_536, "联邦关联 SQL")
            : renderSql(report.getSqlTemplate(), parameters);
        LocalDateTime now = LocalDateTime.now();
        AgentReportRun run = new AgentReportRun();
        run.setId(idGenerator.nextId());
        run.setReportId(report.getId());
        run.setTriggerType(triggerType);
        run.setResolvedParamsJson(writeJson(parameters));
        run.setExecutedSql(sql);
        run.setStatus("running");
        run.setStartedAt(now);
        run.setCreatedAt(now);
        mapper.insertRun(run);
        try {
            DataQueryResultView result;
            if (federated) {
                if (federatedService == null) {
                    throw new ServiceException("联邦报表执行能力未启用", 503);
                }
                CurrentPrincipal executionPrincipal = backgroundPrincipal == null
                    ? human() : backgroundPrincipal;
                result = federatedService.executeScheduled(
                    executionPrincipal,
                    new PortalChatBIFederatedService.ScheduledRequest(
                        report.getDatasetId(),
                        longList(extra.get("datasetIds"), "联邦数据集"),
                        longValue(extra.get("sourceConversationId"), "联邦会话"),
                        textValue(extra.get("question"), 4000, "联邦问题"),
                        textValue(extra.get("planJson"), 128_000, "联邦计划"),
                        sql
                    )
                );
            } else {
                DataQueryRequest queryRequest = new DataQueryRequest(
                    report.getDatasetId(), null, null, null, report.getName(), sql
                );
                result = backgroundPrincipal == null
                    ? queryService.execute(queryRequest)
                    : queryService.executeBackground(backgroundPrincipal, queryRequest);
            }
            if (result.resultHash() == null || !result.resultHash().matches("[0-9a-f]{64}")) {
                throw new ServiceException("报表执行缺少不可变结果哈希，已拒绝标记为成功", 502);
            }
            run.setRunId(result.queryId());
            run.setStatus("succeeded");
            run.setResultHash(result.resultHash());
            run.setRowCount(result.rowCount());
            run.setFinishedAt(LocalDateTime.now());
            mapper.finishRun(run);
            return new ScheduledReportExecution(run.getId(), result);
        } catch (RuntimeException exception) {
            run.setStatus("failed");
            run.setErrorSummary(trimError(exception));
            run.setFinishedAt(LocalDateTime.now());
            mapper.finishRun(run);
            throw exception;
        }
    }

    /**
     * 执行{@code s}相关的处理流程。
     *
     * @param reportId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ReportRunView> runs(Long reportId, int limit) {
        requireReadable(reportId);
        return mapper.selectRuns(reportId, Math.min(limit, 500)).stream().map(ReportRunView::from).toList();
    }

    /**
     * 创建并保存{@code Subscription}。
     *
     * @param reportId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ReportSubscriptionView createSubscription(Long reportId, CreateReportSubscriptionRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal actor = human();
        requireManageable(reportId, actor);
        AgentReportSubscription subscription = new AgentReportSubscription();
        LocalDateTime now = utcNow();
        String scheduleType = enumValue(
            request.scheduleType(), Set.of("cron", "interval"), "报表订阅调度类型"
        );
        String timezone = request.timezone() == null || request.timezone().isBlank()
            ? "Asia/Shanghai" : text(request.timezone(), 64, "时区");
        scheduleCalculator.zone(timezone);
        subscription.setId(idGenerator.nextId());
        subscription.setReportId(reportId);
        subscription.setScheduleType(scheduleType);
        if ("cron".equals(scheduleType)) {
            if (request.intervalMinutes() != null) {
                throw badRequest("Cron订阅不能设置固定周期");
            }
            subscription.setCronExpr(scheduleCalculator.normalizeCron(request.cronExpr()));
        } else {
            if (request.cronExpr() != null && !request.cronExpr().isBlank()) {
                throw badRequest("周期订阅不能设置Cron表达式");
            }
            if (request.intervalMinutes() == null
                || request.intervalMinutes() < 1 || request.intervalMinutes() > 525600) {
                throw badRequest("报表订阅周期必须在1到525600分钟之间");
            }
            subscription.setIntervalMinutes(request.intervalMinutes());
        }
        subscription.setTimezone(timezone);
        subscription.setParamsJson(normalizeJsonObject(request.paramsJson(), "订阅参数"));
        subscription.setNotifyPolicyJson(normalizeJsonObject(request.notifyPolicyJson(), "通知策略"));
        subscription.setStatus("active");
        subscription.setMaxAttempts(request.maxAttempts() == null ? 3 : request.maxAttempts());
        if (subscription.getMaxAttempts() < 1 || subscription.getMaxAttempts() > 10) {
            throw badRequest("报表订阅最大尝试次数必须在1到10之间");
        }
        subscription.setRevisionNo(1L);
        subscription.setNextRunAt(scheduleCalculator.next(subscription, now));
        subscription.setCreateBy(actor.id());
        subscription.setCreateTime(now);
        subscription.setDelFlag("0");
        mapper.insertSubscription(subscription);
        return ReportSubscriptionView.from(subscription);
    }

    /**
     * 处理{@code subscriptions}并返回对应结果。
     *
     * @param reportId 资源标识
     * @return 符合条件的数据集合
     */
    public List<ReportSubscriptionView> subscriptions(Long reportId) {
        requireReadable(reportId);
        return mapper.selectSubscriptions(reportId).stream().map(ReportSubscriptionView::from).toList();
    }

    /**
     * 处理{@code visibleSubscriptions}并返回对应结果。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ReportSubscriptionView> visibleSubscriptions(int limit) {
        CurrentPrincipal actor = human();
        return mapper.selectVisibleSubscriptions(
            actor.id(), actor.hasRole(PlatformRole.PLATFORM_ADMIN), Math.min(Math.max(limit, 1), 500)
        ).stream().map(ReportSubscriptionView::from).toList();
    }

    /**
     * 执行{@code Subscription}相关的处理流程。
     *
     * @param subscriptionId 资源标识
     * @return 处理结果
     */
    public DataQueryResultView executeSubscription(Long subscriptionId) {
        return executeSubscription(null, subscriptionId);
    }

    /**
     * 执行{@code Subscription}相关的处理流程。
     *
     * @param reportId 资源标识
     * @param subscriptionId 资源标识
     * @return 处理结果
     */
    public DataQueryResultView executeSubscription(Long reportId, Long subscriptionId) {
        CurrentPrincipal actor = human();
        AgentReportSubscription subscription = requireManageableSubscription(reportId, subscriptionId, actor);
        AgentReport report = requireManageable(subscription.getReportId(), actor);
        try {
            return executeReport(
                report, readJsonObject(subscription.getParamsJson(), "订阅参数"), "subscription"
            ).result();
        } finally {
            mapper.recordSubscriptionRun(
                subscriptionId, subscription.getReportId(), actor.id(), utcNow()
            );
        }
    }

    /**
     * 执行{@code ScheduledSubscription}相关的处理流程。
     *
     * @param subscriptionId 资源标识
     * @return 处理结果
     */
    public ScheduledReportExecution executeScheduledSubscription(Long subscriptionId) {
        AgentReportSubscription subscription = mapper.selectSubscription(subscriptionId);
        if (subscription == null || !"active".equals(subscription.getStatus())) {
            throw new ReportDeliveryCancelledException("报表订阅已暂停或删除");
        }
        AgentReport report = mapper.selectById(subscription.getReportId());
        if (report == null || !"active".equals(report.getStatus())) {
            throw new ReportDeliveryCancelledException("报表已停用或删除");
        }
        try {
            CurrentPrincipal executionPrincipal = executionPrincipalResolver.resolve(subscription.getCreateBy());
            return executeReport(
                report, readJsonObject(subscription.getParamsJson(), "订阅参数"), "scheduled",
                executionPrincipal
            );
        } finally {
            mapper.recordSubscriptionRun(
                subscriptionId, subscription.getReportId(), subscription.getCreateBy(), utcNow()
            );
        }
    }

    /**
     * 更新{@code SubscriptionStatus}。
     *
     * @param subscriptionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ReportSubscriptionView updateSubscriptionStatus(
        Long subscriptionId, UpdateReportSubscriptionStatusRequest request
    ) {
        return updateSubscriptionStatus(null, subscriptionId, request);
    }

    /**
     * 更新{@code SubscriptionStatus}。
     *
     * @param reportId 资源标识
     * @param subscriptionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ReportSubscriptionView updateSubscriptionStatus(
        Long reportId, Long subscriptionId, UpdateReportSubscriptionStatusRequest request
    ) {
        CurrentPrincipal actor = human();
        AgentReportSubscription subscription = requireManageableSubscription(reportId, subscriptionId, actor);
        LocalDateTime now = utcNow();
        LocalDateTime nextRunAt = "active".equals(request.status())
            ? scheduleCalculator.next(subscription, now) : null;
        if (mapper.updateSubscriptionStatus(
            subscriptionId, subscription.getReportId(), request.status(), nextRunAt, actor.id(), now
        ) != 1) {
            throw conflict("报表订阅状态已变化");
        }
        if ("paused".equals(request.status())) {
            mapper.cancelPendingDeliveries(subscriptionId, subscription.getReportId(), now);
        }
        subscription.setStatus(request.status());
        subscription.setNextRunAt(nextRunAt);
        subscription.setRevisionNo(
            subscription.getRevisionNo() == null ? 1L : subscription.getRevisionNo() + 1
        );
        return ReportSubscriptionView.from(subscription);
    }

    /**
     * 删除{@code Subscription}。
     *
     * @param subscriptionId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSubscription(Long subscriptionId) {
        deleteSubscription(null, subscriptionId);
    }

    /**
     * 删除{@code Subscription}。
     *
     * @param reportId 资源标识
     * @param subscriptionId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSubscription(Long reportId, Long subscriptionId) {
        CurrentPrincipal actor = human();
        AgentReportSubscription subscription = requireManageableSubscription(reportId, subscriptionId, actor);
        LocalDateTime now = utcNow();
        if (mapper.deleteSubscription(
            subscriptionId, subscription.getReportId(), actor.id(), now
        ) != 1) {
            throw conflict("报表订阅已被删除");
        }
        mapper.cancelPendingDeliveries(subscriptionId, subscription.getReportId(), now);
    }

    /**
     * 校验{@code ManageableSubscription}，并在条件不满足时终止处理。
     *
     * @param reportId 资源标识
     * @param subscriptionId 资源标识
     * @param actor {@code actor}参数
     * @return 处理结果
     */
    private AgentReportSubscription requireManageableSubscription(
        Long reportId,
        Long subscriptionId,
        CurrentPrincipal actor
    ) {
        AgentReportSubscription subscription = mapper.selectSubscription(subscriptionId);
        if (subscription == null) {
            throw new ServiceException("报表订阅不存在", HttpStatus.NOT_FOUND);
        }
        if (reportId != null && !reportId.equals(subscription.getReportId())) {
            throw new ServiceException("报表订阅不属于当前报表", HttpStatus.NOT_FOUND);
        }
        requireManageable(subscription.getReportId(), actor);
        return subscription;
    }

    /**
     * 校验{@code Readable}，并在条件不满足时终止处理。
     *
     * @param reportId 资源标识
     * @return 处理结果
     */
    private AgentReport requireReadable(Long reportId) {
        CurrentPrincipal actor = human();
        AgentReport report = requireReport(reportId);
        if (actor.hasRole(PlatformRole.PLATFORM_ADMIN)
            || actor.id().equals(report.getOwnerId())
            || "enterprise_shared".equals(report.getVisibility())) {
            return report;
        }
        throw new ServiceException("没有查看该报表的权限", HttpStatus.FORBIDDEN);
    }

    /**
     * 校验{@code Manageable}，并在条件不满足时终止处理。
     *
     * @param reportId 资源标识
     * @param actor {@code actor}参数
     * @return 处理结果
     */
    private AgentReport requireManageable(Long reportId, CurrentPrincipal actor) {
        AgentReport report = requireReport(reportId);
        if (actor.hasRole(PlatformRole.PLATFORM_ADMIN) || actor.id().equals(report.getOwnerId())) {
            return report;
        }
        throw new ServiceException("没有管理该报表的权限", HttpStatus.FORBIDDEN);
    }

    /**
     * 校验报表，并在条件不满足时终止处理。
     *
     * @param reportId 资源标识
     * @return 处理结果
     */
    private AgentReport requireReport(Long reportId) {
        AgentReport report = mapper.selectById(reportId);
        if (report == null) {
            throw new ServiceException("报表不存在", HttpStatus.NOT_FOUND);
        }
        return report;
    }

    /**
     * 处理{@code human}并返回对应结果。
     *
     * @return 处理结果
     */
    private CurrentPrincipal human() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (!principal.isHuman()) {
            throw new ServiceException("机器主体不能直接管理报表", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 处理{@code renderSql}并返回对应结果。
     *
     * @param template 模板参数
     * @param parameters {@code parameters}参数
     * @return 处理结果
     */
    private String renderSql(String template, Map<String, Object> parameters) {
        Map<String, Object> values = parameters == null ? Map.of() : parameters;
        Matcher matcher = PARAMETER_TOKEN.matcher(template);
        StringBuffer rendered = new StringBuffer();
        Set<String> used = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            used.add(name);
            if (!values.containsKey(name)) {
                throw badRequest("缺少报表参数：" + name);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(sqlLiteral(values.get(name))));
        }
        matcher.appendTail(rendered);
        if (!values.keySet().stream().allMatch(key -> used.contains(key))) {
            throw badRequest("报表参数包含未使用的字段");
        }
        return rendered.toString();
    }

    /**
     * 处理{@code sqlLiteral}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String sqlLiteral(Object value) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean bool) {
            return bool ? "TRUE" : "FALSE";
        }
        if (value instanceof Number number) {
            String text = number.toString();
            try {
                new BigDecimal(text);
            } catch (NumberFormatException exception) {
                throw badRequest("报表数字参数无效");
            }
            return text;
        }
        if (value instanceof String text) {
            if (text.length() > 4000 || text.indexOf('\0') >= 0) {
                throw badRequest("报表文本参数无效");
            }
            return "'" + text.replace("'", "''") + "'";
        }
        throw badRequest("报表参数只支持文本、数字、布尔值或空值");
    }

    /**
     * 校验Sql模板，并在条件不满足时终止处理。
     *
     * @param sql {@code sql}参数
     */
    private void validateSqlTemplate(String sql) {
        if (sql == null || sql.isBlank() || sql.indexOf('\0') >= 0) {
            throw badRequest("报表 SQL 不能为空");
        }
        if (sql.length() > 65536) {
            throw badRequest("报表 SQL 超过 65536 字符");
        }
    }

    /**
     * 处理{@code normalizeJsonObject}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String normalizeJsonObject(String raw, String label) {
        String value = raw == null || raw.isBlank() ? "{}" : raw.strip();
        try {
            JsonNode node = jsonMapper.readTree(value);
            if (node == null || !node.isObject()) {
                throw badRequest(label + "必须是 JSON 对象");
            }
            return value;
        } catch (JacksonException exception) {
            throw badRequest(label + "不是有效 JSON");
        }
    }

    /**
     * 处理{@code writeJson}并返回对应结果。
     *
     * @param values {@code values}参数
     * @return 处理结果
     */
    private String writeJson(Map<String, Object> values) {
        try {
            return jsonMapper.writeValueAsString(values == null ? Map.of() : new LinkedHashMap<>(values));
        } catch (JacksonException exception) {
            throw badRequest("报表参数无法序列化");
        }
    }

    /**
     * 处理{@code readJsonObject}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Map<String, Object> readJsonObject(String raw, String label) {
        String value = raw == null || raw.isBlank() ? "{}" : raw;
        try {
            Map<String, Object> result = jsonMapper.readValue(value, MAP_TYPE);
            return result == null ? Map.of() : new LinkedHashMap<>(result);
        } catch (JacksonException exception) {
            throw badRequest(label + "不是有效 JSON");
        }
    }

    /**
     * 处理{@code enumValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String enumValue(String value, Set<String> allowed, String label) {
        String normalized = value == null ? "" : value.strip().toLowerCase();
        if (!allowed.contains(normalized)) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String text(String value, int max, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > max || normalized.indexOf('\0') >= 0) {
            throw badRequest(label + "为空或超过长度限制");
        }
        return normalized;
    }

    /**
     * 处理{@code textValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String textValue(Object value, int max, String label) {
        return text(value == null ? null : String.valueOf(value), max, label);
    }

    /**
     * 处理{@code longValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Long longValue(Object value, String label) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (value == null) return null;
        if (value instanceof Number number && number.longValue() > 0) return number.longValue();
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) {
            // Fall through to the same bounded conflict response.
        }
        throw conflict(label + "无效");
    }

    /**
     * 处理{@code longList}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 符合条件的数据集合
     */
    private List<Long> longList(Object value, String label) {
        if (!(value instanceof List<?> values) || values.size() < 2 || values.size() > 5) {
            throw conflict(label + "快照无效");
        }
        List<Long> result = new java.util.ArrayList<>();
        for (Object item : values) {
            Long id = longValue(item, label);
            if (id == null || result.contains(id)) throw conflict(label + "快照包含重复或无效数据集");
            result.add(id);
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code trimError}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String trimError(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
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
     * 处理{@code utcNow}并返回对应结果。
     *
     * @return 处理结果
     */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 封装Scheduled报表执行相关的不可变数据。
     */
    public record ScheduledReportExecution(Long reportRunId, DataQueryResultView result) {
    }
}
