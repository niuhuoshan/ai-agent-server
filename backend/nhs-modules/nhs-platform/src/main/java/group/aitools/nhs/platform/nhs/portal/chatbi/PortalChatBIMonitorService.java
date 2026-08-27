package group.aitools.nhs.platform.nhs.portal.chatbi;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.data.persistence.row.DataQueryStoredResultRow;
import group.aitools.nhs.platform.data.domain.AgentDataQuery;
import group.aitools.nhs.platform.report.domain.AgentReport;
import group.aitools.nhs.platform.report.mapper.AgentReportMapper;
import group.aitools.nhs.platform.report.service.ReportApplicationService;
import group.aitools.nhs.platform.report.web.CreateReportRequest;
import group.aitools.nhs.platform.report.web.CreateReportSubscriptionRequest;
import group.aitools.nhs.platform.report.web.ReportSubscriptionView;
import group.aitools.nhs.platform.report.web.UpdateReportRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责门户对话BIMonitor相关的业务编排与领域规则处理。
 * Converts a supplied read-only ChatBI query into a durable report subscription. */
@Service
public class PortalChatBIMonitorService {

    private final CurrentPrincipalProvider principalProvider;
    private final PortalChatBIQueryMapper queryMapper;
    private final AgentReportMapper reportMapper;
    private final ReportApplicationService reportService;
    private final PortalChatBIFederatedService federatedService;
    private final JsonMapper jsonMapper;

