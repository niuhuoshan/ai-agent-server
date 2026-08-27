package group.aitools.nhs.platform.nhs.portal.chatbi;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 负责门户对话BI任务Plan相关的业务编排与领域规则处理。
 * Builds and persists the small dependency graph used by mixed ChatBI requests. */
@Service
public class PortalChatBITaskPlanService {

    private static final Pattern SEQUENCE = Pattern.compile(
        "(?:\\s*[，,；;]\\s*(?:然后|随后|接着|之后|再)\\s*|\\s+(?:然后|随后|接着|之后|再)\\s*)"
    );

    private final PortalChatBIRecoveryMapper mapper;
    private final PlatformIdGenerator idGenerator;
    private final AgentAuditEventMapper auditMapper;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code PortalChatBITaskPlanService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param auditMapper 审计Mapper参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public PortalChatBITaskPlanService(
        PortalChatBIRecoveryMapper mapper,
        PlatformIdGenerator idGenerator,
        AgentAuditEventMapper auditMapper,
        JsonMapper jsonMapper
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.auditMapper = auditMapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code start}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param datasetId 资源标识
     * @param question 追问参数
     * @return 处理结果
     */
    public Plan start(CurrentPrincipal principal, Long datasetId, String question) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<Task> tasks = buildTasks(question);
        if (tasks.isEmpty() || !tasks.stream().allMatch(task -> supported(task.operation()))) {
            return null;
        }
        String planKey = "plan_" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        AgentChatBITaskPlan header = new AgentChatBITaskPlan();
        header.setId(idGenerator.nextId());
        header.setPlanKey(planKey);
        header.setOwnerId(principal.id());
        header.setDatasetId(datasetId);
        header.setRequestQuestion(question);
        header.setStatus("pending");
        header.setTaskCount(tasks.size());
        header.setCreatedAt(now);
        mapper.insertTaskPlan(header);
        for (Task task : tasks) {
            AgentChatBITaskPlanItem item = new AgentChatBITaskPlanItem();
            item.setId(idGenerator.nextId());
            item.setPlanId(header.getId());
            item.setTaskKey(task.taskKey());
            item.setSequenceNo(task.sequenceNo());
            item.setOperation(task.operation());
            item.setQueryText(task.query());
            item.setDependsOnJson(jsonMapper.writeValueAsString(task.dependsOn()));
            item.setStatus("pending");
            item.setCreatedAt(now);
            mapper.insertTaskPlanItem(item);
        }
        audit(
            principal, "create", header.getId(),
            "ChatBI任务计划创建，节点数=" + tasks.size()
        );
        Plan plan = new Plan(header, tasks);
        return plan;
    }

    /**
     * 处理{@code emitPlan}相关逻辑。
     *
     * @param plan {@code plan}参数
     * @param sink {@code sink}参数
     */
    public void emitPlan(Plan plan, PortalChatBIProgressSink sink) {
        if (plan != null) planEvent(plan, "pending", null, sink);
    }

    /**
     * 处理bind会话相关逻辑。
     *
     * @param plan {@code plan}参数
     * @param conversationId 资源标识
     */
    public void bindConversation(Plan plan, Long conversationId) {
        if (plan == null || conversationId == null) return;
        mapper.bindPlanConversation(plan.header().getId(), plan.header().getOwnerId(), conversationId);
        plan.header().setConversationId(conversationId);
    }

    /**
     * 处理{@code markRunning}相关逻辑。
     *
     * @param plan {@code plan}参数
     * @param task 任务参数
     * @param traceId 资源标识
     * @param sink {@code sink}参数
     */
    public void markRunning(Plan plan, Task task, String traceId, PortalChatBIProgressSink sink) {
        if (plan == null || task == null) return;
        LocalDateTime now = LocalDateTime.now();
        mapper.markPlanRunning(plan.header().getId(), plan.header().getOwnerId(), task.taskKey(), now);
        mapper.markTaskRunning(plan.header().getId(), task.taskKey(), traceId, now);
        task.status = "running";
        task.traceId = traceId;
        plan.header().setStatus("running");
        plan.header().setCurrentTaskKey(task.taskKey());
        emitStatus(plan, task, sink);
    }

    /**
     * 处理finish任务相关逻辑。
     *
     * @param plan {@code plan}参数
     * @param task 任务参数
     * @param status 目标状态
     * @param resultQueryId 资源标识
     * @param error {@code error}参数
     * @param sink {@code sink}参数
     */
    public void finishTask(
        Plan plan,
        Task task,
        String status,
        Long resultQueryId,
        String error,
        PortalChatBIProgressSink sink
    ) {
        if (plan == null || task == null) return;
        mapper.finishTask(
            plan.header().getId(), task.taskKey(), status, resultQueryId,
            bounded(error, 1000), LocalDateTime.now()
        );
        task.status = status;
        task.resultQueryId = resultQueryId;
        task.error = bounded(error, 1000);
        emitStatus(plan, task, sink);
    }

    /**
     * 处理{@code finishPlan}相关逻辑。
     *
     * @param plan {@code plan}参数
     * @param status 目标状态
     * @param sink {@code sink}参数
     */
    public void finishPlan(
        Plan plan,
        String status,
        PortalChatBIProgressSink sink
    ) {
        if (plan == null) return;
        mapper.finishTaskPlan(
            plan.header().getId(), plan.header().getOwnerId(), status, LocalDateTime.now()
        );
        plan.header().setStatus(status);
        plan.header().setCurrentTaskKey(null);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "chatbi_task_status");
        event.put("data", Map.of(
            "plan_id", plan.header().getPlanKey(),
            "status", status,
            "tasks", taskViews(plan.tasks())
        ));
        persistAndEmit(plan, event, sink);
        audit(
            new CurrentPrincipal(plan.header().getOwnerId(), "", group.aitools.nhs.platform.iam.domain.PrincipalType.HUMAN, java.util.Set.of()),
            "finish", plan.header().getId(), "ChatBI任务计划状态=" + status
        );
    }

    /**
     * 处理{@code viewForOwner}并返回对应结果。
     *
     * @param planKey {@code planKey}参数
     * @param userId 资源标识
     * @return 处理结果
     */
    public Map<String, Object> viewForOwner(String planKey, Long userId) {
        AgentChatBITaskPlan plan = mapper.selectOwnedTaskPlan(planKey, userId);
        if (plan == null) {
            throw new ServiceException("ChatBI 任务计划不存在", 404);
        }
        return view(plan, mapper.selectTaskPlanItems(plan.getId()));
    }

    /**
     * 处理viewBy结果并返回对应结果。
     *
     * @param queryId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    public Map<String, Object> viewByResult(Long queryId, Long userId) {
        AgentChatBITaskPlan plan = mapper.selectOwnedTaskPlanByResult(queryId, userId);
        return plan == null ? null : view(plan, mapper.selectTaskPlanItems(plan.getId()));
    }

    /**
 * 处理{@code events}并返回对应结果。
 * Returns owner-scoped durable events after a global cursor for SSE recovery. */
    public Map<String, Object> events(String planKey, Long userId, Long afterCursor, int requestedLimit) {
        AgentChatBITaskPlan plan = mapper.selectOwnedTaskPlan(planKey, userId);
        if (plan == null) {
            throw new ServiceException("ChatBI 任务计划不存在", 404);
        }
        long after = afterCursor == null ? 0L : Math.max(0L, afterCursor);
        int limit = Math.min(Math.max(requestedLimit, 1), 200);
        List<AgentChatBITaskPlanEvent> persisted = mapper.selectOwnedTaskPlanEvents(
            plan.getId(), userId, after, limit
        );
        List<Map<String, Object>> values = persisted.stream().map(this::eventView).toList();
        long next = persisted.isEmpty() ? after : persisted.getLast().getCursor();
        boolean hasMore = mapper.hasMoreTaskPlanEvents(plan.getId(), userId, after, limit) != null;
        return Map.of(
            "plan_id", plan.getPlanKey(),
            "events", values,
            "next_cursor", next,
            "has_more", hasMore
        );
    }

    /**
     * 构建{@code Tasks}。
     *
     * @param question 追问参数
     * @return 符合条件的数据集合
     */
    private List<Task> buildTasks(String question) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        String raw = question == null ? "" : question.strip();
        if (raw.isBlank() || raw.length() > 4000) return List.of();
        String[] parts = SEQUENCE.split(raw);
        List<Task> tasks = new ArrayList<>();
        for (String part : parts) {
            String text = part.strip();
            if (text.isBlank()) continue;
            text = text.replaceFirst("^先(?:帮我)?", "").strip();
            if (text.isBlank()) continue;
            int sequence = tasks.size() + 1;
            String key = "task_" + sequence + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            List<String> dependsOn = tasks.isEmpty() ? List.of() : List.of(tasks.get(tasks.size() - 1).taskKey());
            tasks.add(new Task(key, sequence, operation(text), text, dependsOn));
            if (tasks.size() >= 12) return List.of();
        }
        return List.copyOf(tasks.isEmpty() ? List.of(new Task(
            "task_1_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6),
            1, operation(raw), raw, List.of()
        )) : tasks);
    }

    /**
     * 处理操作并返回对应结果。
     *
     * @param text 待处理内容
     * @return 处理结果
     */
    private String operation(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        if (containsAny(value, "图表", "柱状图", "折线图", "饼图", "可视化")) return "present";
        if (containsAny(value, "分析", "原因", "归因", "总结", "结论", "变化")) return "analyze";
        return "query";
    }

    /**
     * 处理{@code supported}并返回对应结果。
     *
     * @param operation 操作参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean supported(String operation) {
        return List.of("query", "analyze", "present").contains(operation);
    }

    /**
     * 处理{@code emitStatus}相关逻辑。
     *
     * @param plan {@code plan}参数
     * @param task 任务参数
     * @param sink {@code sink}参数
     */
    private void emitStatus(Plan plan, Task task, PortalChatBIProgressSink sink) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "chatbi_task_status");
        event.put("data", Map.of(
            "plan_id", plan.header().getPlanKey(),
            "task_id", task.taskKey(),
            "operation", task.operation(),
            "status", task.status(),
            "result_query_id", task.resultQueryId() == null ? "" : task.resultQueryId(),
            "error", task.error() == null ? "" : task.error()
        ));
        persistAndEmit(plan, event, sink);
    }

    /**
     * 处理plan事件相关逻辑。
     *
     * @param plan {@code plan}参数
     * @param status 目标状态
     * @param task 任务参数
     * @param sink {@code sink}参数
     */
    private void planEvent(Plan plan, String status, Task task, PortalChatBIProgressSink sink) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "chatbi_task_plan");
        event.put("data", Map.of(
            "version", 1,
            "plan_id", plan.header().getPlanKey(),
            "status", status,
            "tasks", taskViews(plan.tasks())
        ));
        persistAndEmit(plan, event, sink);
    }

    /**
     * 处理事件View并返回对应结果。
     *
     * @param event 事件参数
     * @return 处理结果
     */
    private Map<String, Object> eventView(AgentChatBITaskPlanEvent event) {
        try {
            JsonNode node = jsonMapper.readTree(event.getPayloadJson());
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("ChatBI 任务计划事件载荷无效");
            }
            Map<String, Object> value = new LinkedHashMap<>(jsonMapper.convertValue(node, Map.class));
            value.put("cursor", event.getCursor());
            value.put("created_at", event.getCreatedAt());
            return value;
        } catch (RuntimeException exception) {
            throw new ServiceException("ChatBI 任务计划事件载荷无法读取", exception);
        }
    }

    /**
     * 处理{@code persistAndEmit}相关逻辑。
     *
     * @param plan {@code plan}参数
     * @param event 事件参数
     * @param sink {@code sink}参数
     */
    private void persistAndEmit(Plan plan, Map<String, Object> event, PortalChatBIProgressSink sink) {
        try {
            AgentChatBITaskPlanEvent persisted = new AgentChatBITaskPlanEvent();
            persisted.setId(idGenerator.nextId());
            persisted.setPlanId(plan.header().getId());
            persisted.setOwnerId(plan.header().getOwnerId());
            persisted.setEventType(String.valueOf(event.getOrDefault("type", "message")));
            persisted.setPayloadJson(jsonMapper.writeValueAsString(event));
            persisted.setCreatedAt(LocalDateTime.now());
            if (mapper.insertTaskPlanEvent(persisted) != 1) {
                throw new ServiceException("ChatBI 任务计划事件写入失败", 409);
            }
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ServiceException("ChatBI 任务计划事件无法持久化", exception);
        }
        safeEmit(sink, event);
    }

    /**
     * 处理任务Views并返回对应结果。
     *
     * @param tasks {@code tasks}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> taskViews(List<Task> tasks) {
        return tasks.stream().map(task -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("task_id", task.taskKey());
            item.put("operation", task.operation());
            item.put("query", task.query());
            item.put("depends_on", task.dependsOn());
            item.put("status", task.status());
            if (task.resultQueryId() != null) item.put("result_query_id", task.resultQueryId());
            if (task.error() != null) item.put("error", task.error());
            return item;
        }).toList();
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param plan {@code plan}参数
     * @param items {@code items}参数
     * @return 处理结果
     */
    private Map<String, Object> view(AgentChatBITaskPlan plan, List<AgentChatBITaskPlanItem> items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plan_id", plan.getPlanKey());
        result.put("status", plan.getStatus());
        result.put("dataset_id", plan.getDatasetId());
        result.put("conversation_id", plan.getConversationId());
        result.put("question", plan.getRequestQuestion());
        result.put("created_at", plan.getCreatedAt());
        result.put("started_at", plan.getStartedAt());
        result.put("finished_at", plan.getFinishedAt());
        result.put("tasks", items.stream().map(this::itemView).toList());
        return result;
    }

    /**
     * 处理{@code itemView}并返回对应结果。
     *
     * @param item {@code item}参数
     * @return 处理结果
     */
    private Map<String, Object> itemView(AgentChatBITaskPlanItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", item.getTaskKey());
        result.put("operation", item.getOperation());
        result.put("query", item.getQueryText());
        result.put("depends_on", parseList(item.getDependsOnJson()));
        result.put("status", item.getStatus());
        result.put("trace_id", item.getTraceId());
        result.put("result_query_id", item.getResultQueryId());
        result.put("error", item.getErrorSummary());
        result.put("started_at", item.getStartedAt());
        result.put("finished_at", item.getFinishedAt());
        return result;
    }

    /**
     * 处理{@code parseList}并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 符合条件的数据集合
     */
    private List<String> parseList(String json) {
        try {
            JsonNode node = jsonMapper.readTree(json == null ? "[]" : json);
            if (node != null && node.isArray()) {
                List<String> values = new ArrayList<>();
                node.forEach(item -> values.add(item.asText()));
                return List.copyOf(values);
            }
        } catch (RuntimeException ignored) {
            // A corrupt dependency snapshot is presented as empty; execution never trusts this view.
        }
        return List.of();
    }

    /**
     * 处理{@code safeEmit}相关逻辑。
     *
     * @param sink {@code sink}参数
     * @param event 事件参数
     */
    private void safeEmit(PortalChatBIProgressSink sink, Map<String, Object> event) {
        if (sink == null) return;
        try {
            sink.emit(event);
        } catch (RuntimeException ignored) {
            // The query facts remain durable when a browser closes the stream.
        }
    }

    /**
     * 处理审计相关逻辑。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param resourceId 资源标识
     * @param summary {@code summary}参数
     */
    private void audit(CurrentPrincipal principal, String action, Long resourceId, String summary) {
        if (principal == null) return;
        auditMapper.insertEvent(
            idGenerator.nextId(), "user", principal.id(), action,
            "chatbi_task_plan", resourceId, null, "success", "owner", bounded(summary, 500), LocalDateTime.now()
        );
    }

    /**
     * 处理{@code containsAny}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param words {@code words}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @return 处理结果
     */
    private String bounded(String value, int max) {
        String normalized = value == null ? "" : value.replace('\0', ' ').replaceAll("[\\r\\n]+", " ").strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    /**
     * 表示{@code Plan}相关的领域对象。
     */
    public final class Plan {
        private final AgentChatBITaskPlan header;
        private final List<Task> tasks;

        /**
         * 创建 {@code Plan} 实例并初始化所需依赖。
         *
         * @param header {@code header}参数
         * @param tasks {@code tasks}参数
         */
        private Plan(AgentChatBITaskPlan header, List<Task> tasks) {
            this.header = header;
            this.tasks = tasks;
        }

        /**
         * 处理{@code header}并返回对应结果。
         *
         * @return 处理结果
         */
        public AgentChatBITaskPlan header() { return header; }
        /**
         * 处理{@code tasks}并返回对应结果。
         *
         * @return 符合条件的数据集合
         */
        public List<Task> tasks() { return tasks; }
        /**
         * 处理{@code planKey}并返回对应结果。
         *
         * @return 处理结果
         */
        public String planKey() { return header.getPlanKey(); }
    }

    /**
     * 表示任务相关的领域对象。
     */
    public static final class Task {
        private final String taskKey;
        private final int sequenceNo;
        private final String operation;
        private final String query;
        private final List<String> dependsOn;
        private String status = "pending";
        private String traceId;
        private Long resultQueryId;
        private String error;

        /**
         * 创建 {@code Task} 实例并初始化所需依赖。
         *
         * @param taskKey 任务Key参数
         * @param sequenceNo 起始位置或序号
         * @param operation 操作参数
         * @param query 查询参数
         * @param dependsOn {@code dependsOn}参数
         */
        private Task(String taskKey, int sequenceNo, String operation, String query, List<String> dependsOn) {
            this.taskKey = taskKey;
            this.sequenceNo = sequenceNo;
            this.operation = operation;
            this.query = query;
            this.dependsOn = List.copyOf(dependsOn);
        }

        /**
         * 处理任务Key并返回对应结果。
         *
         * @return 处理结果
         */
        public String taskKey() { return taskKey; }
        /**
         * 处理{@code sequenceNo}并返回对应结果。
         *
         * @return 处理结果
         */
        public int sequenceNo() { return sequenceNo; }
        /**
         * 处理操作并返回对应结果。
         *
         * @return 处理结果
         */
        public String operation() { return operation; }
        /**
         * 获取查询。
         *
         * @return 处理结果
         */
        public String query() { return query; }
        /**
         * 处理{@code dependsOn}并返回对应结果。
         *
         * @return 符合条件的数据集合
         */
        public List<String> dependsOn() { return dependsOn; }
        /**
         * 处理{@code status}并返回对应结果。
         *
         * @return 处理结果
         */
        public String status() { return status; }
        /**
         * 处理链路追踪Id并返回对应结果。
         *
         * @return 处理结果
         */
        public String traceId() { return traceId; }
        /**
         * 处理结果查询Id并返回对应结果。
         *
         * @return 处理结果
         */
        public Long resultQueryId() { return resultQueryId; }
        /**
         * 处理{@code error}并返回对应结果。
         *
         * @return 处理结果
         */
        public String error() { return error; }
    }
}
