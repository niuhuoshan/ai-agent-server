package group.aitools.nhs.platform.connector.web;

import group.aitools.nhs.platform.connector.domain.AgentRuntimeConfirmation;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 封装运行时Confirmation相关的不可变数据。
 * Safe projection of a business confirmation; runtime snapshots stay server-side. */
public record RuntimeConfirmationView(
    String confirmationId,
    Long conversationTurnId,
    Long taskId,
    Long runId,
    Long stepId,
    String status,
    String title,
    List<Map<String, Object>> fields,
    Map<String, Object> ui,
    LocalDateTime expiresAt,
    LocalDateTime decidedAt,
    LocalDateTime consumedAt
) {
    private static final TypeReference<List<Map<String, Object>>> FIELDS = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> UI = new TypeReference<>() { };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param source 数据源参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static RuntimeConfirmationView from(
        AgentRuntimeConfirmation source,
        JsonMapper jsonMapper
    ) {
        List<Map<String, Object>> fields = source.getFieldsJson() == null
            ? List.of() : jsonMapper.readValue(source.getFieldsJson(), FIELDS);
        Map<String, Object> ui = source.getUiJson() == null
            ? Map.of() : jsonMapper.readValue(source.getUiJson(), UI);
        return new RuntimeConfirmationView(
            source.getConfirmationKey(), source.getConversationTurnId(), source.getTaskId(),
            source.getRunId(), source.getStepId(),
            source.getStatus(), source.getTitle(), fields, ui, source.getExpiresAt(),
            source.getDecidedAt(), source.getConsumedAt()
        );
    }
}
