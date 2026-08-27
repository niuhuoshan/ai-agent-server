package group.aitools.nhs.platform.automation.service;

import group.aitools.nhs.platform.automation.mapper.AutomationMapper;
import group.aitools.nhs.platform.automation.persistence.row.AutomationJobRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 负责自动化QueuePersistence相关的业务编排与领域规则处理。
 */
@Service
public class AutomationQueuePersistenceService {

    private final AutomationMapper mapper;

    /**
     * 创建 {@code AutomationQueuePersistenceService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     */
    public AutomationQueuePersistenceService(AutomationMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 处理{@code claim}并返回对应结果。
     *
     * @param workerId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AutomationJobRow claim(String workerId) {
        LocalDateTime now = utcNow();
        String leaseToken = UUID.randomUUID().toString();
        AutomationJobRow job = mapper.claimJob(workerId, leaseToken, now, now.plusMinutes(2));
        if (job != null && mapper.markFireRunning(
            job.getFireId(), job.getId(), job.getAttemptNo()
        ) != 1) {
            throw new IllegalStateException("自动化触发事实不能进入运行状态");
        }
        return job;
    }

    /**
     * 处理{@code renew}相关逻辑。
     *
     * @param job 作业参数
     * @param workerId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void renew(AutomationJobRow job, String workerId) {
        LocalDateTime now = utcNow();
        if (mapper.renewJob(
            job.getId(), workerId, job.getLeaseToken(), now, now.plusMinutes(2)
        ) != 1) {
            throw new StaleAutomationLeaseException();
        }
    }

    /**
     * 处理{@code complete}相关逻辑。
     *
     * @param job 作业参数
     * @param workerId 资源标识
     * @param runId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(AutomationJobRow job, String workerId, Long runId) {
        LocalDateTime now = utcNow();
        if (mapper.completeJob(job.getId(), workerId, job.getLeaseToken(), now) != 1) {
            throw new StaleAutomationLeaseException();
        }
        if (mapper.completeFire(job.getFireId(), job.getId(), runId, now) != 1) {
            throw new IllegalStateException("自动化触发事实不能完成");
        }
    }

    /**
     * 处理{@code fail}相关逻辑。
     *
     * @param job 作业参数
     * @param workerId 资源标识
     * @param error {@code error}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void fail(AutomationJobRow job, String workerId, String error) {
        LocalDateTime now = utcNow();
        boolean dead = job.getAttemptNo() >= job.getMaxAttempts();
        String status = dead ? "dead" : "queued";
        LocalDateTime availableAt = dead ? now : now.plusSeconds(backoffSeconds(job.getAttemptNo()));
        if (mapper.failJob(
            job.getId(), workerId, job.getLeaseToken(), status, availableAt, error, now
        ) != 1) {
            throw new StaleAutomationLeaseException();
        }
        if (mapper.failFire(
            job.getFireId(), job.getId(), job.getAttemptNo(), dead ? "dead" : "retry", error, now
        ) != 1) {
            throw new IllegalStateException("自动化触发事实不能记录失败");
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
            case 1 -> 5;
            case 2 -> 30;
            default -> 120;
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
     * 表示Stale自动化Lease处理过程中发生的业务异常。
     */
    public static final class StaleAutomationLeaseException extends IllegalStateException {
        /**
         * 创建 {@code StaleAutomationLeaseException} 实例并初始化所需依赖。
         */
        public StaleAutomationLeaseException() {
            super("自动化作业租约已失效");
        }
    }
}
