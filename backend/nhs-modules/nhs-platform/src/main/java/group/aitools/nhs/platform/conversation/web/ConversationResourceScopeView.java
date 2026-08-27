package group.aitools.nhs.platform.conversation.web;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 封装会话资源范围相关的不可变数据。
 */
public record ConversationResourceScopeView(
    Long conversationId,
    int revision,
    Map<String, List<Long>> resources,
    LocalDateTime updatedAt
) {
}
