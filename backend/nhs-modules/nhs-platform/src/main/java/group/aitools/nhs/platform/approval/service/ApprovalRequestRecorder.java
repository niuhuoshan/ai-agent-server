package group.aitools.nhs.platform.approval.service;

import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.platform.approval.domain.AgentApprovalRequest;
import group.aitools.nhs.platform.approval.mapper.ApprovalRequestMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.mapper.RuntimeAuxiliaryBuiltinMapper;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.platform.notification.service.NotificationMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表示审批RequestRecorder相关的领域对象。
 * Converts a persisted AgentScope confirmation event into one durable approval request. */
@Service
public class ApprovalRequestRecorder {

    private static final int MAX_ACTIONS = 32;
    private static final int MAX_PENDING_BYTES = 64 * 1024;
    private static final Set<String> RISK_LEVELS = Set.of("R0", "R1", "R2", "R3");

    private final ApprovalRequestMapper approvalMapper;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;
    private final NotificationApplicationService notificationService;
    private final RuntimeAuxiliaryBuiltinMapper auxiliaryMapper;

    public ApprovalRequestRecorder(
        ApprovalRequestMapper approvalMapper,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper,
        NotificationApplicationService notificationService
    ) {
        this(approvalMapper, idGenerator, jsonMapper, notificationService, null);
    }

    /**
     * 创建 {@code ApprovalRequestRecorder} 实例并初始化所需依赖。
     *
     * @param approvalMapper 审批Mapper参数
     * @param idGenerator {@code idGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param notificationService 通知Service参数
     * @param auxiliaryMapper {@code auxiliaryMapper}参数
     */
    @Autowired
    public ApprovalRequestRecorder(
        ApprovalRequestMapper approvalMapper,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper,
        NotificationApplicationService notificationService,
        RuntimeAuxiliaryBuiltinMapper auxiliaryMapper
    ) {
        this.approvalMapper = approvalMapper;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
        this.notificationService = notificationService;
        this.auxiliaryMapper = auxiliaryMapper;
    }

    /**
     * 处理{@code record}并返回对应结果。
     *
     * @param requestedBy {@code requestedBy}参数
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param source 数据源参数
     * @param persisted {@code persisted}参数
     * @return 处理结果
     */
    public AgentApprovalRequest record(
        Long requestedBy,
        Long taskId,
        Long runId,
        Long stepId,
        RuntimeEvent source,
        ExecutionEventView persisted
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        validateIdentity(taskId, runId, stepId, source, persisted);
        Map<String, Object> payload = source.payload();
        String replyId = requiredText(payload.get("replyId"), "replyId", 128);
        List<Map<String, Object>> actions = pendingActions(payload.get("toolCalls"));
        String actionsJson = jsonMapper.writeValueAsString(actions);
        if (actionsJson.getBytes(StandardCharsets.UTF_8).length > MAX_PENDING_BYTES) {
            throw new IllegalStateException("approval pending actions exceed 64KB");
        }
        AgentApprovalRequest existing = approvalMapper.selectByEventId(persisted.eventId());
        if (existing != null) {
            recordBusinessConfirmation(requestedBy, taskId, existing, source, persisted, actions);
            notifyReviewers(existing);
            return existing;
        }

        AgentApprovalRequest request = new AgentApprovalRequest();
        request.setId(idGenerator.nextId());
        request.setTaskId(taskId);
        request.setRunId(runId);
        request.setStepId(stepId);
        request.setRiskLevel(riskLevel(actions));
        request.setActionSummary(actionSummary(actions));
        request.setInputSummary(inputSummary(actions));
        request.setImpactScope(impactScope(actions));
        request.setStatus("pending");
        request.setRequestedBy(requestedBy);
        request.setExpiresAt(LocalDateTime.now().plusHours(24));
        request.setCreatedAt(LocalDateTime.now());
        request.setRequestEventId(persisted.eventId());
        request.setReplyId(replyId);
        request.setPendingActionsJson(actionsJson);
        if (approvalMapper.insertRequest(request) == 0) {
            AgentApprovalRequest raced = approvalMapper.selectByEventId(persisted.eventId());
            if (raced == null) {
                throw new IllegalStateException("approval request idempotency conflict");
            }
            recordBusinessConfirmation(requestedBy, taskId, raced, source, persisted, actions);
            notifyReviewers(raced);
            return raced;
        }
        recordBusinessConfirmation(requestedBy, taskId, request, source, persisted, actions);
        notifyReviewers(request);
        return request;
    }

