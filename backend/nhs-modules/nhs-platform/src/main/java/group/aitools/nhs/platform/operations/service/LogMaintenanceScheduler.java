package group.aitools.nhs.platform.operations.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 表示{@code LogMaintenance}相关的领域对象。
 * Daily PostgreSQL log partition preparation and bounded retention cleanup. */
@Slf4j
@Component
public class LogMaintenanceScheduler {

    private final LogMaintenanceApplicationService service;

    public LogMaintenanceScheduler(LogMaintenanceApplicationService service) {
        this.service = service;
    }

    /**
     * 处理{@code maintain}相关逻辑。
     */
    @Scheduled(
        cron = "${agent.operations.log-maintenance-cron:0 0 2 * * *}",
        zone = "${agent.operations.log-maintenance-zone:Asia/Shanghai}"
    )
    public void maintain() {
        try {
            var result = service.scheduledMaintenance();
            log.info(
                "Log maintenance {}: run={}, deletedRows={}, droppedPartitions={}, remaining={}",
                result.status(), result.runId(), result.deletedRows(), result.droppedPartitions().size(),
                result.remainingExpiredRows()
            );
        } catch (RuntimeException exception) {
            log.error("Scheduled PostgreSQL log maintenance failed: {}", exception.getMessage());
        }
    }
}
