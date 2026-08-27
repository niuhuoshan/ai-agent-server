package group.aitools.nhs.platform.automation.service;

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
 * 表示Cron调度Calculator相关的领域对象。
 */
@Component
public class CronScheduleCalculator {

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param expression {@code expression}参数
     * @return 处理结果
     */
    public String normalize(String expression) {
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
            throw badRequest("Cron时区无效");
        }
    }

    /**
 * 处理{@code next}并返回对应结果。
 * All persisted trigger timestamps are UTC LocalDateTime values. */
    public LocalDateTime next(String expression, ZoneId zone, LocalDateTime afterUtc) {
        CronExpression cron = CronExpression.parse(normalize(expression));
        ZonedDateTime local = afterUtc.atZone(ZoneOffset.UTC).withZoneSameInstant(zone);
        ZonedDateTime next = cron.next(local);
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
