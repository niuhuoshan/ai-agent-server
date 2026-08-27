package group.aitools.nhs.platform.nhs.portal.chatbi;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.data.domain.AgentDataQuery;
import group.aitools.nhs.platform.data.persistence.row.DataQueryStoredResultRow;

import java.util.List;

/**
 * 获取数据集DbType。
 *
 * 定义门户对话BI查询相关的数据访问契约。
 * Owner-scoped projections for durable ChatBI query history and downstream reuse. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
@Mapper
public interface PortalChatBIQueryMapper {

    @Select("""
        SELECT s.db_type
        FROM agent_data_dataset d
        INNER JOIN agent_data_source s ON s.id = d.data_source_id AND s.del_flag = '0'
        WHERE d.id = #{datasetId} AND d.del_flag = '0'
        """)
    String selectDatasetDbType(@Param("datasetId") Long datasetId);

    /**
     * 获取{@code OwnedQueries}。
     *
     * @param userId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, task_id, run_id, conversation_id, trace_id, data_source_id, dataset_id,
               data_source_revision, dataset_revision, user_query,
               sql_plan_json::text AS sql_plan_json, sql_text, sql_hash,
               permission_summary_json::text AS permission_summary_json,
               row_count, result_bytes, result_truncated, status, error_summary,
               started_at, finished_at, created_by, created_at
        FROM agent_data_query
        WHERE created_by = #{userId}
          AND trace_id LIKE 'chatbi:%'
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentDataQuery> selectOwnedQueries(
        @Param("userId") Long userId,
        @Param("limit") int limit
    );

    /**
     * 获取Owned查询。
     *
     * @param queryId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_id, run_id, conversation_id, trace_id, data_source_id, dataset_id,
               data_source_revision, dataset_revision, user_query,
               sql_plan_json::text AS sql_plan_json, sql_text, sql_hash,
               permission_summary_json::text AS permission_summary_json,
               row_count, result_bytes, result_truncated, status, error_summary,
               started_at, finished_at, created_by, created_at
        FROM agent_data_query
        WHERE id = #{queryId} AND created_by = #{userId}
          AND trace_id LIKE 'chatbi:%'
        """)
    AgentDataQuery selectOwnedQuery(
        @Param("queryId") Long queryId,
        @Param("userId") Long userId
    );

    /**
     * 获取Owned结果。
     *
     * @param queryId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT r.query_id, r.columns_json::text AS columns_json,
               r.rows_json::text AS rows_json, r.content_hash,
               r.row_count, r.result_bytes, r.created_by, r.created_at
        FROM agent_data_query_result r
        INNER JOIN agent_data_query q ON q.id = r.query_id
        WHERE r.query_id = #{queryId} AND r.created_by = #{userId}
          AND q.created_by = #{userId} AND q.trace_id LIKE 'chatbi:%'
        """)
    DataQueryStoredResultRow selectOwnedResult(
        @Param("queryId") Long queryId,
        @Param("userId") Long userId
    );

    /**
     * 获取{@code OwnedAssistantAnalysis}。
     *
     * @param queryId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT m.content
        FROM agent_data_query q
        INNER JOIN agent_conversation c
            ON c.id = q.conversation_id AND c.user_id = #{userId}
           AND c.principal_type = 'human' AND c.del_flag = '0'
        INNER JOIN agent_conversation_message m
            ON m.conversation_id = q.conversation_id AND m.trace_id = q.trace_id
           AND m.role = 'assistant' AND m.status = 'completed'
        WHERE q.id = #{queryId} AND q.created_by = #{userId}
          AND q.trace_id LIKE 'chatbi:%'
        ORDER BY m.seq_no DESC, m.id DESC
        LIMIT 1
        """)
    String selectOwnedAssistantAnalysis(
        @Param("queryId") Long queryId,
        @Param("userId") Long userId
    );

    /**
     * 获取Owned会话Title。
     *
     * @param queryId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT c.title
        FROM agent_data_query q
        INNER JOIN agent_conversation c
            ON c.id = q.conversation_id AND c.user_id = #{userId}
           AND c.principal_type = 'human' AND c.del_flag = '0'
        WHERE q.id = #{queryId} AND q.created_by = #{userId}
          AND q.trace_id LIKE 'chatbi:%'
        """)
    String selectOwnedConversationTitle(
        @Param("queryId") Long queryId,
        @Param("userId") Long userId
    );
}
