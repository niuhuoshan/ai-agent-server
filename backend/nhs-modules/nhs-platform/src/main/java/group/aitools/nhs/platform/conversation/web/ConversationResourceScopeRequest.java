package group.aitools.nhs.platform.conversation.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 封装会话资源范围相关的不可变数据。
 * Explicit conversation resource scope. Omitted keys inherit Agent bindings; present empty lists deny that type. */
public record ConversationResourceScopeRequest(
    @PositiveOrZero Integer expectedRevision,
    @NotNull @Size(max = 32) Map<String, List<Long>> resources
) {
}
