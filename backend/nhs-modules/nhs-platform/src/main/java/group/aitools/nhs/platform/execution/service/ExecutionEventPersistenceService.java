package group.aitools.nhs.platform.execution.service;

import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.runtime.spi.RuntimeSecretScrubber;
import group.aitools.nhs.runtime.spi.RuntimeSensitiveLevel;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.domain.AgentExecutionEvent;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentExecutionEventMapper;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 负责执行事件Persistence相关的业务编排与领域规则处理。
 */
@Service
public class ExecutionEventPersistenceService {

    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final int MAX_QUERY_PROJECTION_BYTES = 64 * 1024;
    private static final Set<RuntimeEventType> PROJECTABLE_TYPES = Set.of(
        RuntimeEventType.MODEL_CALL_STARTED,
        RuntimeEventType.MODEL_CALL_FINISHED,
        RuntimeEventType.TOOL_CALL_STARTED,
        RuntimeEventType.TOOL_CALL_DELTA,
        RuntimeEventType.TOOL_CALL_FINISHED,
        RuntimeEventType.TOOL_RESULT_STARTED,
        RuntimeEventType.TOOL_RESULT_DELTA,
        RuntimeEventType.TOOL_RESULT_FINISHED,
        RuntimeEventType.APPROVAL_REQUIRED,
        RuntimeEventType.EXTERNAL_EXECUTION_REQUIRED,
        RuntimeEventType.APPROVAL_RESOLVED,
        RuntimeEventType.EXTERNAL_EXECUTION_RESOLVED
    );
    private static final Set<String> PROJECTION_FIELDS = Set.of(
        "agentName", "model", "temperature", "replyId", "toolCallId", "toolName",
        "inputDelta", "outputDelta", "outputData", "toolState", "promptTokens",
        "completionTokens", "cachedTokens", "totalTokens", "durationMs", "truncated",
        "resultStatus", "resultMessage", "businessConfirmation", "confirmationId",
        "confirmationStatus", "delegationId", "delegationStatus", "dashboardContext", "artifact",
        "requestType", "toolCalls", "toolCall", "permissionRequestId", "externalExecutionRequestId",
        "title", "details", "status", "result", "output", "data", "url", "blockId", "mediaType",
        "delegationCount", "delegationCompletedCount", "delegationFailedCount", "delegationPendingCount",
        "delegationResults", "todo", "todoType", "citations", "citationCount",
        "evidenceType", "evidenceStatus"
    );

    private final PlatformIdGenerator idGenerator;
    private final AgentExecutionEventMapper eventMapper;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code ExecutionEventPersistenceService} 实例并初始化所需依赖。
     *
     * @param idGenerator {@code idGenerator}参数
     * @param eventMapper 事件Mapper参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public ExecutionEventPersistenceService(
        PlatformIdGenerator idGenerator,
        AgentExecutionEventMapper eventMapper,
        JsonMapper jsonMapper
    ) {
        this.idGenerator = idGenerator;
        this.eventMapper = eventMapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code append}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ExecutionEventView append(RuntimeEvent source) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Objects.requireNonNull(source, "source must not be null");
        if (isRetraction(source)) {
            // The retraction may arrive after one or more public deltas.  Scrub
            // those rows before exposing the retraction to any reader; this is
            // deliberately idempotent for replayed event streams.
            eventMapper.redactTextDeltasForRetraction(source.executionKey().traceId());
        }
        String eventId = ContentHashing.sha256(
            source.executionKey().executionId() + "\u0000" + source.sourceEventId()
        );
        AgentExecutionEvent existing = eventMapper.selectByEventId(eventId);
        if (existing != null) {
            return ExecutionEventView.from(existing, jsonMapper);
        }

        AgentExecutionEvent event = toEvent(source, eventId, eventMapper.nextCursor());
        if (eventMapper.insertEvent(event) == 0) {
            AgentExecutionEvent raced = eventMapper.selectByEventId(eventId);
            if (raced == null) {
                throw new IllegalStateException("execution event idempotency conflict");
            }
            return ExecutionEventView.from(raced, jsonMapper);
        }
        return ExecutionEventView.from(event, jsonMapper);
    }

    /**
     * 判断{@code Retraction}是否满足要求。
     *
     * @param source 数据源参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isRetraction(RuntimeEvent source) {
        return source.type() == RuntimeEventType.CUSTOM
            && Boolean.TRUE.equals(source.payload().get("retraction"));
    }

    /**
     * 处理{@code persist}并返回对应结果。
     *
     * @param events {@code events}参数
     * @return 处理结果
     */
    public Flux<ExecutionEventView> persist(Flux<RuntimeEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        return events.concatMap(event -> Mono.fromCallable(() -> append(event).externalized())
            .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 处理persistWith数据源并返回对应结果。
     *
     * @param events {@code events}参数
     * @return 处理结果
     */
    public Flux<PersistedRuntimeEvent> persistWithSource(Flux<RuntimeEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        return events.concatMap(event -> Mono.fromCallable(
            () -> new PersistedRuntimeEvent(event, append(event))
        ).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 将输入数据转换为事件。
     *
     * @param source 数据源参数
     * @param eventId 资源标识
     * @param cursor {@code cursor}参数
     * @return 处理结果
     */
    private AgentExecutionEvent toEvent(RuntimeEvent source, String eventId, Long cursor) {
        Map<String, Object> payload = source.sensitiveLevel() == RuntimeSensitiveLevel.SECRET
            ? Map.of("redacted", true)
            : source.payload();
        String payloadJson = jsonMapper.writeValueAsString(payload);
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("execution event payload exceeds 64KB");
        }
        Map<String, Object> projection = safeProjection(source);
        String projectionJson = jsonMapper.writeValueAsString(projection);
        if (projectionJson.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_PROJECTION_BYTES) {
            throw new IllegalArgumentException("execution event query projection exceeds 64KB");
        }
        AgentExecutionEvent event = new AgentExecutionEvent();
        event.setId(idGenerator.nextId());
        event.setEventId(eventId);
        event.setTraceId(source.executionKey().traceId());
        event.setConversationId(source.conversationId());
        event.setRunId(source.runId());
        event.setStepId(source.stepId());
        event.setCursor(cursor);
        event.setEventType(source.type().name().toLowerCase(java.util.Locale.ROOT));
        event.setEventStatus(source.status().name().toLowerCase(java.util.Locale.ROOT));
        event.setSummary(source.summary());
        event.setPayloadJson(payloadJson);
        event.setQueryProjectionJson(projectionJson);
        event.setSensitiveLevel(source.sensitiveLevel().name().toLowerCase(java.util.Locale.ROOT));
        event.setOccurredAt(LocalDateTime.ofInstant(source.occurredAt(), ZoneOffset.UTC));
        event.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return event;
    }

    /**
     * 处理{@code projectionAllowed}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean projectionAllowed(RuntimeEvent source) {
        return source.sensitiveLevel() != RuntimeSensitiveLevel.SECRET
            && PROJECTABLE_TYPES.contains(source.type());
    }

    /**
     * 处理{@code safeProjection}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private Map<String, Object> safeProjection(RuntimeEvent source) {
        if (!projectionAllowed(source)) {
            return Map.of();
        }
        Map<String, Object> projection = new LinkedHashMap<>();
        source.queryProjection().forEach((key, value) -> {
            if (PROJECTION_FIELDS.contains(key)) {
                projection.put(key, RuntimeSecretScrubber.sanitizeValue(key, value));
            }
        });
        return projection;
    }
}
