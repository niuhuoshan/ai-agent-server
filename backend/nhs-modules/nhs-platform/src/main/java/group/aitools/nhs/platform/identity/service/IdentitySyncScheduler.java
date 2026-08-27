package group.aitools.nhs.platform.identity.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 表示身份Sync相关的领域对象。
 * Evaluates the persisted hourly/daily/weekly identity-sync preset once per hour. */
@Slf4j
@Component
public class IdentitySyncScheduler {

    private final IdentitySyncApplicationService service;

    public IdentitySyncScheduler(IdentitySyncApplicationService service) {
        this.service = service;
    }

    /**
     * 处理{@code synchronize}相关逻辑。
     */
    @Scheduled(cron = "0 0 * * * *")
    public void synchronize() {
        try {
            service.runScheduled(LocalDateTime.now());
        } catch (RuntimeException exception) {
            log.warn("Scheduled identity-provider synchronization did not complete: {}", exception.getMessage());
        }
    }
}
