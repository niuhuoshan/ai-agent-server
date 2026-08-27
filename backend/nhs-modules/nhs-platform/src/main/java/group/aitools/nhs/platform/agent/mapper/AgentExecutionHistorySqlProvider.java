package group.aitools.nhs.platform.agent.mapper;

import java.util.Map;

/**
 * 负责智能体执行历史记录Sql相关的转换、解析或处理逻辑。
 * Builds the owner-scoped Nhs history projection without string-concatenating user input. */
public final class AgentExecutionHistorySqlProvider {

    private AgentExecutionHistorySqlProvider() {
    }

    /**
     * 获取{@code select}。
     *
     * @param params {@code params}参数
     * @return 处理结果
     */
    public static String select(Map<String, Object> params) {
        String base = base(params);
        boolean grouped = Boolean.TRUE.equals(params.get("groupByConversation"));
        String projection = grouped
            ? "SELECT id, trace_id, agent_id, conversation_id, username, query, summary, status, "
                + "agent_version, model_id, execution_time_ms, created_at, turn_count, "
                + "agent_display_name, agent_name, prompt_tokens, completion_tokens, total_tokens "
                + "FROM (SELECT b.*, COUNT(*) OVER (PARTITION BY COALESCE(b.conversation_id::text, b.trace_id)) AS turn_count, "
                + "ROW_NUMBER() OVER (PARTITION BY COALESCE(b.conversation_id::text, b.trace_id) "
                + "ORDER BY b.created_at DESC, b.id DESC) AS row_number FROM base b) grouped_history "
                + "WHERE row_number = 1"
            : "SELECT b.*, NULL::bigint AS turn_count FROM base b";
        return "WITH base AS (" + base + ") " + projection
            + " ORDER BY created_at DESC, id DESC OFFSET #{offset} LIMIT #{pageSize}";
    }

    /**
     * 处理{@code count}并返回对应结果。
     *
     * @param params {@code params}参数
     * @return 处理结果
     */
    public static String count(Map<String, Object> params) {
        String base = base(params);
        if (Boolean.TRUE.equals(params.get("groupByConversation"))) {
            return "WITH base AS (" + base + ") SELECT COUNT(DISTINCT COALESCE(conversation_id::text, trace_id)) FROM base";
        }
        return "WITH base AS (" + base + ") SELECT COUNT(*) FROM base";
    }

    /**
     * 处理{@code base}并返回对应结果。
     *
     * @param p {@code p}参数
     * @return 处理结果
     */
    private static String base(Map<String, Object> p) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        StringBuilder sql = new StringBuilder("""
            SELECT t.id, t.trace_id, t.agent_id, t.conversation_id,
                   COALESCE(u.user_name, t.user_id::text) AS username,
                   question_message.content AS query,
                   NULLIF(answer_message.content, '') AS summary,
                   CASE WHEN t.status = 'succeeded' THEN 'success' ELSE t.status END AS status,
                   COALESCE(v.version_no::text, t.agent_version_id::text) AS agent_version,
                   COALESCE(answer_message.model_id, v.model_id)::text AS model_id,
                   CASE WHEN t.finished_at IS NULL THEN NULL ELSE GREATEST(
                       0, FLOOR(EXTRACT(EPOCH FROM (t.finished_at - t.started_at)) * 1000)::bigint
                   ) END AS execution_time_ms,
                   t.started_at AS created_at,
                   d.name AS agent_display_name,
                   d.agent_key AS agent_name,
                   COALESCE(answer_message.prompt_tokens, 0) AS prompt_tokens,
                   COALESCE(answer_message.completion_tokens, 0) AS completion_tokens,
                   COALESCE(answer_message.total_tokens, 0) AS total_tokens
              FROM agent_conversation_turn t
              INNER JOIN agent_conversation c ON c.id = t.conversation_id
                 AND c.user_id = t.user_id AND c.principal_type = 'human'
                 AND c.del_flag = '0' AND c.status <> 'deleted'
              LEFT JOIN agent_definition d ON d.id = t.agent_id
              LEFT JOIN agent_definition_version v ON v.id = t.agent_version_id AND v.agent_id = t.agent_id
              LEFT JOIN sys_user u ON u.user_id = t.user_id AND u.del_flag = '0'
             LEFT JOIN LATERAL (
                  SELECT m.content
                    FROM agent_conversation_message m
                   WHERE m.conversation_id = t.conversation_id AND m.trace_id = t.trace_id
                     AND m.role = 'user'
                   ORDER BY m.seq_no, m.id LIMIT 1
              ) question_message ON TRUE
              LEFT JOIN LATERAL (
                  SELECT m.content, m.model_id, m.prompt_tokens, m.completion_tokens, m.total_tokens
                    FROM agent_conversation_message m
                   WHERE m.conversation_id = t.conversation_id AND m.trace_id = t.trace_id
                     AND m.role = 'assistant'
                   ORDER BY m.seq_no DESC, m.id DESC LIMIT 1
              ) answer_message ON TRUE
             WHERE 1 = 1
               AND NOT EXISTS (
                   SELECT 1 FROM agent_conversation_history_tombstone h
                   WHERE h.user_id = t.user_id AND h.conversation_id = t.conversation_id
                     AND (h.trace_id IS NULL OR h.trace_id = t.trace_id)
               )
            """);
        if (Boolean.TRUE.equals(p.get("platformAdmin"))) {
            if (p.get("username") != null) {
                sql.append(" AND u.user_name = #{username}");
            }
        } else {
            sql.append(" AND t.user_id = #{ownerId} AND c.user_id = #{ownerId}");
        }
        if (p.get("agentId") != null) sql.append(" AND t.agent_id = #{agentId}");
        if (p.get("conversationId") != null) sql.append(" AND t.conversation_id = #{conversationId}");
        if (p.get("status") != null && !((String) p.get("status")).isBlank()) sql.append(" AND t.status = #{status}");
        if (p.get("keyword") != null && !((String) p.get("keyword")).isBlank()) {
            sql.append(" AND (question_message.content ILIKE CONCAT('%', #{keyword}, '%') "
                + "OR answer_message.content ILIKE CONCAT('%', #{keyword}, '%'))");
        }
        if (p.get("startDate") != null) sql.append(" AND t.started_at >= #{startDate}");
        if (p.get("endDate") != null) sql.append(" AND t.started_at <= #{endDate}");
        return sql.toString();
    }
}
