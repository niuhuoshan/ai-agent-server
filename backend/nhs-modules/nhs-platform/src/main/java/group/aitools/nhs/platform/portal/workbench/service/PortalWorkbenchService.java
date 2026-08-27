package group.aitools.nhs.platform.portal.workbench.service;

import group.aitools.nhs.platform.agent.service.AgentApplicationService;
import group.aitools.nhs.platform.agent.web.AgentView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.notification.domain.AgentNotification;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.platform.portal.data.service.PortalDataPortalService;
import group.aitools.nhs.platform.portal.quota.service.PortalQuotaService;
import group.aitools.nhs.platform.scenario.service.ScenarioTemplateApplicationService;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.platform.task.web.TaskView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责门户Workbench相关的业务编排与领域规则处理。
 * Personal workbench projection composed from owner-scoped platform services. */
@Service
public class PortalWorkbenchService {

    private final CurrentPrincipalProvider principalProvider;
    private final NotificationApplicationService notificationService;
    private final TaskQueryService taskService;
    private final AgentApplicationService agentService;
    private final PortalDataPortalService dataPortalService;
    private final PortalQuotaService quotaService;
    private final ScenarioTemplateApplicationService scenarioService;

    public PortalWorkbenchService(
        CurrentPrincipalProvider principalProvider,
        NotificationApplicationService notificationService,
        TaskQueryService taskService,
        AgentApplicationService agentService,
        PortalDataPortalService dataPortalService,
        PortalQuotaService quotaService,
        ScenarioTemplateApplicationService scenarioService
    ) {
        this.principalProvider = principalProvider;
        this.notificationService = notificationService;
        this.taskService = taskService;
        this.agentService = agentService;
        this.dataPortalService = dataPortalService;
        this.quotaService = quotaService;
        this.scenarioService = scenarioService;
    }

    /**
     * 处理{@code home}并返回对应结果。
     *
     * @return 处理结果
     */
    public Map<String, Object> home() {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (!principal.isHuman()) {
            throw new ServiceException("服务账号不能访问个人工作台", HttpStatus.FORBIDDEN);
        }
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> attention = new ArrayList<>();
        String notificationStatus = "ok";
        try {
            for (AgentNotification notification : notificationService.listPage(0, 20, true)) {
                attention.add(notificationItem(notification));
            }
        } catch (RuntimeException ex) {
            notificationStatus = "error";
        }
        if (notificationStatus.equals("ok") && attention.isEmpty()) {
            notificationStatus = "empty";
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        String taskStatus = "ok";
        try {
            for (TaskView task : taskService.list(20)) {
                tasks.add(taskItem(task));
            }
        } catch (RuntimeException ex) {
            taskStatus = "error";
        }
        if (taskStatus.equals("ok") && tasks.isEmpty()) {
            taskStatus = "empty";
        }

        List<Map<String, Object>> agents = new ArrayList<>();
        String agentStatus = "ok";
        try {
            for (AgentView agent : agentService.allowed(20)) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", String.valueOf(agent.id()));
                value.put("name", agent.name());
                value.put("description", agent.description());
                value.put("execution_count", 0);
                value.put("action", "open_agent");
                value.put("target", Map.of("agent_id", String.valueOf(agent.id())));
                agents.add(value);
            }
        } catch (RuntimeException ex) {
            agentStatus = "error";
        }
        if (agentStatus.equals("ok") && agents.isEmpty()) {
            agentStatus = "empty";
        }

        List<Map<String, Object>> scenarios = new ArrayList<>();
        String scenarioStatus = "ok";
        try {
            scenarioService.listTemplates().stream().filter(item -> item.recommended()).limit(6).forEach(item -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", item.id());
                value.put("name", item.name());
                value.put("title", item.name());
                value.put("description", item.description());
                value.put("subtitle", item.description());
                value.put("category", item.category());
                value.put("action", "open_scenario");
                value.put("target", Map.of("template_id", item.id()));
                scenarios.add(value);
            });
        } catch (RuntimeException ex) {
            scenarioStatus = "error";
        }
        if (scenarioStatus.equals("ok") && scenarios.isEmpty()) {
            scenarioStatus = "empty";
        }

        Map<String, Object> portal = Map.of();
        String reportStatus = "ok";
        try {
            portal = dataPortalService.home();
        } catch (RuntimeException ex) {
            reportStatus = "error";
        }
        if (reportStatus.equals("ok") && portal.isEmpty()) {
            reportStatus = "empty";
        }
        List<Map<String, Object>> latestResults = reportActivities(portal.get("recent_analysis"));
        List<Map<String, Object>> resumeItems = latestResults.stream()
            .filter(item -> "open_conversation".equals(item.get("action")))
            .toList();
        latestResults = latestResults.stream()
            .filter(item -> "open_report".equals(item.get("action")) || "open_digest".equals(item.get("action")))
            .toList();

