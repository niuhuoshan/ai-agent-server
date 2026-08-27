package group.aitools.nhs.platform.approval.service;

import group.aitools.nhs.platform.approval.domain.AgentApprovalRequest;
import group.aitools.nhs.platform.approval.mapper.ApprovalRequestMapper;
import group.aitools.nhs.platform.approval.web.ApprovalDecisionRequest;
import group.aitools.nhs.platform.approval.web.ApprovalDecisionResult;
import group.aitools.nhs.platform.approval.web.ApprovalView;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.connector.mapper.RuntimeAuxiliaryBuiltinMapper;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责审批相关的业务编排与领域规则处理。
 * Durable, deny-first approval decisions backed only by server-owned recovery state. */
@Service
public class ApprovalApplicationService {

    private static final Set<String> STATUSES = Set.of(
        "pending", "approved", "rejected", "revoked", "expired"
    );
    private static final TypeReference<List<Map<String, Object>>> ACTION_LIST = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final ApprovalRequestMapper approvalMapper;
    private final TaskRunCommandMapper runMapper;
    private final TaskRunApplicationService runService;
    private final JsonMapper jsonMapper;
    private final RuntimeAuxiliaryBuiltinMapper auxiliaryMapper;

    /**
     * 创建 {@code ApprovalApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param approvalMapper 审批Mapper参数
     * @param runMapper {@code runMapper}参数
     * @param runService {@code runService}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public ApprovalApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        ApprovalRequestMapper approvalMapper,
        TaskRunCommandMapper runMapper,
        TaskRunApplicationService runService,
        JsonMapper jsonMapper
    ) {
        this(
            principalProvider, authorizationEnforcer, approvalMapper, runMapper, runService,
            jsonMapper, null
        );
    }

    /**
     * 创建 {@code ApprovalApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param approvalMapper 审批Mapper参数
     * @param runMapper {@code runMapper}参数
     * @param runService {@code runService}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param auxiliaryMapper {@code auxiliaryMapper}参数
     */
    @Autowired
    public ApprovalApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        ApprovalRequestMapper approvalMapper,
        TaskRunCommandMapper runMapper,
        TaskRunApplicationService runService,
        JsonMapper jsonMapper,
        RuntimeAuxiliaryBuiltinMapper auxiliaryMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.approvalMapper = approvalMapper;
        this.runMapper = runMapper;
        this.runService = runService;
        this.jsonMapper = jsonMapper;
        this.auxiliaryMapper = auxiliaryMapper;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param status 目标状态
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ApprovalView> list(String status, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorize(principal, null, null, "list");
        String normalized = normalizeStatus(status);
        return approvalMapper.selectRecent(normalized, limit).stream().map(ApprovalView::from).toList();
    }

    /**
     * 获取{@code get}。
     *
     * @param approvalId 资源标识
     * @return 处理结果
     */
    public ApprovalView get(Long approvalId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentApprovalRequest request = requireApproval(approvalMapper.selectById(approvalId));
        authorize(principal, request.getId(), request.getTaskId(), "view");
        return ApprovalView.from(request);
    }

    /**
     * 处理{@code approve}并返回对应结果。
     *
     * @param approvalId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ApprovalDecisionResult approve(Long approvalId, ApprovalDecisionRequest request) {
        return decide(approvalId, request, "approved");
    }

    /**
     * 处理{@code reject}并返回对应结果。
     *
     * @param approvalId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ApprovalDecisionResult reject(Long approvalId, ApprovalDecisionRequest request) {
        return decide(approvalId, request, "rejected");
    }

    /**
     * 处理{@code decide}并返回对应结果。
     *
     * @param approvalId 资源标识
     * @param input {@code input}参数
     * @param targetStatus 目标状态
     * @return 处理结果
     */
    private ApprovalDecisionResult decide(
        Long approvalId,
        ApprovalDecisionRequest input,
        String targetStatus
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentApprovalRequest preliminary = requireApproval(approvalMapper.selectById(approvalId));
        if (auxiliaryMapper != null && auxiliaryMapper.countByApprovalId(approvalId) > 0) {
            throw conflict("业务确认请使用确认卡上的确定或取消操作");
        }
        authorize(principal, approvalId, preliminary.getTaskId(), actionFor(targetStatus));
        String idempotencyKey = normalizeIdempotencyKey(input);
        String comment = normalizeComment(input.comment());
        String keyHash = ContentHashing.sha256("approval:" + approvalId + ":" + idempotencyKey);

        runMapper.lockTask(preliminary.getTaskId());
        AgentApprovalRequest current = requireApproval(approvalMapper.lockById(approvalId));
        if (!preliminary.getTaskId().equals(current.getTaskId())) {
            throw conflict("审批任务身份发生变化");
        }
        if (!"pending".equals(current.getStatus())) {
            return replayOrConflict(current, targetStatus, keyHash);
        }
        if (current.getExpiresAt() != null && !current.getExpiresAt().isAfter(LocalDateTime.now())) {
            if (approvalMapper.expire(approvalId) != 1) {
                throw conflict("审批过期状态更新失败");
            }
            runService.expireFromApproval(current);
            return new ApprovalDecisionResult(
                ApprovalView.from(requireApproval(approvalMapper.selectById(approvalId))), false, false
            );
        }

        List<Map<String, Object>> actions = "approved".equals(targetStatus)
            ? parsePendingActions(current)
            : List.of();
        String metadataJson = jsonMapper.writeValueAsString(Map.of(
            "approvalId", approvalId,
            "reviewerId", principal.id(),
            "decision", targetStatus
        ));
        if (approvalMapper.decide(
            approvalId, targetStatus, principal.id(), comment, metadataJson, keyHash
        ) != 1) {
            throw conflict("审批已由其他操作处理");
        }

        boolean resumed = false;
        if ("approved".equals(targetStatus)) {
            runService.resumeFromApproval(current, principal, actions);
            resumed = true;
        } else {
            runService.rejectFromApproval(current);
        }
        AgentApprovalRequest decided = requireApproval(approvalMapper.selectById(approvalId));
        return new ApprovalDecisionResult(ApprovalView.from(decided), false, resumed);
    }