    public PortalChatBIMonitorService(
        CurrentPrincipalProvider principalProvider,
        PortalChatBIQueryMapper queryMapper,
        AgentReportMapper reportMapper,
        ReportApplicationService reportService,
        PortalChatBIFederatedService federatedService,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.queryMapper = queryMapper;
        this.reportMapper = reportMapper;
        this.reportService = reportService;
        this.federatedService = federatedService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(CreateMonitorRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (!principal.isHuman()) {
            throw new ServiceException("机器主体不能创建门户查询监控", HttpStatus.FORBIDDEN);
        }
        Long queryId = queryId(request.resultId());
        AgentDataQuery query = queryMapper.selectOwnedQuery(queryId, principal.id());
        DataQueryStoredResultRow stored = queryMapper.selectOwnedResult(queryId, principal.id());
        if (query == null || !"succeeded".equals(query.getStatus())
            || query.getConversationId() == null || query.getDatasetId() == null
            || query.getSqlText() == null || query.getSqlText().isBlank()
            || stored == null || !validHash(stored.getContentHash())) {
            throw new ServiceException("可创建监控的 ChatBI 查询结果不存在", HttpStatus.NOT_FOUND);
        }
        AgentChatBIFederatedRun federation = federatedService.findByResult(queryId, principal.id());
        Long datasetId = query.getDatasetId();
        String sql = query.getSqlText().strip();
        validateReadOnlySql(sql);
        String conversationId = String.valueOf(query.getConversationId());
        String resultId = String.valueOf(queryId);
        String reportKey = "chatbi-monitor-" + digest(
            principal.id() + ":" + conversationId + ":" + resultId
        ).substring(0, 32);
        AgentReport existing = reportMapper.selectByKey(reportKey, principal.id());
        if (existing != null) {
            persistLineage(existing.getId(), principal.id(), query, stored, federation);
            List<ReportSubscriptionView> subscriptions = reportService.subscriptions(existing.getId());
            ReportSubscriptionView subscription = subscriptions.isEmpty() ? null : subscriptions.get(0);
            return result(existing.getId(), subscription == null ? null : subscription.id(), false, null, subscription);
        }

        String title = request.title() == null || request.title().isBlank()
            ? "ChatBI 查询监控" : text(request.title(), 255, "监控名称");
        AgentReport report = null;
        try {
            report = reportService.create(new CreateReportRequest(
                reportKey, title, datasetId, sql, "{}", "private"
            )) == null ? null : reportMapper.selectByKey(reportKey, principal.id());
            if (report == null) {
                throw new ServiceException("监控报表创建失败", HttpStatus.ERROR);
            }
            reportService.update(report.getId(), new UpdateReportRequest(
                title, datasetId, sql, "{}", "active", "private"
            ));
            persistLineage(report.getId(), principal.id(), query, stored, federation);
            String cron = cron(
                request.scheduleType(), request.timeValue(), request.weekday(), request.monthday()
            );
            ReportSubscriptionView subscription = reportService.createSubscription(
                report.getId(), new CreateReportSubscriptionRequest(
                    "cron", cron, null, "Asia/Shanghai", "{}",
                    notifyPolicy(request.notifyOnSuccess()), 3
                )
            );
            return result(report.getId(), subscription.id(), true, cron, subscription);
        } catch (ServiceException exception) {
            throw exception;
        }
    }

    /**
     * 处理{@code persistLineage}相关逻辑。
     *
     * @param reportId 资源标识
     * @param ownerId 资源标识
     * @param query 查询参数
     * @param stored {@code stored}参数
     * @param federation {@code federation}参数
     */
    private void persistLineage(
        Long reportId,
        Long ownerId,
        AgentDataQuery query,
        DataQueryStoredResultRow stored,
        AgentChatBIFederatedRun federation
    ) {
        Map<String, Object> lineage = new LinkedHashMap<>();
        lineage.put("lineageVersion", 1);
        lineage.put("sourceType", federation == null ? "chatbi_query" : "chatbi_federated");
        lineage.put("sourceQueryId", query.getId());
        lineage.put("sourceResultHash", stored.getContentHash());
        lineage.put("sourceConversationId", query.getConversationId());
        lineage.put("sourceTraceId", query.getTraceId());
        lineage.put("sourceSqlHash", query.getSqlHash());
        if (federation != null) {
            lineage.put("executionMode", "federated");
            lineage.put("federatedRunKey", federation.getRunKey());
            lineage.put("datasetIds", jsonArray(federation.getDatasetIdsJson()));
            lineage.put("sourceConversationId", federation.getConversationId());
            lineage.put("question", federation.getRequestQuestion());
            lineage.put("planJson", federation.getPlanJson());
            lineage.put("joinSql", federation.getJoinSql());
        }
        try {
            if (reportMapper.updateLineage(
                reportId, ownerId, jsonMapper.writeValueAsString(lineage), LocalDateTime.now()
            ) != 1) {
                throw new ServiceException("监控结果血缘写入失败", HttpStatus.CONFLICT);
            }
        } catch (JacksonException exception) {
            throw new ServiceException("监控结果血缘无法序列化", HttpStatus.ERROR);
        }
    }

    /**
     * 处理{@code jsonArray}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 处理结果
     */
    private Object jsonArray(String raw) {
        try {
            var node = jsonMapper.readTree(raw == null ? "[]" : raw);
            return node == null || !node.isArray() ? List.of() : jsonMapper.convertValue(node, List.class);
        } catch (JacksonException exception) {
            throw new ServiceException("联邦数据集快照无法读取", HttpStatus.CONFLICT);
        }
    }

    /**
     * 处理{@code validHash}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean validHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /**
     * 获取{@code Id}。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Long queryId(String value) {
        String normalized = text(value, 128, "结果标识");
        try {
            long id = Long.parseLong(normalized);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new ServiceException("结果标识必须是有效的 ChatBI 查询 ID", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理结果并返回对应结果。
     *
     * @param reportId 资源标识
     * @param subscriptionId 资源标识
     * @param created {@code created}参数
     * @param cron {@code cron}参数
     * @param subscription {@code subscription}参数
     * @return 处理结果
     */
    private Map<String, Object> result(
        Long reportId,
        Long subscriptionId,
        boolean created,
        String cron,
        ReportSubscriptionView subscription
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("report_id", reportId);
        value.put("subscription_id", subscriptionId);
        value.put("created", created);
        if (cron != null) {
            value.put("cron_expr", cron);
        }
        if (subscription != null) {
            value.put("next_run_at", subscription.nextRunAt());
            value.put("status", subscription.status());
        }
        return value;
    }

    /**
     * 处理{@code cron}并返回对应结果。
     *
     * @param type 业务类型
     * @param time {@code time}参数
     * @param weekday {@code weekday}参数
     * @param monthday {@code monthday}参数
     * @return 处理结果
     */
    private String cron(String type, String time, Integer weekday, Integer monthday) {
        String schedule = type == null ? "daily" : type.strip().toLowerCase();
        String value = time == null ? "09:00" : time.strip();
        if (!value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
            throw new ServiceException("执行时间无效", HttpStatus.BAD_REQUEST);
        }
        String[] parts = value.split(":");
        String minute = parts[1];
        String hour = parts[0];
        return switch (schedule) {
            case "daily" -> "0 " + minute + " " + hour + " * * *";
            case "weekly" -> {
                int day = weekday == null ? 0 : weekday;
                if (day < 0 || day > 6) {
                    throw new ServiceException("星期参数无效", HttpStatus.BAD_REQUEST);
                }
                // Spring cron uses 1=Sunday, 2=Monday, ... 7=Saturday.
                yield "0 " + minute + " " + hour + " * * " + (day == 6 ? 1 : day + 2);
            }
            case "monthly" -> {
                int day = monthday == null ? 1 : monthday;
                if (day < 1 || day > 28) {
                    throw new ServiceException("每月日期必须在1到28之间", HttpStatus.BAD_REQUEST);
                }
                yield "0 " + minute + " " + hour + " " + day + " * *";
            }
            default -> throw new ServiceException("调度类型无效", HttpStatus.BAD_REQUEST);
        };
    }

    /**
     * 处理notify策略并返回对应结果。
     *
     * @param success {@code success}参数
     * @return 处理结果
     */
    private String notifyPolicy(boolean success) {
        return success ? "{\"notifyOnSuccess\":true,\"notifyOnFailure\":true}"
            : "{\"notifyOnSuccess\":false,\"notifyOnFailure\":true}";
    }

    /**
     * 校验{@code ReadOnlySql}，并在条件不满足时终止处理。
     *
     * @param sql {@code sql}参数
     */
    private void validateReadOnlySql(String sql) {
        String normalized = sql.replaceFirst("^\\s*(?:/\\*[\\s\\S]*?\\*/|--[^\\n]*\\n)*", "").strip();
        if (!normalized.matches("(?is)^(?:WITH\\b[\\s\\S]*?\\bSELECT\\b|SELECT\\b)[\\s\\S]*")) {
            throw new ServiceException("监控查询必须是只读 SELECT SQL", HttpStatus.BAD_REQUEST);
        }
        String forbidden = "(?i)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|GRANT|REVOKE|COPY|CALL)\\b";
        if (normalized.matches("[\\s\\S]*" + forbidden + "[\\s\\S]*")) {
            throw new ServiceException("监控查询包含禁止的写入或管理语句", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理{@code digest}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
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
            throw new ServiceException(label + "为空或超过长度限制", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 封装{@code CreateMonitor}相关的不可变数据。
     */
    public record CreateMonitorRequest(
        String resultId,
        String title,
        String scheduleType,
        String timeValue,
        Integer weekday,
        Integer monthday,
        boolean notifyOnSuccess
    ) {
    }
}
