package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.platform.automation.service.AutomationApplicationService;
import group.aitools.nhs.platform.automation.web.AutomationFireView;
import group.aitools.nhs.platform.automation.web.AutomationTriggerView;
import group.aitools.nhs.platform.automation.web.CreateAutomationTriggerRequest;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.identity.service.ServiceAccountPrincipalResolver;
import group.aitools.nhs.platform.task.service.TaskApplicationService;
import group.aitools.nhs.platform.task.web.CreateTaskRequest;
import group.aitools.nhs.platform.task.web.TaskMutationResult;
import group.aitools.nhs.platform.task.web.TaskResourceRequest;
import group.aitools.nhs.platform.task.web.TaskView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 负责任务ControlBuiltin相关的业务编排与领域规则处理。
 * Durable Nhs task-control builtins backed by formal tasks and automation triggers. */
@Service
public class TaskControlBuiltinService {

    private static final Set<String> RESOURCE_TYPES = Set.of(
        "agent_version", "tool", "skill", "knowledge_base", "data_source",
        "dataset", "artifact", "connector"
    );
    private static final Set<String> NOTIFICATION_CHANNELS = Set.of(
        "portal", "dingtalk", "wechat_work", "email"
    );

    private final TaskApplicationService taskService;
    private final AutomationApplicationService automationService;
    private final ServiceAccountPrincipalResolver accountResolver;

    /**
     * 创建 {@code TaskControlBuiltinService} 实例并初始化所需依赖。
     *
     * @param taskService 任务Service参数
     * @param automationService 自动化Service参数
     * @param accountResolver 账户Resolver参数
     */
    public TaskControlBuiltinService(
        TaskApplicationService taskService,
        AutomationApplicationService automationService,
        ServiceAccountPrincipalResolver accountResolver
    ) {
        this.taskService = taskService;
        this.automationService = automationService;
        this.accountResolver = accountResolver;
    }

