package group.aitools.nhs.platform.automation.service;

import lombok.extern.slf4j.Slf4j;
import group.aitools.nhs.platform.automation.domain.AutomationFire;
import group.aitools.nhs.platform.automation.domain.AutomationTrigger;
import group.aitools.nhs.platform.automation.mapper.AutomationMapper;
import group.aitools.nhs.platform.automation.persistence.row.AutomationJobRow;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.execution.web.CreateTaskRunRequest;
import group.aitools.nhs.platform.execution.web.TaskRunActionResult;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.identity.service.ServiceAccountPrincipalResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表示自动化作业工作进程相关的领域对象。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "agent.platform.automation",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class AutomationJobWorker {

    private static final int MAX_PAYLOAD_BYTES = 128 * 1024;
    private static final Pattern RAW_CREDENTIAL = Pattern.compile("agk_[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final String workerId = "automation-" + ManagementFactory.getRuntimeMXBean().getName();
    private final AutomationQueuePersistenceService persistence;
    private final AutomationMapper mapper;
    private final ServiceAccountPrincipalResolver accountResolver;
    private final TaskRunApplicationService runService;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code AutomationJobWorker} 实例并初始化所需依赖。
     *
     * @param persistence {@code persistence}参数
     * @param mapper {@code mapper}参数
     * @param accountResolver 账户Resolver参数
     * @param runService {@code runService}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public AutomationJobWorker(
        AutomationQueuePersistenceService persistence,
        AutomationMapper mapper,
        ServiceAccountPrincipalResolver accountResolver,
        TaskRunApplicationService runService,
        JsonMapper jsonMapper
    ) {
        this.persistence = persistence;
        this.mapper = mapper;
        this.accountResolver = accountResolver;
        this.runService = runService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code poll}相关逻辑。
     */
    @Scheduled(
        fixedDelayString = "${agent.platform.automation.worker-delay-ms:1000}",
        initialDelayString = "${agent.platform.automation.initial-delay-ms:10000}"
    )
    public void poll() {
        for (int count = 0; count < 10; count++) {
            AutomationJobRow job = persistence.claim(workerId);
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
    void process(AutomationJobRow job) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try {
            AutomationFire fire = mapper.selectFire(job.getFireId());
            if (fire == null || !job.getId().equals(fire.getJobId())
                || !"running".equals(fire.getStatus())) {
                throw new IllegalStateException("自动化触发事实与队列作业不一致");
            }
            AutomationTrigger trigger = mapper.selectTrigger(fire.getTriggerId());
            boolean manuallyRunnable = trigger != null
                && "manual".equals(fire.getSourceType())
                && Set.of("active", "paused", "error").contains(trigger.getStatus());
            if (trigger == null || (!"active".equals(trigger.getStatus()) && !manuallyRunnable)
                || !trigger.getRevisionNo().equals(fire.getTriggerRevisionNo())
                || !trigger.getServiceAccountId().equals(fire.getServiceAccountId())) {
                throw new IllegalStateException("自动化触发器已失效或配置发生变化");
            }
            CurrentPrincipal principal = accountResolver.requireActive(fire.getServiceAccountId());
            String input = payloadInput(job.getPayloadJson());
            TaskRunActionResult created = runService.createAs(
                principal,
                trigger.getTaskId(),
                trigger.getTaskVersionId(),
                new CreateTaskRunRequest("automation-fire:" + fire.getId(), input)
            );
            persistence.renew(job, workerId);
            TaskRunActionResult started = runService.startAs(
                principal, trigger.getTaskId(), created.run().id(), trigger.getTaskVersionId()
            );
            persistence.complete(job, workerId, started.run().id());
        } catch (AutomationQueuePersistenceService.StaleAutomationLeaseException exception) {
            log.info("Automation worker {} stopped stale job {}", workerId, job.getId());
        } catch (RuntimeException exception) {
            String error = safeError(exception);
            try {
                persistence.fail(job, workerId, error);
            } catch (AutomationQueuePersistenceService.StaleAutomationLeaseException stale) {
                log.info("Automation worker {} could not fail stale job {}", workerId, job.getId());
            }
        }
    }

    /**
     * 处理{@code payloadInput}并返回对应结果。
     *
     * @param payloadJson {@code payloadJson}参数
     * @return 处理结果
     */
    private String payloadInput(String payloadJson) {
        if (payloadJson == null
            || payloadJson.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("自动化作业载荷为空或超过限制");
        }
        Map<String, Object> payload = jsonMapper.readValue(payloadJson, MAP_TYPE);
        if (!payload.keySet().equals(Set.of("input")) || !(payload.get("input") instanceof String input)
            || input.isBlank() || input.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("自动化作业载荷格式无效");
        }
        return input;
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
        String normalized = RAW_CREDENTIAL.matcher(value).replaceAll("[credential-redacted]")
            .replace('\0', ' ').strip();
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
    }
}
