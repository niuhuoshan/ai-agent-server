package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.domain.AgentExecutionTimelineSnapshot;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentExecutionTimelineSnapshotMapper;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.execution.web.ExecutionTimelineView;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 负责执行时间线快照相关的业务编排与领域规则处理。
 * Builds and persists a bounded semantic timeline from the event fact stream. */
@Service
public class ExecutionTimelineSnapshotService {

    private static final int PAGE_SIZE = 200;
    private static final int MAX_EVENTS = 1_000;
    private static final TypeReference<List<ExecutionEventView>> ITEMS = new TypeReference<>() {
    };

    private final ExecutionEventQueryService eventQuery;
    private final ExecutionTraceAggregationService aggregation;
    private final AgentExecutionTimelineSnapshotMapper mapper;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code ExecutionTimelineSnapshotService} 实例并初始化所需依赖。
     *
     * @param eventQuery 事件查询参数
     * @param aggregation {@code aggregation}参数
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public ExecutionTimelineSnapshotService(
        ExecutionEventQueryService eventQuery,
        ExecutionTraceAggregationService aggregation,
        AgentExecutionTimelineSnapshotMapper mapper,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper
    ) {
        this.eventQuery = eventQuery;
        this.aggregation = aggregation;
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理会话链路追踪并返回对应结果。
     *
     * @param traceId 资源标识
     * @return 处理结果
     */
    public ExecutionTimelineView conversationTrace(String traceId) {
        ExecutionEventQueryService.ConversationTrace trace = eventQuery.traceConversation(traceId);
        List<ExecutionEventView> items = aggregation.aggregate(trace.events());
        Long runId = trace.events().stream()
            .map(ExecutionEventView::runId)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        return persist(
            trace.traceId(),
            trace.turn().getConversationId(),
            null,
            runId,
            items
        );
    }

    /**
     * 处理任务Run并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    public ExecutionTimelineView taskRun(Long taskId, Long runId) {
        List<ExecutionEventView> events = readAll(eventQuery.taskRunReader(taskId, runId));
        if (events.isEmpty()) {
            return new ExecutionTimelineView(
                "task-" + taskId + "-run-" + runId, null, taskId, runId,
                ContentHashing.sha256("[]"), LocalDateTime.now(), false, List.of()
            );
        }
        String traceId = events.getFirst().traceId();
        return persist(traceId, events.getFirst().conversationId(), taskId, runId,
            aggregation.aggregate(events));
    }

    /**
     * 处理{@code cached}并返回对应结果。
     *
     * @param traceId 资源标识
     * @return 处理结果
     */
    public ExecutionTimelineView cached(String traceId) {
        // A cached projection is still private conversation data. Resolve the
        // trace through the owner-aware query first so a snapshot lookup cannot
        // become an authorization bypass.
        ExecutionEventQueryService.ConversationTrace trace = eventQuery.traceConversation(traceId);
        AgentExecutionTimelineSnapshot snapshot = mapper.selectByTraceId(traceId);
        if (snapshot == null) {
            return persist(
                trace.traceId(),
                trace.turn().getConversationId(),
                null,
                trace.events().stream()
                    .map(ExecutionEventView::runId)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null),
                aggregation.aggregate(trace.events())
            );
        }
        return new ExecutionTimelineView(
            snapshot.getTraceId(), snapshot.getConversationId(), snapshot.getTaskId(),
            snapshot.getRunId(), snapshot.getContentHash(), snapshot.getGeneratedAt(), true,
            parseItems(snapshot.getTimelineJson())
        );
    }

    /**
     * 处理{@code readAll}并返回对应结果。
     *
     * @param reader {@code reader}参数
     * @return 符合条件的数据集合
     */
    private List<ExecutionEventView> readAll(ExecutionEventQueryService.EventStreamReader reader) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<ExecutionEventView> result = new java.util.ArrayList<>();
        long cursor = 0L;
        while (result.size() < MAX_EVENTS) {
            List<ExecutionEventView> page = reader.read(cursor, PAGE_SIZE);
            if (page == null || page.isEmpty()) {
                break;
            }
            result.addAll(page);
            long next = page.getLast().cursor();
            if (next <= cursor || page.size() < PAGE_SIZE) {
                break;
            }
            cursor = next;
        }
        if (result.size() > MAX_EVENTS) {
            throw new IllegalStateException("执行时间线超过1000条限制");
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code persist}并返回对应结果。
     *
     * @param traceId 资源标识
     * @param conversationId 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param items {@code items}参数
     * @return 处理结果
     */
    private ExecutionTimelineView persist(
        String traceId,
        Long conversationId,
        Long taskId,
        Long runId,
        List<ExecutionEventView> items
    ) {
        Objects.requireNonNull(traceId, "traceId must not be null");
        String json = jsonMapper.writeValueAsString(items);
        String hash = ContentHashing.sha256(json);
        LocalDateTime now = LocalDateTime.now();
        AgentExecutionTimelineSnapshot snapshot = new AgentExecutionTimelineSnapshot();
        snapshot.setId(idGenerator.nextId());
        snapshot.setTraceId(traceId);
        snapshot.setConversationId(conversationId);
        snapshot.setTaskId(taskId);
        snapshot.setRunId(runId);
        snapshot.setTimelineJson(json);
        snapshot.setContentHash(hash);
        snapshot.setGeneratedAt(now);
        snapshot.setCreatedAt(now);
        snapshot.setUpdatedAt(now);
        mapper.upsert(snapshot);
        return new ExecutionTimelineView(
            traceId, conversationId, taskId, runId, hash, now, true, items
        );
    }

    /**
     * 处理{@code parseItems}并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 符合条件的数据集合
     */
    private List<ExecutionEventView> parseItems(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ExecutionEventView> result = jsonMapper.readValue(json, ITEMS);
            return result == null ? List.of() : result;
        } catch (RuntimeException exception) {
            return List.of();
        }
    }
}
