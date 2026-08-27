package group.aitools.nhs.platform.conversation.web;

import group.aitools.nhs.platform.conversation.domain.AgentConversation;

import java.time.LocalDateTime;

/**
 * 封装会话相关的不可变数据。
 * Safe conversation projection that never includes another user's messages or metadata. */
public record ConversationView(
    Long id,
    Long projectId,
    Long taskId,
    Long agentId,
    Long agentVersionId,
    String branchId,
    Long parentConversationId,
    Long forkMessageId,
    Integer contextCutoffSequence,
    String title,
    String visibility,
    String status,
    LocalDateTime lastMessageAt,
    LocalDateTime createdAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param conversation 会话参数
     * @return 处理结果
     */
    public static ConversationView from(AgentConversation conversation) {
        return new ConversationView(
            conversation.getId(),
            conversation.getProjectId(),
            conversation.getTaskId(),
            conversation.getAgentId(),
            conversation.getAgentVersionId(),
            conversation.getBranchId(),
            conversation.getParentConversationId(),
            conversation.getForkMessageId(),
            conversation.getContextCutoffSequence(),
            conversation.getTitle(),
            conversation.getVisibility(),
            conversation.getStatus(),
            conversation.getLastMessageAt(),
            conversation.getCreateTime()
        );
    }

    /**
 * 创建 {@code ConversationView} 实例并初始化所需依赖。
 * Compatibility constructor for clients that predate chat branches. */
    public ConversationView(
        Long id, Long projectId, Long taskId, Long agentId, Long agentVersionId,
        String title, String visibility, String status, LocalDateTime lastMessageAt,
        LocalDateTime createdAt
    ) {
        this(id, projectId, taskId, agentId, agentVersionId, null, null, null, null,
            title, visibility, status, lastMessageAt, createdAt);
    }
}
