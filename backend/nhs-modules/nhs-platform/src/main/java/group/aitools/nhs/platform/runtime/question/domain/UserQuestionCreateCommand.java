package group.aitools.nhs.platform.runtime.question.domain;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 封装用户追问Create相关的不可变数据。
 * Internal command used by the future ask_user_question runtime tool. */
public record UserQuestionCreateCommand(
    Long ownerId,
    String questionId,
    Long conversationId,
    String executionId,
    Long conversationTurnId,
    String toolCallId,
    String idempotencyKey,
    String question,
    List<Map<String, Object>> options,
    boolean multiSelect,
    boolean allowCustomInput,
    String context,
    String purpose,
    LocalDateTime expiresAt
) {

    /**
     * 创建 {@code UserQuestionCreateCommand} 实例并初始化所需依赖。
     *
     * @param ownerId 资源标识
     * @param questionId 资源标识
     * @param conversationId 资源标识
     * @param executionId 资源标识
     * @param conversationTurnId 资源标识
     * @param toolCallId 资源标识
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param question 追问参数
     * @param options {@code options}参数
     * @param multiSelect {@code multiSelect}参数
     * @param allowCustomInput {@code allowCustomInput}参数
     * @param context 待处理内容
     * @param purpose {@code purpose}参数
     * @param expiresAt {@code expiresAt}参数
     */
    public UserQuestionCreateCommand {
        options = options == null ? List.of() : options.stream()
            .map(value -> value == null ? Map.<String, Object>of() : new LinkedHashMap<>(value))
            .toList();
    }
}
