package group.aitools.nhs.platform.report.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.report.domain.AgentReportSubscription;
import group.aitools.nhs.platform.report.domain.ReportDeliveryJob;
import group.aitools.nhs.platform.report.domain.ReportNotificationOutboxEvent;
import group.aitools.nhs.platform.report.mapper.AgentReportMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 负责报表DeliveryPersistence相关的业务编排与领域规则处理。
 * Owns delivery leases, retry transitions and transactional notification outbox writes. */
@Service
public class ReportDeliveryPersistenceService {

    private final AgentReportMapper mapper;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;

    public ReportDeliveryPersistenceService(
        AgentReportMapper mapper,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code claim}并返回对应结果。
     *
     * @param workerId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ReportDeliveryJob claim(String workerId) {
        LocalDateTime now = utcNow();
        return mapper.claimDeliveryJob(
            workerId, UUID.randomUUID().toString(), now, now.plusMinutes(10)
        );
    }

    /**
     * 处理{@code complete}相关逻辑。
     *
     * @param job 作业参数
     * @param workerId 资源标识
     * @param execution 执行参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(
        ReportDeliveryJob job,
        String workerId,
        ReportApplicationService.ScheduledReportExecution execution
    ) {
        LocalDateTime now = utcNow();
        if (mapper.completeDeliveryJob(
            job.getId(), workerId, job.getLeaseToken(), execution.reportRunId(), now
        ) != 1) {
            throw new StaleReportDeliveryLeaseException();
        }
        enqueueNotification(
            job,
            "report.delivery.succeeded",
            "success",
            "定时报表执行成功",
            "报表 " + job.getReportId() + " 已生成，共 " + execution.result().rowCount() + " 行",
            execution,
            now
        );
    }

    /**
     * 处理{@code fail}相关逻辑。
     *
     * @param job 作业参数
     * @param workerId 资源标识
     * @param error {@code error}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void fail(ReportDeliveryJob job, String workerId, String error) {
        LocalDateTime now = utcNow();
        boolean dead = job.getAttemptNo() >= job.getMaxAttempts();
        String status = dead ? "dead" : "retry";
        LocalDateTime availableAt = dead ? now : now.plusSeconds(backoffSeconds(job.getAttemptNo()));
        if (mapper.failDeliveryJob(
            job.getId(), workerId, job.getLeaseToken(), status, availableAt, error, now
        ) != 1) {
            throw new StaleReportDeliveryLeaseException();
        }
        if (dead) {
            enqueueNotification(
                job,
                "report.delivery.failed",
                "error",
                "定时报表执行失败",
                "报表 " + job.getReportId() + " 在 " + job.getMaxAttempts() + " 次尝试后仍失败：" + error,
                null,
                now
            );
        }
    }

    /**
     * 判断{@code cel}是否满足要求。
     *
     * @param job 作业参数
     * @param workerId 资源标识
     * @param reason {@code reason}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(ReportDeliveryJob job, String workerId, String reason) {
        if (mapper.cancelDeliveryJob(
            job.getId(), workerId, job.getLeaseToken(), reason, utcNow()
        ) != 1) {
            throw new StaleReportDeliveryLeaseException();
        }
    }

    /**
     * 处理enqueue通知相关逻辑。
     *
     * @param job 作业参数
     * @param eventType 业务类型
     * @param level {@code level}参数
     * @param title {@code title}参数
     * @param content 待处理内容
     * @param execution 执行参数
     * @param now {@code now}参数
     */
    private void enqueueNotification(
        ReportDeliveryJob job,
        String eventType,
        String level,
        String title,
        String content,
        ReportApplicationService.ScheduledReportExecution execution,
        LocalDateTime now
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (job.getRecipientId() == null || job.getRecipientId() <= 0) {
            return;
        }
        AgentReportSubscription subscription = mapper.selectSubscription(job.getSubscriptionId());
        if (!notificationEnabled(subscription, eventType)) {
            return;
        }
        List<String> channels = notificationChannels(subscription);
        if (channels.isEmpty()) {
            return;
        }
        String eventKey = "report-delivery:" + job.getId();
        ReportNotificationPayload payload = new ReportNotificationPayload(
            job.getRecipientId(), eventKey, level, title, content, job.getReportId(),
            execution == null ? null : execution.reportRunId(),
            execution == null ? null : execution.result().queryId(),
            execution == null ? null : execution.result().resultHash(),
            channels
        );
        ReportNotificationOutboxEvent event = new ReportNotificationOutboxEvent();
        event.setId(idGenerator.nextId());
        event.setEventType(eventType);
        event.setAggregateId(job.getSubscriptionId());
        event.setEventKey(eventKey);
        try {
            event.setPayloadJson(jsonMapper.writeValueAsString(payload));
        } catch (JacksonException exception) {
            throw new IllegalStateException("报表通知无法序列化", exception);
        }
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        mapper.insertReportNotificationOutbox(event);
    }

