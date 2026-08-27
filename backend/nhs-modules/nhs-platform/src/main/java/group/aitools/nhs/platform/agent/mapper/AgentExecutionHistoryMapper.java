package group.aitools.nhs.platform.agent.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.agent.persistence.row.AgentExecutionHistoryRow;

import java.util.List;

/**
 * 获取历史记录。
 *
 * 定义智能体执行历史记录相关的数据访问契约。
 * Read-only Agent execution history with the human-conversation owner boundary in SQL. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentExecutionHistoryMapper {

    @SelectProvider(type = AgentExecutionHistorySqlProvider.class, method = "select")
    List<AgentExecutionHistoryRow> selectHistory(
        @Param("ownerId") Long ownerId,
        @Param("platformAdmin") boolean platformAdmin,
        @Param("agentId") Long agentId,
        @Param("conversationId") Long conversationId,
        @Param("username") String username,
        @Param("keyword") String keyword,
        @Param("status") String status,
        @Param("startDate") java.time.LocalDateTime startDate,
        @Param("endDate") java.time.LocalDateTime endDate,
        @Param("groupByConversation") boolean groupByConversation,
        @Param("offset") long offset,
        @Param("pageSize") int pageSize
    );

    /**
     * 处理count历史记录并返回对应结果。
     *
     * @param ownerId 资源标识
     * @param platformAdmin 平台Admin参数
     * @param agentId 资源标识
     * @param conversationId 资源标识
     * @param username 名称
     * @param keyword {@code keyword}参数
     * @param status 目标状态
     * @param startDate {@code startDate}参数
     * @param endDate {@code endDate}参数
     * @param groupByConversation groupBy会话参数
     * @return 处理结果
     */
    @SelectProvider(type = AgentExecutionHistorySqlProvider.class, method = "count")
    long countHistory(
        @Param("ownerId") Long ownerId,
        @Param("platformAdmin") boolean platformAdmin,
        @Param("agentId") Long agentId,
        @Param("conversationId") Long conversationId,
        @Param("username") String username,
        @Param("keyword") String keyword,
        @Param("status") String status,
        @Param("startDate") java.time.LocalDateTime startDate,
        @Param("endDate") java.time.LocalDateTime endDate,
        @Param("groupByConversation") boolean groupByConversation
    );

    /**
     * 获取{@code Executions}。
     *
     * @param agentId 资源标识
     * @param ownerId 资源标识
     * @param platformAdmin 平台Admin参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT t.id,
               t.trace_id,
               t.agent_id,
               t.conversation_id,
               COALESCE(u.user_name, t.user_id::text) AS username,
               question_message.content AS query,
               NULLIF(answer_message.content, '') AS summary,
               CASE WHEN t.status = 'succeeded' THEN 'success' ELSE t.status END AS status,
               COALESCE(v.version_no::text, t.agent_version_id::text) AS agent_version,
               COALESCE(answer_message.model_id, v.model_id)::text AS model_id,
               CASE
                   WHEN t.finished_at IS NULL THEN NULL
                   ELSE GREATEST(
                       0,
                       FLOOR(EXTRACT(EPOCH FROM (t.finished_at - t.started_at)) * 1000)::bigint
                   )
               END AS execution_time_ms,
               t.started_at AS created_at,
               COUNT(*) OVER (PARTITION BY t.conversation_id) AS turn_count,
               d.name AS agent_display_name
        FROM agent_conversation_turn t
        INNER JOIN agent_conversation c
          ON c.id = t.conversation_id
         AND c.user_id = t.user_id
         AND c.principal_type = 'human'
         AND c.del_flag = '0'
         AND c.status <> 'deleted'
        INNER JOIN agent_definition d
          ON d.id = t.agent_id
         AND d.del_flag = '0'
        LEFT JOIN agent_definition_version v
          ON v.id = t.agent_version_id
         AND v.agent_id = t.agent_id
        LEFT JOIN sys_user u
          ON u.user_id = t.user_id
         AND u.del_flag = '0'
        LEFT JOIN LATERAL (
            SELECT m.content
            FROM agent_conversation_message m
            WHERE m.conversation_id = t.conversation_id
              AND m.trace_id = t.trace_id
              AND m.role = 'user'
            ORDER BY m.seq_no, m.id
            LIMIT 1
        ) question_message ON TRUE
        LEFT JOIN LATERAL (
            SELECT m.content, m.model_id
            FROM agent_conversation_message m
            WHERE m.conversation_id = t.conversation_id
              AND m.trace_id = t.trace_id
              AND m.role = 'assistant'
            ORDER BY m.seq_no DESC, m.id DESC
            LIMIT 1
        ) answer_message ON TRUE
        WHERE t.agent_id = #{agentId}
          AND (#{platformAdmin} OR (t.user_id = #{ownerId} AND c.user_id = #{ownerId}))
          AND NOT EXISTS (
              SELECT 1 FROM agent_conversation_history_tombstone h
              WHERE h.user_id = t.user_id AND h.conversation_id = t.conversation_id
                AND (h.trace_id IS NULL OR h.trace_id = t.trace_id)
          )
        ORDER BY t.started_at DESC, t.id DESC
        LIMIT #{limit}
        """)
    List<AgentExecutionHistoryRow> selectExecutions(
        @Param("agentId") Long agentId,
        @Param("ownerId") Long ownerId,
        @Param("platformAdmin") boolean platformAdmin,
        @Param("limit") int limit
    );
}
