package group.aitools.nhs.platform.report.service;

import lombok.extern.slf4j.Slf4j;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.report.domain.AgentReportSubscription;
import group.aitools.nhs.platform.report.domain.ReportDeliveryJob;
import group.aitools.nhs.platform.report.mapper.AgentReportMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 表示报表Subscription相关的领域对象。
 * Advances report-owned schedules and atomically creates durable delivery jobs. */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "agent.platform.report-scheduling",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class ReportSubscriptionScheduler {

    private static final int MAX_SUBSCRIPTIONS_PER_TICK = 50;

    private final AgentReportMapper mapper;
    private final PlatformIdGenerator idGenerator;
    private final ReportScheduleCalculator calculator;

    /**
     * 创建 {@code ReportSubscriptionScheduler} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param calculator {@code calculator}参数
     */
    public ReportSubscriptionScheduler(
        AgentReportMapper mapper,
        PlatformIdGenerator idGenerator,
        ReportScheduleCalculator calculator
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.calculator = calculator;
    }

    /**
     * 执行{@code Due}相关的处理流程。
     */
    @Scheduled(
        fixedDelayString = "${agent.platform.report-scheduling.dispatch-delay-ms:5000}",
        initialDelayString = "${agent.platform.report-scheduling.initial-delay-ms:10000}"
    )
    @Transactional(rollbackFor = Exception.class)
    public void dispatchDue() {
        LocalDateTime now = utcNow();
        for (AgentReportSubscription subscription
            : mapper.lockDueSubscriptions(now, MAX_SUBSCRIPTIONS_PER_TICK)) {
            enqueueAndAdvance(subscription, now);
        }
    }

    /**
     * 处理{@code enqueueAndAdvance}相关逻辑。
     *
     * @param subscription {@code subscription}参数
     * @param now {@code now}参数
     */
    void enqueueAndAdvance(AgentReportSubscription subscription, LocalDateTime now) {
        LocalDateTime scheduledAt = subscription.getNextRunAt();
        if (scheduledAt == null || scheduledAt.isAfter(now)) {
            throw new IllegalArgumentException("报表订阅没有到期运行时间");
        }
        ReportDeliveryJob job = new ReportDeliveryJob();
        job.setId(idGenerator.nextId());
        job.setSubscriptionId(subscription.getId());
        job.setReportId(subscription.getReportId());
        job.setRecipientId(subscription.getCreateBy());
        job.setScheduledAt(scheduledAt);
        job.setMaxAttempts(subscription.getMaxAttempts());
        job.setAvailableAt(now);
        job.setCreatedAt(now);
        mapper.insertDeliveryJob(job);

        LocalDateTime nextRunAt = calculator.next(subscription, now);
        if (mapper.advanceSubscriptionSchedule(
            subscription.getId(), subscription.getRevisionNo(), scheduledAt, nextRunAt, now
        ) != 1) {
            throw new IllegalStateException("报表订阅调度状态发生变化");
        }
        log.debug("Queued saved report subscription {} for {}", subscription.getId(), scheduledAt);
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
