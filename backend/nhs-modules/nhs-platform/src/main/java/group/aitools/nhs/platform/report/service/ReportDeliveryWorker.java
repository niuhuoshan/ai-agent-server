package group.aitools.nhs.platform.report.service;

import lombok.extern.slf4j.Slf4j;
import group.aitools.nhs.platform.report.domain.ReportDeliveryJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * 表示报表Delivery工作进程相关的领域对象。
 * Executes report delivery jobs without assuming a human request principal. */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "agent.platform.report-scheduling",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class ReportDeliveryWorker {

    private final String workerId = "report-delivery-" + ManagementFactory.getRuntimeMXBean().getName();
    private final ReportDeliveryPersistenceService persistence;
    private final ReportApplicationService reportService;

    /**
     * 创建 {@code ReportDeliveryWorker} 实例并初始化所需依赖。
     *
     * @param persistence {@code persistence}参数
     * @param reportService 报表Service参数
     */
    public ReportDeliveryWorker(
        ReportDeliveryPersistenceService persistence,
        ReportApplicationService reportService
    ) {
        this.persistence = persistence;
        this.reportService = reportService;
    }

    /**
     * 处理{@code poll}相关逻辑。
     */
    @Scheduled(
        fixedDelayString = "${agent.platform.report-scheduling.worker-delay-ms:1000}",
        initialDelayString = "${agent.platform.report-scheduling.initial-delay-ms:10000}"
    )
    public void poll() {
        for (int count = 0; count < 10; count++) {
            ReportDeliveryJob job = persistence.claim(workerId);
            if (job == null) {
                return;
            }
            process(job);
        }
    }

    /**
     * 执行{@code process}相关的处理流程。
     *
     * @param job 作业参数
     */
    void process(ReportDeliveryJob job) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try {
            ReportApplicationService.ScheduledReportExecution execution =
                reportService.executeScheduledSubscription(job.getSubscriptionId());
            persistence.complete(job, workerId, execution);
        } catch (ReportDeliveryCancelledException exception) {
            try {
                persistence.cancel(job, workerId, safeError(exception));
            } catch (ReportDeliveryPersistenceService.StaleReportDeliveryLeaseException stale) {
                log.info("Report worker {} could not cancel stale job {}", workerId, job.getId());
            }
        } catch (ReportDeliveryPersistenceService.StaleReportDeliveryLeaseException exception) {
            log.info("Report worker {} stopped stale job {}", workerId, job.getId());
        } catch (RuntimeException exception) {
            try {
                persistence.fail(job, workerId, safeError(exception));
            } catch (ReportDeliveryPersistenceService.StaleReportDeliveryLeaseException stale) {
                log.info("Report worker {} could not fail stale job {}", workerId, job.getId());
            }
        }
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
}