    /**
     * 处理通知Enabled并返回对应结果。
     *
     * @param subscription {@code subscription}参数
     * @param eventType 业务类型
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean notificationEnabled(AgentReportSubscription subscription, String eventType) {
        if (subscription == null || subscription.getNotifyPolicyJson() == null
            || subscription.getNotifyPolicyJson().isBlank()) {
            return true;
        }
        try {
            JsonNode policy = jsonMapper.readTree(subscription.getNotifyPolicyJson());
            boolean success = "report.delivery.succeeded".equals(eventType);
            JsonNode configured = first(
                policy,
                success ? "onSuccess" : "onFailure",
                success ? "notifyOnSuccess" : "notifyOnFailure",
                success ? "notify_on_success" : "notify_on_failure"
            );
            return configured == null || !configured.isBoolean() || configured.booleanValue();
        } catch (JacksonException exception) {
            return true;
        }
    }

    /**
     * 处理通知Channels并返回对应结果。
     *
     * @param subscription {@code subscription}参数
     * @return 符合条件的数据集合
     */
    private List<String> notificationChannels(AgentReportSubscription subscription) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (subscription == null || subscription.getNotifyPolicyJson() == null
            || subscription.getNotifyPolicyJson().isBlank()) {
            return List.of("inbox");
        }
        try {
            JsonNode policy = jsonMapper.readTree(subscription.getNotifyPolicyJson());
            JsonNode configured = policy == null ? null : policy.get("channels");
            if (configured == null || !configured.isArray()) {
                return List.of("inbox");
            }
            Set<String> allowed = Set.of("inbox", "portal", "dingtalk", "wechat_work", "email");
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (JsonNode value : configured) {
                if (!value.isTextual()) {
                    throw new IllegalArgumentException("通知渠道必须是字符串");
                }
                String channel = value.asText().strip().toLowerCase(java.util.Locale.ROOT);
                if (!allowed.contains(channel)) {
                    throw new IllegalArgumentException("通知渠道无效");
                }
                result.add(channel);
            }
            return List.copyOf(result);
        } catch (RuntimeException exception) {
            // Existing subscriptions created before channel validation remain inbox-safe.
            return List.of("inbox");
        }
    }

    /**
     * 处理{@code first}并返回对应结果。
     *
     * @param source 数据源参数
     * @param names 名称
     * @return 处理结果
     */
    private JsonNode first(JsonNode source, String... names) {
        if (source == null || !source.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = source.get(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 处理{@code backoffSeconds}并返回对应结果。
     *
     * @param attempt {@code attempt}参数
     * @return 处理结果
     */
    private long backoffSeconds(int attempt) {
        return switch (attempt) {
            case 1 -> 10;
            case 2 -> 60;
            default -> 300;
        };
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
     * 表示Stale报表DeliveryLease处理过程中发生的业务异常。
     */
    public static final class StaleReportDeliveryLeaseException extends IllegalStateException {
        /**
         * 创建 {@code StaleReportDeliveryLeaseException} 实例并初始化所需依赖。
         */
        public StaleReportDeliveryLeaseException() {
            super("报表交付作业租约已失效");
        }
    }
}
