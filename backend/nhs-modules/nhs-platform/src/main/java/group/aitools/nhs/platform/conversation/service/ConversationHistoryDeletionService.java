package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.mapper.ConversationHistoryDeletionMapper;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationHistoryTargetRow;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 负责会话历史记录Deletion相关的业务编排与领域规则处理。
 * Hides V1 history rows while preserving the underlying conversation facts. */
@Service
public class ConversationHistoryDeletionService {

    private static final Set<String> ACTIVE_TURN_STATES = Set.of(
        "running", "stopping", "waiting_confirmation", "waiting_user_question"
    );

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final ConversationHistoryDeletionMapper mapper;

    /**
     * 创建 {@code ConversationHistoryDeletionService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param mapper {@code mapper}参数
     */
    public ConversationHistoryDeletionService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        ConversationHistoryDeletionMapper mapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
    }

    /**
     * 删除链路追踪。
     *
     * @param traceId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public DeletionResult deleteTrace(String traceId) {
        String normalized = normalizeTrace(traceId);
        ConversationHistoryTargetRow target = mapper.selectTrace(normalized);
        if (target == null) {
            throw new ServiceException("历史记录不存在", HttpStatus.NOT_FOUND);
        }
        CurrentPrincipal principal = authorize(target);
        ensureNotActive(target);
        int inserted = mapper.insertTombstone(
            idGenerator.nextId(), target.getUserId(), target.getConversationId(), normalized,
            principal.id(), LocalDateTime.now(), "v1_trace_delete"
        );
        return new DeletionResult(
            target.getConversationId(), normalized, inserted > 0, inserted == 0
        );
    }

    /**
     * 删除{@code Conversations}。
     *
     * @param conversationIds 资源标识集合
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public BatchDeletionResult deleteConversations(List<Long> conversationIds) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (conversationIds == null || conversationIds.isEmpty() || conversationIds.size() > 100) {
            throw new ServiceException("conversation_ids 不能为空且最多100项", HttpStatus.BAD_REQUEST);
        }
        LinkedHashSet<Long> requested = new LinkedHashSet<>(conversationIds);
        List<Long> distinct = requested.stream().sorted().toList();
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        int inserted = 0;
        for (Long conversationId : distinct) {
            if (conversationId == null || conversationId <= 0) {
                throw new ServiceException("conversation_ids 必须为正整数", HttpStatus.BAD_REQUEST);
            }
            ConversationHistoryTargetRow target = mapper.selectConversation(conversationId);
            if (target == null) {
                throw new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
            }
            authorize(target);
            target.setTurnStatus(mapper.selectActiveTurnStatus(conversationId));
            ensureNotActive(target);
            int row = mapper.insertTombstone(
                idGenerator.nextId(), target.getUserId(), target.getConversationId(), null,
                principal.id(), LocalDateTime.now(), "v1_conversation_batch_delete"
            );
            inserted += row;
        }
        return new BatchDeletionResult(distinct, distinct.size(), inserted);
    }

    /**
     * 处理{@code authorize}并返回对应结果。
     *
     * @param target {@code target}参数
     * @return 处理结果
     */
    private CurrentPrincipal authorize(ConversationHistoryTargetRow target) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        boolean owner = principal.id().equals(target.getUserId());
        boolean admin = principal.hasRole(PlatformRole.PLATFORM_ADMIN);
        if (!owner && !admin) {
            throw new ServiceException("无权删除该历史记录", HttpStatus.FORBIDDEN);
        }
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "conversation", target.getConversationId(), null, "delete_history",
            ResourceState.ACTIVE, true, Set.of(), null
        ));
        return principal;
    }

    /**
     * 校验{@code NotActive}，并在条件不满足时终止处理。
     *
     * @param target {@code target}参数
     */
    private void ensureNotActive(ConversationHistoryTargetRow target) {
        if (ACTIVE_TURN_STATES.contains(target.getTurnStatus())) {
            throw new ServiceException("执行中的回合不能删除历史记录", HttpStatus.CONFLICT);
        }
    }

    /**
     * 处理normalize链路追踪并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeTrace(String value) {
        if (value == null || value.isBlank()) {
            throw new ServiceException("Trace ID不能为空", HttpStatus.BAD_REQUEST);
        }
        String normalized = value.strip();
        if (normalized.length() > 64 || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
            throw new ServiceException("Trace ID无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 封装{@code Deletion}相关的不可变数据。
     */
    public record DeletionResult(
        Long conversationId,
        String traceId,
        boolean deleted,
        boolean alreadyDeleted
    ) {
    }

    /**
     * 封装{@code BatchDeletion}相关的不可变数据。
     */
    public record BatchDeletionResult(
        List<Long> conversationIds,
        int requestedCount,
        int insertedCount
    ) {
    }
}
