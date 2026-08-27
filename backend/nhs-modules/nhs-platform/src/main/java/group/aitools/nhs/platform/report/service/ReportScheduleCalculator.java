package group.aitools.nhs.platform.report.service;

import group.aitools.nhs.platform.report.domain.AgentReportSubscription;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * 处理{@code normalizeCron}并返回对应结果。
 *
 * 表示报表调度Calculator相关的领域对象。
 * Calculates report-native cron and fixed-period schedules in UTC. */
@Component
public class ReportScheduleCalculator {

    public String normalizeCron(String expression) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (expression == null || expression.isBlank()) {
            throw badRequest("Cron表达式不能为空");
        }
        String normalized = expression.strip().replaceAll("\\s+", " ");
        int fields = normalized.split(" ").length;
        if (fields == 5) {
            normalized = "0 " + normalized;
        } else if (fields != 6) {
            throw badRequest("Cron表达式必须为5段或6段");
        }
        try {
            CronExpression.parse(normalized);
        } catch (IllegalArgumentException exception) {
            throw badRequest("Cron表达式无效");
        }
        return normalized;
    }

    /**
     * 处理{@code zone}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public ZoneId zone(String value) {
        String normalized = value == null || value.isBlank() ? "Asia/Shanghai" : value.strip();
        try {
            return ZoneId.of(normalized);
        } catch (DateTimeException exception) {
            throw badRequest("报表订阅时区无效");
        }
    }

    /**
     * 处理{@code next}并返回对应结果。
     *
     * @param subscription {@code subscription}参数
     * @param afterUtc {@code afterUtc}参数
     * @return 处理结果
     */
    public LocalDateTime next(AgentReportSubscription subscription, LocalDateTime afterUtc) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if ("interval".equals(subscription.getScheduleType())) {
            Integer minutes = subscription.getIntervalMinutes();
            if (minutes == null || minutes < 1 || minutes > 525600) {
                throw badRequest("报表订阅周期必须在1到525600分钟之间");
            }
            return afterUtc.plusMinutes(minutes);
        }
        if (!"cron".equals(subscription.getScheduleType())) {
            throw badRequest("报表订阅调度类型无效");
        }
        String cronExpression = normalizeCron(subscription.getCronExpr());
        ZonedDateTime local = afterUtc.atZone(ZoneOffset.UTC)
            .withZoneSameInstant(zone(subscription.getTimezone()));
        ZonedDateTime next = CronExpression.parse(cronExpression).next(local);
        if (next == null) {
            throw badRequest("Cron表达式没有可计算的下一次运行时间");
        }
        return next.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
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
}
