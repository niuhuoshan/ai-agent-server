package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.runtime.spi.RuntimeResumeDecision;
import group.aitools.nhs.platform.approval.domain.AgentApprovalRequest;
import group.aitools.nhs.platform.approval.mapper.ApprovalRequestMapper;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.connector.domain.AgentRuntimeConfirmation;
import group.aitools.nhs.platform.connector.mapper.RuntimeAuxiliaryBuiltinMapper;
import group.aitools.nhs.platform.connector.web.RuntimeConfirmationDecisionRequest;
import group.aitools.nhs.platform.connector.web.RuntimeConfirmationDecisionResult;
import group.aitools.nhs.platform.connector.web.RuntimeConfirmationView;
import group.aitools.nhs.platform.conversation.service.ConversationTurnApplicationService;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 负责运行时Confirmation相关的业务编排与领域规则处理。
 * Owner-bound, idempotent resume API for request_user_confirmation. */
@Service
public class RuntimeConfirmationApplicationService {

    private static final TypeReference<List<Map<String, Object>>> FIELDS = new TypeReference<>() { };
    private final CurrentPrincipalProvider principalProvider;
    private final RuntimeAuxiliaryBuiltinMapper confirmationMapper;
    private final ApprovalRequestMapper approvalMapper;
    private final TaskRunApplicationService runService;
    private final ConversationTurnApplicationService conversationTurnService;
    private final JsonMapper jsonMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeConfirmationApplicationService(
        CurrentPrincipalProvider principalProvider,
        RuntimeAuxiliaryBuiltinMapper confirmationMapper,
        ApprovalRequestMapper approvalMapper,
        TaskRunApplicationService runService,
        JsonMapper jsonMapper,
        ConversationTurnApplicationService conversationTurnService
    ) {
        this.principalProvider = principalProvider;
        this.confirmationMapper = confirmationMapper;
        this.approvalMapper = approvalMapper;
        this.runService = runService;
        this.jsonMapper = jsonMapper;
        this.conversationTurnService = conversationTurnService;
    }

    /**
 * 创建 {@code RuntimeConfirmationApplicationService} 实例并初始化所需依赖。
 * Compatibility constructor for focused tests that only exercise task-backed confirmations. */
    public RuntimeConfirmationApplicationService(
        CurrentPrincipalProvider principalProvider,
        RuntimeAuxiliaryBuiltinMapper confirmationMapper,
        ApprovalRequestMapper approvalMapper,
        TaskRunApplicationService runService,
        JsonMapper jsonMapper
    ) {
        this(principalProvider, confirmationMapper, approvalMapper, runService, jsonMapper, null);
    }