    /**
 * 处理{@code recordBusinessConfirmation}相关逻辑。
 *
     * A business confirmation is represented by the normal AgentScope approval event, but its
     * editable card is stored separately so the decision can resume this exact reply.  Only a
     * single confirmation call is promoted; mixed batches remain ordinary high-risk approvals.
     */
    private void recordBusinessConfirmation(
        Long requestedBy,
        Long taskId,
        AgentApprovalRequest approval,
        RuntimeEvent source,
        ExecutionEventView persisted,
        List<Map<String, Object>> actions
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (auxiliaryMapper == null || actions.size() != 1) {
            return;
        }
        Map<String, Object> action = actions.getFirst();
        if (!"request_user_confirmation".equals(normalizedActionName(action.get("name")))) {
            return;
        }
        if (!(action.get("input") instanceof Map<?, ?> rawInput)) {
            return;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        rawInput.forEach((key, value) -> input.put(String.valueOf(key), value));
        String title = optionalText(input.get("title"));
        Object rawFields = input.get("fields");
        if (title == null || !(rawFields instanceof List<?> fields)
            || fields.isEmpty() || fields.size() > MAX_ACTIONS) {
            return;
        }
        try {
            String uiJson = jsonMapper.writeValueAsString(input);
            String fieldsJson = jsonMapper.writeValueAsString(fields);
            if (uiJson.getBytes(StandardCharsets.UTF_8).length > MAX_PENDING_BYTES
                || fieldsJson.getBytes(StandardCharsets.UTF_8).length > MAX_PENDING_BYTES) {
                return;
            }
            String replyId = requiredText(source.payload().get("replyId"), "replyId", 128);
            LocalDateTime now = LocalDateTime.now();
            auxiliaryMapper.insertSuspendedConfirmation(
                idGenerator.nextId(), replyId, requestedBy, source.executionKey().executionId(),
                source.conversationId(), taskId, source.runId(), source.stepId(), approval.getId(),
                persisted.eventId(), replyId, requiredText(action.get("id"), "toolCalls.id", 128),
                "request_user_confirmation", title, fieldsJson, uiJson, now.plusHours(24), now
            );
        } catch (RuntimeException ignored) {
            // A malformed confirmation must remain an ordinary approval rather than weakening it.
        }
    }

    /**
     * 处理{@code normalizedActionName}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizedActionName(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).strip().toLowerCase(java.util.Locale.ROOT)
            .replace('-', '_');
    }

    /**
     * 处理{@code notifyReviewers}相关逻辑。
     *
     * @param request 请求参数
     */
    private void notifyReviewers(AgentApprovalRequest request) {
        String summary = request.getActionSummary();
        notificationService.publishApprovalAudience(new NotificationMessage(
            "approval:pending:" + request.getId(),
            "approval",
            "warning",
            "有新的高风险操作待审批",
            summary == null || summary.isBlank() ? "请打开审批工作台查看详情" : truncate(summary, 500),
            "approval",
            request.getId()
        ));
    }

    /**
     * 校验身份，并在条件不满足时终止处理。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param source 数据源参数
     * @param persisted {@code persisted}参数
     */
    private void validateIdentity(
        Long taskId,
        Long runId,
        Long stepId,
        RuntimeEvent source,
        ExecutionEventView persisted
    ) {
        if (source == null || source.type() != RuntimeEventType.APPROVAL_REQUIRED) {
            throw new IllegalStateException("approval event source is missing or invalid");
        }
        if (!runId.equals(source.runId()) || !runId.equals(persisted.runId())
            || !stepId.equals(source.stepId()) || !stepId.equals(persisted.stepId())
            || !source.executionKey().traceId().equals(persisted.traceId())
            || (taskId != null && source.runId() == null)) {
            throw new SecurityException("approval event identity does not match the claimed run");
        }
    }

    /**
     * 处理{@code pendingActions}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> pendingActions(Object value) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (!(value instanceof List<?> raw) || raw.isEmpty() || raw.size() > MAX_ACTIONS) {
            throw new IllegalStateException("approval event must contain 1-32 tool calls");
        }
        List<Map<String, Object>> actions = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> rawAction)) {
                throw new IllegalStateException("approval tool call must be an object");
            }
            Map<String, Object> action = new LinkedHashMap<>();
            rawAction.forEach((key, entryValue) -> action.put(String.valueOf(key), entryValue));
            requiredText(action.get("id"), "toolCalls.id", 128);
            requiredText(action.get("name"), "toolCalls.name", 128);
            Object input = action.get("input");
            if (input != null && !(input instanceof Map<?, ?>)) {
                throw new IllegalStateException("toolCalls.input must be an object");
            }
            actions.add(Collections.unmodifiableMap(new LinkedHashMap<>(action)));
        }
        return List.copyOf(actions);
    }

    /**
     * 处理风险Level并返回对应结果。
     *
     * @param actions {@code actions}参数
     * @return 处理结果
     */
    private String riskLevel(List<Map<String, Object>> actions) {
        for (Map<String, Object> action : actions) {
            Object metadata = action.get("metadata");
            if (metadata instanceof Map<?, ?> map) {
                String risk = optionalText(map.get("riskLevel"));
                if (risk != null && RISK_LEVELS.contains(risk)) {
                    return risk;
                }
            }
        }
        return "R2";
    }

    /**
     * 处理{@code actionSummary}并返回对应结果。
     *
     * @param actions {@code actions}参数
     * @return 处理结果
     */
    private String actionSummary(List<Map<String, Object>> actions) {
        String value = actions.stream()
            .map(action -> String.valueOf(action.get("name")))
            .distinct()
            .reduce((left, right) -> left + ", " + right)
            .orElse("tool action");
        return truncate("工具调用审批: " + value, 2000);
    }

    /**
     * 处理{@code inputSummary}并返回对应结果。
     *
     * @param actions {@code actions}参数
     * @return 处理结果
     */
    private String inputSummary(List<Map<String, Object>> actions) {
        return truncate(jsonMapper.writeValueAsString(actions), 2000);
    }

    /**
     * 处理impact范围并返回对应结果。
     *
     * @param actions {@code actions}参数
     * @return 处理结果
     */
    private String impactScope(List<Map<String, Object>> actions) {
        for (Map<String, Object> action : actions) {
            Object metadata = action.get("metadata");
            if (metadata instanceof Map<?, ?> map) {
                String scope = optionalText(map.get("impactScope"));
                if (scope != null) {
                    return truncate(scope, 2000);
                }
            }
        }
        return "当前任务运行";
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param name 名称
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String requiredText(Object value, String name, int maxLength) {
        String text = optionalText(value);
        if (text == null || text.length() > maxLength || text.indexOf('\0') >= 0) {
            throw new IllegalStateException(name + " is missing or invalid");
        }
        return text;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * 处理{@code truncate}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
