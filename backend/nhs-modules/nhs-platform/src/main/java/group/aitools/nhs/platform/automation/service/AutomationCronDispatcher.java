package group.aitools.nhs.platform.automation.service;

import lombok.extern.slf4j.Slf4j;
import group.aitools.nhs.platform.automation.domain.AutomationTrigger;
import group.aitools.nhs.platform.automation.mapper.AutomationMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 表示自动化Cron相关的领域对象。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "agent.platform.automation",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class AutomationCronDispatcher {

    private static final int MAX_TRIGGERS_PER_TICK = 50;
    private final AutomationMapper mapper;
    private final AutomationApplicationService applicationService;
    private final CronScheduleCalculator cronCalculator;

    /**
     * 创建 {@code AutomationCronDispatcher} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param applicationService 应用Service参数
     * @param cronCalculator {@code cronCalculator}参数
     */
    public AutomationCronDispatcher(
        AutomationMapper mapper,
        AutomationApplicationService applicationService,
        CronScheduleCalculator cronCalculator
    ) {
        this.mapper = mapper;
        this.applicationService = applicationService;
        this.cronCalculator = cronCalculator;
    }

    /**
     * 执行{@code Due}相关的处理流程。
     */
    @Scheduled(
        fixedDelayString = "${agent.platform.automation.dispatch-delay-ms:5000}",
        initialDelayString = "${agent.platform.automation.initial-delay-ms:10000}"
    )
    @Transactional(rollbackFor = Exception.class)
    public void dispatchDue() {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (AutomationTrigger trigger : mapper.lockDueTriggers(now, MAX_TRIGGERS_PER_TICK)) {
            try {
                DispatchPlan plan = plan(trigger, now);
                for (LocalDateTime scheduledAt : plan.fireTimes()) {
                    applicationService.cronFire(trigger, scheduledAt);
                }
                LocalDateTime lastRunAt = plan.fireTimes().isEmpty()
                    ? null : plan.fireTimes().getLast();
                if (mapper.updateSchedule(
                    trigger.getId(), trigger.getRevisionNo(), lastRunAt, plan.nextRunAt(), now
                ) != 1) {
                    throw new IllegalStateException("Cron触发器调度状态发生变化");
                }
            } catch (RuntimeException exception) {
                mapper.markTriggerError(trigger.getId(), trigger.getRevisionNo(), now);
                log.warn("Automation trigger {} was disabled after dispatch validation failed: {}",
                    trigger.getId(), safeError(exception));
            }
        }
        mapper.deleteExpiredNonces(now.minusMinutes(1));
    }

    /**
     * 处理{@code plan}并返回对应结果。
     *
     * @param trigger {@code trigger}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    DispatchPlan plan(AutomationTrigger trigger, LocalDateTime now) {
        // 以下流程根据当前类型或状态选择处理分支，并保证每种分支返回明确结果。
        LocalDateTime due = trigger.getNextRunAt();
        if (due == null || due.isAfter(now)) {
            throw new IllegalArgumentException("Cron触发器没有到期运行时间");
        }
        ZoneId zone = cronCalculator.zone(trigger.getTimezone());
        List<LocalDateTime> fires = new ArrayList<>();
        LocalDateTime next;
        switch (trigger.getMisfirePolicy()) {
            case "skip" -> {
                if (!due.isBefore(now.minusSeconds(30))) {
                    fires.add(due);
                }
                next = cronCalculator.next(trigger.getCronExpr(), zone, now);
            }
            case "fire_once" -> {
                fires.add(due);
                next = cronCalculator.next(trigger.getCronExpr(), zone, now);
            }
            case "catch_up" -> {
                LocalDateTime cursor = due;
                int limit = Math.max(1, Math.min(trigger.getMaxCatchupCount(), 10));
                while (!cursor.isAfter(now) && fires.size() < limit) {
                    fires.add(cursor);
                    cursor = cronCalculator.next(trigger.getCronExpr(), zone, cursor);
                }
                next = cursor.isAfter(now)
                    ? cursor : cronCalculator.next(trigger.getCronExpr(), zone, now);
            }
            default -> throw new IllegalArgumentException("Cron错过执行策略无效");
        }
        return new DispatchPlan(List.copyOf(fires), next);
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
            return throwable.getClass().getSimpleName();
        }
        String normalized = value.replace('\0', ' ').strip();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    /**
     * 封装{@code DispatchPlan}相关的不可变数据。
     */
    record DispatchPlan(List<LocalDateTime> fireTimes, LocalDateTime nextRunAt) {
    }
}