    /**
     * 获取{@code get}。
     *
     * @param confirmationKey {@code confirmationKey}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public RuntimeConfirmationView get(String confirmationKey) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentRuntimeConfirmation confirmation = requireOwned(confirmationKey, principal.id());
        if ("awaiting_user".equals(confirmation.getStatus())
            && confirmation.getExpiresAt() != null
            && !confirmation.getExpiresAt().isAfter(LocalDateTime.now())) {
            confirmationMapper.expireConfirmation(
                confirmation.getId(), principal.id(), LocalDateTime.now()
            );
            confirmation = requireOwned(confirmationKey, principal.id());
        }
        return RuntimeConfirmationView.from(confirmation, jsonMapper);
    }

    /**
     * 处理{@code confirm}并返回对应结果。
     *
     * @param confirmationKey {@code confirmationKey}参数
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public RuntimeConfirmationDecisionResult confirm(
        String confirmationKey,
        RuntimeConfirmationDecisionRequest request
    ) {
        return decide(confirmationKey, request, true);
    }

    /**
     * 判断{@code cel}是否满足要求。
     *
     * @param confirmationKey {@code confirmationKey}参数
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public RuntimeConfirmationDecisionResult cancel(
        String confirmationKey,
        RuntimeConfirmationDecisionRequest request
    ) {
        return decide(confirmationKey, request, false);
    }

    /**
     * 处理{@code decide}并返回对应结果。
     *
     * @param confirmationKey {@code confirmationKey}参数
     * @param request 请求参数
     * @param confirmed {@code confirmed}参数
     * @return 处理结果
     */
    private RuntimeConfirmationDecisionResult decide(
        String confirmationKey,
        RuntimeConfirmationDecisionRequest request,
        boolean confirmed
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (principal == null || !principal.isHuman()) {
            throw new ServiceException("仅人类用户可以处理业务确认", HttpStatus.FORBIDDEN);
        }
        String key = requiredKey(confirmationKey);
        AgentRuntimeConfirmation current = confirmationMapper.lockConfirmation(key, principal.id());
        if (current == null) {
            throw new ServiceException("业务确认不存在或无权访问", HttpStatus.NOT_FOUND);
        }
        String decision = confirmed ? "confirmed" : "cancelled";
        List<Map<String, Object>> fields = confirmed
            ? normalizeFields(current, request == null ? List.of() : request.fields())
            : readFields(current.getFieldsJson());
        String fieldsJson = jsonMapper.writeValueAsString(fields);
        String uiJson = updatedUi(current, fields, decision);
        String keyHash = ContentHashing.sha256(
            "runtime-confirmation:" + key + ":" + decision + ":"
                + jsonMapper.writeValueAsString(fields) + ":" + normalize(request == null ? null : request.idempotencyKey())
        );
        if (!"awaiting_user".equals(current.getStatus())) {
            if (decision.equals(current.getStatus()) && keyHash.equals(current.getDecisionKeyHash())) {
                return new RuntimeConfirmationDecisionResult(
                    RuntimeConfirmationView.from(current, jsonMapper), true, false
                );
            }
            throw conflict("业务确认已被其他决策处理");
        }
        LocalDateTime now = LocalDateTime.now();
        if (current.getExpiresAt() == null || !current.getExpiresAt().isAfter(now)) {
            if (confirmationMapper.expireConfirmation(current.getId(), principal.id(), now) != 1) {
                throw conflict("业务确认状态已变化");
            }
            AgentRuntimeConfirmation expired = confirmationMapper.lockConfirmation(key, principal.id());
            return new RuntimeConfirmationDecisionResult(
                RuntimeConfirmationView.from(expired, jsonMapper), false, false
            );
        }
        if (current.getApprovalId() == null && current.getConversationTurnId() != null) {
            if (conversationTurnService == null) {
                throw new ServiceException("私有会话确认恢复服务未启用", 503);
            }
            List<Map<String, Object>> pending = parsePendingActions(current.getPendingActionsJson());
            List<Map<String, Object>> resumedActions = replaceConfirmationAction(pending, fields);
            String metadataJson = jsonMapper.writeValueAsString(Map.of(
                "source", "runtime_confirmation",
                "confirmationId", key,
                "reviewerId", principal.id(),
                "decision", decision
            ));
            if (confirmationMapper.decideConfirmation(
                current.getId(), principal.id(), decision, fieldsJson, uiJson, principal.id(),
                metadataJson, keyHash, now
            ) != 1) {
                throw conflict("业务确认已被并发处理");
            }
            conversationTurnService.resumeFromConfirmation(
                current, principal, resumedActions,
                confirmed ? RuntimeResumeDecision.APPROVE : RuntimeResumeDecision.REJECT
            );
            AgentRuntimeConfirmation decided = confirmationMapper.lockConfirmation(key, principal.id());
            return new RuntimeConfirmationDecisionResult(
                RuntimeConfirmationView.from(decided, jsonMapper), false, true
            );
        }
        AgentApprovalRequest approval = approvalMapper.lockById(current.getApprovalId());
        if (approval == null || !Objects.equals(current.getRunId(), approval.getRunId())
            || !"pending".equals(approval.getStatus())) {
            throw conflict("业务确认关联的审批已被处理");
        }
        List<Map<String, Object>> pending = readFields(approval.getPendingActionsJson());
        if (pending.isEmpty()) {
            throw conflict("业务确认缺少服务端动作快照");
        }
        List<Map<String, Object>> resumedActions = replaceConfirmationAction(pending, fields);
        String metadataJson = jsonMapper.writeValueAsString(Map.of(
            "source", "runtime_confirmation",
            "confirmationId", key,
            "reviewerId", principal.id(),
            "decision", decision
        ));
        if (confirmationMapper.decideConfirmation(
            current.getId(), principal.id(), decision, fieldsJson, uiJson, principal.id(),
            metadataJson, keyHash, now
        ) != 1) {
            throw conflict("业务确认已被并发处理");
        }
        if (approvalMapper.decide(
            approval.getId(), confirmed ? "approved" : "rejected", principal.id(),
            normalize(request == null ? null : request.comment()), metadataJson, keyHash
        ) != 1) {
            throw conflict("关联审批已被并发处理");
        }
        runService.resumeFromApproval(
            approval, principal, resumedActions,
            confirmed ? RuntimeResumeDecision.APPROVE : RuntimeResumeDecision.REJECT,
            "runtime_confirmation_" + decision
        );
        AgentRuntimeConfirmation decidedRow = confirmationMapper.lockConfirmation(key, principal.id());
        return new RuntimeConfirmationDecisionResult(
            RuntimeConfirmationView.from(decidedRow, jsonMapper), false, true
        );
    }

