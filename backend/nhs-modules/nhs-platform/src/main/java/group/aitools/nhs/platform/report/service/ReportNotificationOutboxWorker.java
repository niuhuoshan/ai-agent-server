package group.aitools.nhs.platform.report.service;

import lombok.extern.slf4j.Slf4j;
import group.aitools.nhs.platform.report.domain.ReportNotificationOutboxEvent;
import group.aitools.nhs.platform.report.mapper.AgentReportMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 表示报表通知Outbox工作进程相关的领域对象。
 * Publishes report delivery notifications from the durable platform outbox. */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "agent.platform.report-scheduling",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class ReportNotificationOutboxWorker {

    private static final int MAX_OUTBOX_ATTEMPTS = 5;

    private final AgentReportMapper mapper;
    private final ReportNotificationPublisher publisher;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code ReportNotificationOutboxWorker} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param publisher {@code publisher}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public ReportNotificationOutboxWorker(
        AgentReportMapper mapper,
        ReportNotificationPublisher publisher,
        JsonMapper jsonMapper
    ) {
        this.mapper = mapper;
        this.publisher = publisher;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code publishDue}相关逻辑。
     */
    @Scheduled(
        fixedDelayString = "${agent.platform.report-scheduling.outbox-delay-ms:2000}",
        initialDelayString = "${agent.platform.report-scheduling.initial-delay-ms:10000}"
    )
    @Transactional(rollbackFor = Exception.class)
    public void publishDue() {
        LocalDateTime now = utcNow();
        for (ReportNotificationOutboxEvent event
            : mapper.lockDueReportNotificationOutbox(now, 50)) {
            publish(event, now);
        }
    }

    /**
     * 处理{@code publish}相关逻辑。
     *
     * @param event 事件参数
     * @param now {@code now}参数
     */
    void publish(ReportNotificationOutboxEvent event, LocalDateTime now) {
        try {
            ReportNotificationPayload payload = jsonMapper.readValue(
                event.getPayloadJson(), ReportNotificationPayload.class
            );
            validate(payload);
            publisher.publish(payload);
            if (mapper.markReportNotificationPublished(event.getId(), now) != 1) {
                throw new IllegalStateException("报表通知 outbox 状态发生变化");
            }
        } catch (RuntimeException exception) {
            int nextAttempt = event.getAttemptNo() + 1;
            String status = nextAttempt >= MAX_OUTBOX_ATTEMPTS ? "failed" : "pending";
            LocalDateTime nextAttemptAt = now.plusSeconds(backoffSeconds(nextAttempt));
            mapper.markReportNotificationFailed(
                event.getId(), status, nextAttemptAt, safeError(exception)
            );
            log.warn("Report notification outbox {} publish failed: {}", event.getId(), safeError(exception));
        }
    }

    /**
     * 校验{@code validate}，并在条件不满足时终止处理。
     *
     * @param payload {@code payload}参数
     */
    private void validate(ReportNotificationPayload payload) {
        if (payload == null || payload.recipientId() == null || payload.recipientId() <= 0
            || payload.reportId() == null || payload.reportId() <= 0
            || payload.eventKey() == null || payload.eventKey().isBlank()
            || payload.level() == null || payload.title() == null) {
            throw new IllegalArgumentException("报表通知 outbox 载荷无效");
        }
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
        String normalized = value.replace('\0', ' ').strip();
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
    }

    /**
     * 处理{@code utcNow}并返回对应结果。
     *
     * @return 处理结果
     */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
