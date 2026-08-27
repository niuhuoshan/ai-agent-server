package group.aitools.nhs.platform.agent.web;

import group.aitools.nhs.platform.agent.persistence.row.AgentExecutionHistoryRow;

import java.time.LocalDateTime;

/**
 * 封装智能体执行历史记录相关的不可变数据。
 * Nhs-compatible Agent execution history projection. */
public record AgentExecutionHistoryView(
    Long id,
    String trace_id,
    String agent_id,
    String conversation_id,
    String username,
    String query,
    String summary,
    String status,
    String agent_version,
    String model_id,
    Long execution_time_ms,
    LocalDateTime created_at,
    Long turn_count,
    String agent_display_name,
    String agent_name,
    Integer prompt_tokens,
    Integer completion_tokens,
    Integer total_tokens,
    String project_name,
    String reasoning_content,
    java.util.List<Object> process_timeline
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    public static AgentExecutionHistoryView from(AgentExecutionHistoryRow row) {
        return new AgentExecutionHistoryView(
            row.getId(), row.getTraceId(), text(row.getAgentId()), text(row.getConversationId()),
            row.getUsername(), row.getQuery(), row.getSummary(), row.getStatus(), row.getAgentVersion(),
            row.getModelId(), row.getExecutionTimeMs(), row.getCreatedAt(), row.getTurnCount(),
            row.getAgentDisplayName(), row.getAgentName(), row.getPromptTokens(),
            row.getCompletionTokens(), row.getTotalTokens(), null, null, null
        );
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static String text(Long value) {
        return value == null ? null : String.valueOf(value);
    }
}