    /**
     * 处理{@code normalizeFields}并返回对应结果。
     *
     * @param confirmation {@code confirmation}参数
     * @param submitted {@code submitted}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> normalizeFields(
        AgentRuntimeConfirmation confirmation,
        List<Map<String, Object>> submitted
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<Map<String, Object>> original = readFields(confirmation.getFieldsJson());
        if (submitted == null || submitted.isEmpty()) {
            return original;
        }
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> field : submitted) {
            String key = text(field.get("key"));
            if (key == null || byKey.put(key, field) != null) {
                throw new ServiceException("业务确认字段重复或缺少 key", HttpStatus.BAD_REQUEST);
            }
        }
        List<Map<String, Object>> normalized = new ArrayList<>(original.size());
        for (Map<String, Object> field : original) {
            String key = text(field.get("key"));
            Map<String, Object> incoming = byKey.remove(key);
            if (incoming == null) {
                throw new ServiceException("业务确认字段不完整", HttpStatus.BAD_REQUEST);
            }
            boolean editable = !Boolean.FALSE.equals(field.get("editable"));
            Object value = incoming.containsKey("value") ? incoming.get("value") : field.get("value");
            if (!editable && !Objects.equals(value, field.get("value"))) {
                throw new ServiceException("不可编辑的业务确认字段被修改", HttpStatus.BAD_REQUEST);
            }
            Map<String, Object> copy = new LinkedHashMap<>(field);
            copy.put("value", value == null ? "" : value);
            normalized.add(copy);
        }
        if (!byKey.isEmpty()) {
            throw new ServiceException("业务确认包含未知字段", HttpStatus.BAD_REQUEST);
        }
        return List.copyOf(normalized);
    }

    /**
     * 处理{@code replaceConfirmationAction}并返回对应结果。
     *
     * @param actions {@code actions}参数
     * @param fields {@code fields}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> replaceConfirmationAction(
        List<Map<String, Object>> actions,
        List<Map<String, Object>> fields
    ) {
        List<Map<String, Object>> result = new ArrayList<>(actions.size());
        for (Map<String, Object> action : actions) {
            Map<String, Object> copy = new LinkedHashMap<>(action);
            if ("request_user_confirmation".equals(text(action.get("name")))) {
                Map<String, Object> input = action.get("input") instanceof Map<?, ?> raw
                    ? map(raw) : new LinkedHashMap<>();
                input.put("fields", fields);
                copy.put("input", input);
            }
            result.add(copy);
        }
        return List.copyOf(result);
    }

    /**
     * 更新{@code dUi}。
     *
     * @param confirmation {@code confirmation}参数
     * @param fields {@code fields}参数
     * @param status 目标状态
     * @return 处理结果
     */
    private String updatedUi(
        AgentRuntimeConfirmation confirmation,
        List<Map<String, Object>> fields,
        String status
    ) {
        Map<String, Object> ui = readUi(confirmation.getUiJson());
        ui.put("fields", fields);
        ui.put("status", status);
        ui.put("confirmation_id", confirmation.getConfirmationKey());
        return jsonMapper.writeValueAsString(ui);
    }

    /**
     * 校验{@code Owned}，并在条件不满足时终止处理。
     *
     * @param key {@code key}参数
     * @param ownerId 资源标识
     * @return 处理结果
     */
    private AgentRuntimeConfirmation requireOwned(String key, Long ownerId) {
        if (ownerId == null) {
            throw new ServiceException("未登录", HttpStatus.UNAUTHORIZED);
        }
        AgentRuntimeConfirmation value = confirmationMapper.lockConfirmation(requiredKey(key), ownerId);
        if (value == null) {
            throw new ServiceException("业务确认不存在或无权访问", HttpStatus.NOT_FOUND);
        }
        return value;
    }

    /**
     * 处理{@code readFields}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> readFields(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            List<Map<String, Object>> result = jsonMapper.readValue(value, FIELDS);
            return result == null ? List.of() : List.copyOf(result);
        } catch (RuntimeException exception) {
            throw conflict("业务确认字段快照损坏");
        }
    }

    /**
     * 处理{@code parsePendingActions}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> parsePendingActions(String value) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (value == null || value.isBlank()) {
            throw conflict("业务确认缺少服务端动作快照");
        }
        try {
            List<Map<String, Object>> actions = jsonMapper.readValue(value, FIELDS);
            if (actions == null || actions.isEmpty() || actions.size() > 32) {
                throw conflict("业务确认动作快照数量无效");
            }
            for (Map<String, Object> action : actions) {
                if (action == null || !validText(action.get("id")) || !validText(action.get("name"))) {
                    throw conflict("业务确认动作快照身份无效");
                }
            }
            return List.copyOf(actions);
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("业务确认动作快照无法解析");
        }
    }

    /**
     * 处理{@code validText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean validText(Object value) {
        return value instanceof String text && !text.isBlank() && text.length() <= 128;
    }

    /**
     * 处理{@code readUi}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> readUi(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> result = jsonMapper.readValue(value, new TypeReference<>() { });
            return result == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result);
        } catch (RuntimeException exception) {
            throw conflict("业务确认展示快照损坏");
        }
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private Map<String, Object> map(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /**
     * 校验{@code dKey}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String requiredKey(String value) {
        String key = normalize(value);
        if (key == null || key.length() > 128) {
            throw new ServiceException("业务确认标识无效", HttpStatus.BAD_REQUEST);
        }
        return key;
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    /**
     * 处理{@code conflict}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

}
