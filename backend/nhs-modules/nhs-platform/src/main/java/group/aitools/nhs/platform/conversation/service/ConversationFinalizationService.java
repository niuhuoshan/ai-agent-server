package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.domain.AgentConversationTurn;
import group.aitools.nhs.platform.conversation.mapper.ConversationTurnMapper;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.conversation.web.ConversationFinalizeResult;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.nhs.portal.memory.PortalMemoryService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 负责会话Finalization相关的业务编排与领域规则处理。
 *
 * Explicit, idempotent conversation finalization for clients that switch
 * sessions before the normal turn callback has refreshed the summary.
 *
 * The final summary is also projected into the governed personal-memory
 * store and its daily rollup in the same transaction. A retry repairs either
 * missing projection without duplicating memory rows.
 */
@Service
public class ConversationFinalizationService {

    private static final int MAX_SUMMARY_CHARS = 12_000;
    private static final int MESSAGE_LIMIT = 48;

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final ConversationTurnMapper mapper;
    private final PortalMemoryService memoryService;

    @Autowired
    public ConversationFinalizationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        ConversationTurnMapper mapper,
        PortalMemoryService memoryService
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.mapper = mapper;
        this.memoryService = memoryService;
    }

    /**
 * 创建 {@code ConversationFinalizationService} 实例并初始化所需依赖。
 * Backward-compatible constructor retained for focused unit tests. */
    public ConversationFinalizationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        ConversationTurnMapper mapper
    ) {
        this(principalProvider, authorizationEnforcer, mapper, null);
    }

    /**
     * 处理finalize会话并返回对应结果。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationFinalizeResult finalizeConversation(Long conversationId) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (conversationId == null || conversationId <= 0) {
            throw new ServiceException("会话ID无效", HttpStatus.BAD_REQUEST);
        }
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "conversation", conversationId, null, "finalize", ResourceState.ACTIVE,
            true, Set.of(), null
        ));
        AgentConversation conversation = mapper.lockOwnedActiveConversation(
            conversationId, principal.id()
        );
        if (conversation == null) {
            throw new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
        }
        AgentConversationTurn active = mapper.selectActiveTurn(conversationId);
        if (active != null) {
            return new ConversationFinalizeResult(
                false, conversationId, "active_turn_" + active.getStatus()
            );
        }
        List<ConversationMessageRow> messages = new ArrayList<>(
            mapper.selectRecentMessages(conversationId, MESSAGE_LIMIT)
        );
        if (messages.isEmpty()) {
            return new ConversationFinalizeResult(false, conversationId, "no_messages");
        }
        messages.sort(Comparator.comparingInt(row ->
            row.getSequenceNo() == null ? 0 : row.getSequenceNo()
        ));
        String summary = deterministicSummary(messages);
        String previous = conversation.getSummary() == null
            ? "" : conversation.getSummary();
        boolean summaryChanged = !summary.equals(previous);
        if (summaryChanged) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            if (mapper.updateConversationSummary(
                conversationId, principal.id(), summary, now
            ) != 1) {
                throw new ServiceException("会话摘要刷新失败", HttpStatus.CONFLICT);
            }
        }
        if (memoryService != null) {
            memoryService.finalizeConversationSummary(
                principal.id(), conversationId, summary, activityDate(messages)
            );
        }
        return new ConversationFinalizeResult(
            true, conversationId, summaryChanged ? "summary_refreshed" : "already_finalized"
        );
    }

    /**
     * 处理{@code deterministicSummary}并返回对应结果。
     *
     * @param messages 待处理内容
     * @return 处理结果
     */
    private String deterministicSummary(List<ConversationMessageRow> messages) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        StringBuilder result = new StringBuilder();
        for (ConversationMessageRow message : messages) {
            String content = message.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            String role = switch (message.getRole()) {
                case "user" -> "用户";
                case "assistant" -> "助手";
                case "tool" -> "工具";
                default -> "系统";
            };
            String line = role + ": " + content.strip();
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(line);
        }
        String value = result.toString();
        if (value.length() <= MAX_SUMMARY_CHARS) {
            return value;
        }
        int start = value.length() - MAX_SUMMARY_CHARS;
        if (start < value.length() && Character.isLowSurrogate(value.charAt(start))) {
            start++;
        }
        return value.substring(Math.min(start, value.length()));
    }

    /**
     * 处理{@code activityDate}并返回对应结果。
     *
     * @param messages 待处理内容
     * @return 处理结果
     */
    private java.time.LocalDate activityDate(List<ConversationMessageRow> messages) {
        return messages.stream()
            .map(ConversationMessageRow::getCreatedAt)
            .filter(java.util.Objects::nonNull)
            .max(LocalDateTime::compareTo)
            .orElseGet(() -> LocalDateTime.now(ZoneOffset.UTC))
            .toLocalDate();
    }
}
