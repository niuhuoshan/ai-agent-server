package group.aitools.nhs.platform.runtime.question.web;

import group.aitools.nhs.platform.runtime.question.domain.AgentRuntimeUserQuestion;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 封装用户追问相关的不可变数据。
 * Safe projection of a pending or completed user question. */
public record UserQuestionView(
    String questionId,
    Long conversationId,
    Long conversationTurnId,
    String executionId,
    String toolCallId,
    String question,
    List<Map<String, Object>> options,
    boolean multiSelect,
    boolean allowCustomInput,
    String context,
    String purpose,
    String status,
    List<String> selectedOptionIds,
    String customInput,
    LocalDateTime expiresAt,
    LocalDateTime answeredAt,
    LocalDateTime cancelledAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    private static final TypeReference<List<Map<String, Object>>> OPTIONS = new TypeReference<>() { };
    private static final TypeReference<List<String>> SELECTED = new TypeReference<>() { };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param source 数据源参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static UserQuestionView from(
        AgentRuntimeUserQuestion source,
        JsonMapper jsonMapper
    ) {
        List<Map<String, Object>> options = readList(source.getOptionsJson(), OPTIONS, jsonMapper);
        List<String> selected = readList(source.getSelectedOptionIdsJson(), SELECTED, jsonMapper);
        return new UserQuestionView(
            source.getQuestionId(), source.getConversationId(), source.getConversationTurnId(),
            source.getExecutionId(), source.getToolCallId(), source.getQuestion(), options,
            source.isMultiSelect(), source.isAllowCustomInput(), source.getContext(),
            source.getPurpose(), source.getStatus(), selected, source.getCustomInput(),
            source.getExpiresAt(), source.getAnsweredAt(), source.getCancelledAt(),
            source.getCreatedAt(), source.getUpdatedAt()
        );
    }

    /**
     * 处理{@code readList}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param type 业务类型
     * @param jsonMapper {@code jsonMapper}参数
     * @return 符合条件的数据集合
     */
    private static <T> List<T> readList(
        String value,
        TypeReference<List<T>> type,
        JsonMapper jsonMapper
    ) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<T> parsed = jsonMapper.readValue(value, type);
        return parsed == null ? List.of() : List.copyOf(parsed);
    }
}