        attention.addAll(tasks.stream().filter(item -> Boolean.TRUE.equals(item.get("needs_attention"))).toList());
        attention.sort(Comparator.comparing(item -> String.valueOf(item.get("occurred_at")), Comparator.reverseOrder()));
        List<Map<String, Object>> recentTasks = tasks.stream().limit(4).toList();
        Map<String, Object> nextScheduled = tasks.stream()
            .filter(item -> item.get("next_run_at") != null)
            .min(Comparator.comparing(item -> String.valueOf(item.get("next_run_at"))))
            .orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", attention.isEmpty()
            ? (latestResults.isEmpty() && resumeItems.isEmpty() && recentTasks.isEmpty() ? "new_user" : "quiet")
            : "active");
        result.put("attention", attention.stream().limit(5).toList());
        result.put("latest_results", latestResults.stream().limit(4).toList());
        result.put("resume_items", resumeItems.stream().limit(4).toList());
        result.put("recent_tasks", recentTasks);
        result.put("favorite_agents", agents.stream().limit(6).toList());
        result.put("recommended_scenarios", scenarios);
        result.put("running_items", List.of());
        result.put("next_scheduled_item", nextScheduled);
        result.put("personal_resources", personalResources(portal, tasks.size(), notificationCount(notificationStatus)));
        Map<String, Object> sourceStatus = new LinkedHashMap<>();
        sourceStatus.put("notifications", notificationStatus);
        sourceStatus.put("tasks", taskStatus);
        sourceStatus.put("reports", reportStatus);
        sourceStatus.put("conversations", reportStatus);
        sourceStatus.put("agents", agentStatus);
        sourceStatus.put("scenarios", scenarioStatus);
        sourceStatus.put("running", "empty");
        result.put("source_status", sourceStatus);
        result.put("generated_at", now.toString());
        return result;
    }

    /**
     * 处理通知Item并返回对应结果。
     *
     * @param notification 通知参数
     * @return 处理结果
     */
    private Map<String, Object> notificationItem(AgentNotification notification) {
        String category = notification.getCategory() == null ? "notification" : notification.getCategory();
        String action = "task".equals(category) ? "open_task_log" : "open_notification";
        if ("report".equals(notification.getResourceType()) || "saved_report".equals(notification.getResourceType())) {
            action = "open_report";
        }
        Map<String, Object> target = new LinkedHashMap<>();
        if (notification.getResourceId() != null) {
            target.put("resource_id", notification.getResourceId());
            if (action.equals("open_task_log")) {
                target.put("task_id", notification.getResourceId());
            }
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", "notification:" + notification.getId());
        value.put("business_key", notification.getEventKey());
        value.put("type", category);
        value.put("title", notification.getTitle());
        value.put("subtitle", notification.getContent());
        value.put("occurred_at", notification.getCreatedAt());
        value.put("status", "unread");
        value.put("severity", "error".equals(notification.getLevel()) ? "critical" : notification.getLevel());
        value.put("action", action);
        value.put("target", target);
        return value;
    }

    /**
     * 处理任务Item并返回对应结果。
     *
     * @param task 任务参数
     * @return 处理结果
     */
    private Map<String, Object> taskItem(TaskView task) {
        boolean failed = "failed".equalsIgnoreCase(task.status()) || "error".equalsIgnoreCase(task.status());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", "task:" + task.id());
        value.put("business_key", "task:" + task.id() + ":" + task.status());
        value.put("type", failed ? "task_failure" : "scheduled_task");
        value.put("title", task.title());
        value.put("subtitle", failed ? "任务执行异常" : "任务 · " + task.status());
        value.put("occurred_at", task.startAt());
        // Platform TaskView exposes a plan start, not the scheduler's next
        // fire time; keep this unset instead of presenting it as a schedule.
        value.put("next_run_at", null);
        value.put("status", task.status());
        value.put("severity", failed ? "critical" : "info");
        value.put("action", failed ? "open_task_log" : "open_task");
        value.put("target", Map.of("task_id", task.id()));
        value.put("needs_attention", failed);
        return value;
    }

    /**
     * 处理报表Activities并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 符合条件的数据集合
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> reportActivities(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance)
            .map(item -> (Map<String, Object>) new LinkedHashMap<>((Map<String, Object>) item))
            .toList();
    }

    /**
     * 处理{@code personalResources}并返回对应结果。
     *
     * @param portal 门户参数
     * @param taskCount 任务Count参数
     * @param unreadNotifications {@code unreadNotifications}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> personalResources(
        Map<String, Object> portal,
        int taskCount,
        long unreadNotifications
    ) {
        Map<String, Object> summary = portal.get("report_summary") instanceof Map<?, ?> raw
            ? cast(raw) : Map.of();
        Number data = number(summary.get("items"));
        Map<String, Object> quota;
        try {
            quota = quotaService.myQuota();
        } catch (RuntimeException ignored) {
            quota = Map.of();
        }
        Number tokens = number(quota.get("used_tokens"));
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(resource("memory", 0, "我的记忆", "条", "memory", "empty"));
        result.add(resource("tokens", tokens.longValue(), "我的 Token", "本月", "tokens",
            tokens.longValue() > 0 ? "ok" : "empty"));
        result.add(resource("data", data.longValue(), "我的数据门户", "份报表", "data", data.longValue() > 0 ? "ok" : "empty"));
        result.add(resource("skills", 0, "我的技能", "个", "skills", "empty"));
        result.add(resource("mcp", 0, "我的 MCP", "个服务", "mcp", "empty"));
        result.add(resource("tasks", taskCount, "我的任务", "个", "tasks",
            taskCount > 0 ? "ok" : "empty"));
        result.add(resource("inbox", unreadNotifications, "我的站内消息", "条未读", "inbox",
            unreadNotifications > 0 ? "ok" : "empty"));
        return result;
    }

    /**
     * 处理通知Count并返回对应结果。
     *
     * @param status 目标状态
     * @return 处理结果
     */
    private long notificationCount(String status) {
        if (!"ok".equals(status)) {
            return 0L;
        }
        try {
            return notificationService.unreadCount();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    /**
     * 处理资源并返回对应结果。
     *
     * @param key {@code key}参数
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @param unit {@code unit}参数
     * @param tab {@code tab}参数
     * @param status 目标状态
     * @return 处理结果
     */
    private Map<String, Object> resource(
        String key, long value, String label, String unit, String tab, String status
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("label", label);
        result.put("value", Math.max(0L, value));
        result.put("unit", unit);
        result.put("tab", tab);
        result.put("status", status);
        return result;
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    /**
     * 处理{@code cast}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }
}