    /**
     * 处理{@code replayOrConflict}并返回对应结果。
     *
     * @param current 当前参数
     * @param targetStatus 目标状态
     * @param keyHash {@code keyHash}参数
     * @return 处理结果
     */
    private ApprovalDecisionResult replayOrConflict(
        AgentApprovalRequest current,
        String targetStatus,
        String keyHash
    ) {
        if (targetStatus.equals(current.getStatus()) && keyHash.equals(current.getDecisionKeyHash())) {
            return new ApprovalDecisionResult(
                ApprovalView.from(current), true, "approved".equals(targetStatus)
            );
        }
        throw conflict("审批已处理，同一幂等键不能改变决策");
    }

    /**
     * 处理{@code parsePendingActions}并返回对应结果。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> parsePendingActions(AgentApprovalRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (request.getReplyId() == null || request.getReplyId().isBlank()
            || request.getPendingActionsJson() == null || request.getPendingActionsJson().isBlank()) {
            throw conflict("审批缺少可恢复的服务端动作快照");
        }
        try {
            List<Map<String, Object>> actions = jsonMapper.readValue(
                request.getPendingActionsJson(), ACTION_LIST
            );
            if (actions == null || actions.isEmpty() || actions.size() > 32) {
                throw conflict("审批动作快照数量无效");
            }
            for (Map<String, Object> action : actions) {
                if (action == null || !validText(action.get("id")) || !validText(action.get("name"))) {
                    throw conflict("审批动作快照身份无效");
                }
            }
            return List.copyOf(actions);
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("审批动作快照无法解析");
        }
    }

    /**
     * 处理{@code authorize}相关逻辑。
     *
     * @param principal 当前操作主体
     * @param approvalId 资源标识
     * @param taskId 资源标识
     * @param action {@code action}参数
     */
    private void authorize(CurrentPrincipal principal, Long approvalId, Long taskId, String action) {
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "approval", approvalId, null, action, ResourceState.ACTIVE, true, Set.of(), taskId
        ));
    }

    /**
     * 校验审批，并在条件不满足时终止处理。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private AgentApprovalRequest requireApproval(AgentApprovalRequest request) {
        if (request == null) {
            throw new ServiceException("审批不存在", HttpStatus.NOT_FOUND);
        }
        if (request.getTaskId() == null || request.getTaskId() <= 0) {
            throw conflict("审批没有有效的任务身份");
        }
        return request;
    }

    /**
     * 处理{@code normalizeStatus}并返回对应结果。
     *
     * @param status 目标状态
     * @return 处理结果
     */
    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.strip().toLowerCase(java.util.Locale.ROOT);
        if (!STATUSES.contains(value)) {
            throw new ServiceException("审批状态过滤条件无效", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /**
     * 处理{@code normalizeIdempotencyKey}并返回对应结果。
     *
     * @param input {@code input}参数
     * @return 处理结果
     */
    private String normalizeIdempotencyKey(ApprovalDecisionRequest input) {
        if (input == null || input.idempotencyKey() == null) {
            throw new ServiceException("审批幂等键不能为空", HttpStatus.BAD_REQUEST);
        }
        String value = input.idempotencyKey().strip();
        if (value.isEmpty() || value.length() > 128 || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new ServiceException("审批幂等键格式无效", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /**
     * 处理{@code normalizeComment}并返回对应结果。
     *
     * @param comment {@code comment}参数
     * @return 处理结果
     */
    private String normalizeComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String value = comment.strip();
        if (value.length() > 2000 || value.indexOf('\0') >= 0) {
            throw new ServiceException("审批意见格式无效", HttpStatus.BAD_REQUEST);
        }
        return value;
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
     * 处理{@code actionFor}并返回对应结果。
     *
     * @param status 目标状态
     * @return 处理结果
     */
    private String actionFor(String status) {
        return "approved".equals(status) ? "approve" : "reject";
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
