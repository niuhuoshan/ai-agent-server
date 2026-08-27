package group.aitools.nhs.platform.conversation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 封装Create会话会话回合相关的不可变数据。
 * Starts one idempotent human conversation turn. */
public record CreateConversationTurnRequest(
    @NotBlank @Size(max = 128) String idempotencyKey,
    @NotBlank @Size(max = 131072) String input,
    @Positive Long agentId,
    @Positive Long agentVersionId,
    @Size(max = 5) List<@Positive Long> attachmentIds
) {

    /**
     * 创建 {@code CreateConversationTurnRequest} 实例并初始化所需依赖。
     *
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param input {@code input}参数
     * @param agentId 资源标识
     * @param agentVersionId 资源标识
     * @param attachmentIds 资源标识集合
     */
    public CreateConversationTurnRequest {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }
}