    /**
     * 创建并保存{@code Recurring}。
     *
     * @param runtime 运行时参数
     * @param actor {@code actor}参数
     * @param requestedName 名称
     * @param requestedCron {@code requestedCron}参数
     * @param requestedPrompt requested提示词参数
     * @param requestedNotificationChannels requested通知Channels参数
     * @param requestedTimezone {@code requestedTimezone}参数
     * @param requestedServiceAccountId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createRecurring(
        AgentRunRequest runtime,
        CurrentPrincipal actor,
        String requestedName,
        String requestedCron,
        String requestedPrompt,
        List<String> requestedNotificationChannels,
        String requestedTimezone,
        Long requestedServiceAccountId
    ) {
        AgentRunRequest requiredRuntime = Objects.requireNonNull(
            runtime, "runtime must not be null"
        );
        CurrentPrincipal requiredActor = Objects.requireNonNull(actor, "actor must not be null");
        String name = text(requestedName, "周期任务名称", 128);
        String cron = text(requestedCron, "Cron表达式", 128);
        String prompt = text(requestedPrompt, "周期任务指令", 12000);
        String timezone = requestedTimezone == null || requestedTimezone.isBlank()
            ? "Asia/Shanghai" : text(requestedTimezone, "Cron时区", 64);
        List<String> channels = notificationChannels(requestedNotificationChannels);
        CurrentPrincipal servicePrincipal = accountResolver.requireOwnedForAutomation(
            requiredActor, requestedServiceAccountId
        );

        String idempotencyKey = "builtin.recurring." + ContentHashing.sha256(
            requiredActor.type().name() + ":" + requiredActor.id() + ":"
                + requiredRuntime.executionKey().executionId() + ":" + name + ":"
                + cron + ":" + prompt + ":" + servicePrincipal.id()
        ).substring(0, 48);
        Map<String, Object> origin = Map.of(
            "source", "create_recurring_task",
            "executionId", requiredRuntime.executionKey().executionId(),
            "traceId", requiredRuntime.executionKey().traceId()
        );
        CreateTaskRequest createTask = new CreateTaskRequest(
            idempotencyKey,
            name,
            prompt,
            "由智能体内置工具创建的周期任务",
            null,
            requiredRuntime.agentVersionId(),
            null,
            "enterprise_shared",
            "general",
            "single_agent",
            "L3_recurring_task",
            "R1",
            "human",
            0,
            0,
            null,
            origin,
            frozenResources(requiredRuntime),
            Map.of(),
            Map.of("prompt", prompt),
            Map.of(),
            origin,
            List.of("recurring", "agent-created")
        );
        TaskMutationResult task = taskService.createAs(requiredActor, createTask);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("createdByBuiltin", "create_recurring_task");
        config.put("notification_channels", channels);
        AutomationTriggerView trigger = automationService.createForTaskOperator(
            requiredActor,
            new CreateAutomationTriggerRequest(
                "builtin.recurring." + task.task().id(), name, "cron",
                task.task().id(), task.taskVersionId(), servicePrincipal.id(),
                cron, timezone, "fire_once", 1, 3, prompt, Map.copyOf(config)
            )
        );
        TaskView scheduled = taskService.updateStatusAs(
            requiredActor, task.task().id(), "scheduled"
        );

        Map<String, Object> result = triggerResult(trigger);
        result.put("task_id", scheduled.id());
        result.put("task_status", scheduled.status());
        result.put("replayed", task.replayed());
        result.put("notification_channels", channels);
        return result;
    }

    /**
     * 处理{@code pause}并返回对应结果。
     *
     * @param actor {@code actor}参数
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> pause(CurrentPrincipal actor, Long taskId) {
        return triggerResult(automationService.changeRecurringStatusAs(actor, taskId, "paused"));
    }

    /**
     * 处理{@code start}并返回对应结果。
     *
     * @param actor {@code actor}参数
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(CurrentPrincipal actor, Long taskId) {
        return triggerResult(automationService.changeRecurringStatusAs(actor, taskId, "active"));
    }

    /**
     * 判断{@code cel}是否满足要求。
     *
     * @param actor {@code actor}参数
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancel(CurrentPrincipal actor, Long taskId) {
        AutomationTriggerView trigger = automationService.changeRecurringStatusAs(
            actor, taskId, "archived"
        );
        TaskView task = taskService.cancelRecurringAs(actor, taskId);
        Map<String, Object> result = triggerResult(trigger);
        result.put("task_status", task.status());
        result.put("cancelled", true);
        return result;
    }

    /**
     * 执行{@code Manually}相关的处理流程。
     *
     * @param runtime 运行时参数
     * @param actor {@code actor}参数
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> runManually(
        AgentRunRequest runtime,
        CurrentPrincipal actor,
        Long taskId
    ) {
        AgentRunRequest requiredRuntime = Objects.requireNonNull(
            runtime, "runtime must not be null"
        );
        String key = "builtin-manual:" + ContentHashing.sha256(
            requiredRuntime.executionKey().executionId() + ":" + taskId
        );
        AutomationFireView fire = automationService.manualRunRecurringAs(actor, taskId, key);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", taskId);
        result.put("trigger_id", fire.triggerId());
        result.put("fire_id", fire.id());
        result.put("job_id", fire.jobId());
        result.put("status", fire.status());
        result.put("replayed", fire.replayed());
        return result;
    }

    /**
     * 处理{@code frozenResources}并返回对应结果。
     *
     * @param runtime 运行时参数
     * @return 符合条件的数据集合
     */
    private List<TaskResourceRequest> frozenResources(AgentRunRequest runtime) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Object rawSnapshot = runtime.attributes().get("taskResourceSnapshot");
        if (!(rawSnapshot instanceof Map<?, ?> snapshot)
            || !(snapshot.get("agentVersionId") instanceof Number agentVersionId)
            || agentVersionId.longValue() != runtime.agentVersionId()) {
            throw new SecurityException("运行快照缺少匹配的Agent版本授权");
        }
        Object rawResources = snapshot.get("resources");
        if (!(rawResources instanceof List<?> resources)) {
            throw new SecurityException("运行快照缺少任务资源授权");
        }
        LinkedHashMap<String, TaskResourceRequest> result = new LinkedHashMap<>();
        for (Object value : resources) {
            if (!(value instanceof Map<?, ?> resource)
                || !(resource.get("resourceType") instanceof String rawType)
                || !(resource.get("resourceId") instanceof Number rawId)
                || rawId.longValue() <= 0 || rawId.doubleValue() != rawId.longValue()) {
                throw new SecurityException("运行快照包含无效任务资源");
            }
            String type = rawType.strip().toLowerCase(Locale.ROOT);
            if (!RESOURCE_TYPES.contains(type)) {
                throw new SecurityException("运行快照包含不支持的任务资源类型");
            }
            String permission = resourcePermission(type, resource.get("permission"));
            Long id = rawId.longValue();
            String key = type + ":" + id + ":" + permission;
            result.putIfAbsent(key, new TaskResourceRequest(
                type, id, permission,
                !Boolean.FALSE.equals(resource.get("required")),
                "agent",
                Map.of(
                    "source", "frozen_runtime_snapshot",
                    "executionId", runtime.executionKey().executionId()
                )
            ));
        }
        return List.copyOf(result.values());
    }

    /**
     * 处理资源权限并返回对应结果。
     *
     * @param type 业务类型
     * @param rawPermission raw权限参数
     * @return 处理结果
     */
    private String resourcePermission(String type, Object rawPermission) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String permission = rawPermission instanceof String value
            ? value.strip().toLowerCase(Locale.ROOT) : "";
        if ("tool".equals(type) && "invoke".equals(permission)) {
            return "use";
        }
        if ("skill".equals(type)) {
            return "use";
        }
        if ("knowledge_base".equals(type) && permission.isBlank()) {
            return "read";
        }
        if (!Set.of("read", "query", "use", "write", "admin").contains(permission)) {
            throw new SecurityException("运行快照包含无效任务资源权限");
        }
        return permission;
    }

    /**
     * 处理通知Channels并返回对应结果。
     *
     * @param requested {@code requested}参数
     * @return 符合条件的数据集合
     */
    private List<String> notificationChannels(List<String> requested) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        if (requested.size() > NOTIFICATION_CHANNELS.size()) {
            throw badRequest("通知渠道数量无效");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : requested) {
            String channel = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            if (!NOTIFICATION_CHANNELS.contains(channel)) {
                throw badRequest("通知渠道无效：" + channel);
            }
            result.add(channel);
        }
        return List.copyOf(result);
    }

    /**
     * 处理trigger结果并返回对应结果。
     *
     * @param trigger {@code trigger}参数
     * @return 处理结果
     */
    private Map<String, Object> triggerResult(AutomationTriggerView trigger) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", trigger.taskId());
        result.put("trigger_id", trigger.id());
        result.put("name", trigger.name());
        result.put("cron", trigger.cronExpression());
        result.put("timezone", trigger.timezone());
        result.put("status", trigger.status());
        result.put("revision", trigger.revisionNo());
        if (trigger.nextRunAt() != null) {
            result.put("next_run_at", trigger.nextRunAt());
        }
        return result;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private String text(String value, String label, int maximum) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > maximum
            || normalized.indexOf('\0') >= 0) {
            throw badRequest(label + "为空、过长或包含非法字符");
        }
        return normalized;
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
