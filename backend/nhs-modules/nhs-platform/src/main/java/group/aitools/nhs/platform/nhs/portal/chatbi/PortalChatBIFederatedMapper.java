package group.aitools.nhs.platform.nhs.portal.chatbi;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建并保存{@code Run}。
 *
 * 定义门户对话BIFederated相关的数据访问契约。
 * Persistence contract for federated ChatBI runs and their governed source steps. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
@Mapper
public interface PortalChatBIFederatedMapper {

    @Insert("""
        INSERT INTO agent_chatbi_federated_run (
            id, run_key, owner_id, conversation_id, primary_dataset_id,
            request_question, dataset_ids_json, status, source_count, created_at
        ) VALUES (
            #{id}, #{runKey}, #{ownerId}, #{conversationId}, #{primaryDatasetId},
            #{requestQuestion}, CAST(#{datasetIdsJson} AS jsonb), #{status}, #{sourceCount}, #{createdAt}
        )
        """)
    int insertRun(AgentChatBIFederatedRun run);

    /**
     * 处理{@code startRun}并返回对应结果。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_federated_run
        SET conversation_id = #{conversationId}, plan_json = CAST(#{planJson} AS jsonb),
            join_sql = #{joinSql}, status = 'running', started_at = #{startedAt}
        WHERE id = #{id} AND owner_id = #{ownerId} AND status = 'planning'
        """)
    int startRun(AgentChatBIFederatedRun run);

    /**
     * 校验{@code Clarification}，并在条件不满足时终止处理。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_federated_run
        SET conversation_id = #{conversationId}, plan_json = CAST(#{planJson} AS jsonb),
            status = 'clarification_required', error_summary = #{errorSummary}, finished_at = #{finishedAt}
        WHERE id = #{id} AND owner_id = #{ownerId} AND status = 'planning'
        """)
    int requireClarification(AgentChatBIFederatedRun run);

    /**
     * 处理{@code completeRun}并返回对应结果。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_federated_run
        SET result_query_id = #{resultQueryId}, status = 'succeeded', row_count = #{rowCount},
            result_bytes = #{resultBytes}, result_truncated = #{resultTruncated},
            error_summary = NULL, finished_at = #{finishedAt}
        WHERE id = #{id} AND owner_id = #{ownerId} AND status = 'running'
        """)
    int completeRun(AgentChatBIFederatedRun run);

    /**
     * 处理{@code failRun}并返回对应结果。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_federated_run
        SET status = 'failed', error_summary = #{errorSummary}, finished_at = #{finishedAt}
        WHERE id = #{id} AND owner_id = #{ownerId} AND status IN ('planning', 'running')
        """)
    int failRun(AgentChatBIFederatedRun run);

    /**
     * 创建并保存数据源。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_chatbi_federated_source (
            id, run_id, sequence_no, dataset_id, temp_table, trace_id, planned_sql,
            status, repair_count, created_at
        ) VALUES (
            #{id}, #{runId}, #{sequenceNo}, #{datasetId}, #{tempTable}, #{traceId}, #{plannedSql},
            #{status}, #{repairCount}, #{createdAt}
        )
        """)
    int insertSource(AgentChatBIFederatedSource source);

    /**
     * 处理start数据源并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_federated_source
        SET status = 'running', started_at = #{startedAt}
        WHERE id = #{id} AND run_id = #{runId} AND status = 'pending'
        """)
    int startSource(AgentChatBIFederatedSource source);

    /**
     * 处理complete数据源并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_federated_source
        SET effective_sql = #{effectiveSql}, query_id = #{queryId}, status = 'succeeded',
            row_count = #{rowCount}, result_truncated = #{resultTruncated},
            repair_count = #{repairCount}, error_summary = NULL, finished_at = #{finishedAt}
        WHERE id = #{id} AND run_id = #{runId} AND status = 'running'
        """)
    int completeSource(AgentChatBIFederatedSource source);

    /**
     * 处理fail数据源并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_federated_source
        SET status = 'failed', error_summary = #{errorSummary}, finished_at = #{finishedAt}
        WHERE id = #{id} AND run_id = #{runId} AND status IN ('pending', 'running')
        """)
    int failSource(AgentChatBIFederatedSource source);

    /**
     * 获取{@code OwnedRun}。
     *
     * @param runKey {@code runKey}参数
     * @param ownerId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, run_key, owner_id, conversation_id, primary_dataset_id, result_query_id,
               request_question, dataset_ids_json::text AS dataset_ids_json,
               plan_json::text AS plan_json, join_sql, status, source_count, row_count,
               result_bytes, result_truncated, error_summary, started_at, finished_at, created_at
        FROM agent_chatbi_federated_run
        WHERE run_key = #{runKey} AND owner_id = #{ownerId}
        """)
    AgentChatBIFederatedRun selectOwnedRun(
        @Param("runKey") String runKey,
        @Param("ownerId") Long ownerId
    );

    /**
     * 获取OwnedRunBy结果。
     *
     * @param queryId 资源标识
     * @param ownerId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, run_key, owner_id, conversation_id, primary_dataset_id, result_query_id,
               request_question, dataset_ids_json::text AS dataset_ids_json,
               plan_json::text AS plan_json, join_sql, status, source_count, row_count,
               result_bytes, result_truncated, error_summary, started_at, finished_at, created_at
        FROM agent_chatbi_federated_run
        WHERE result_query_id = #{queryId} AND owner_id = #{ownerId}
        """)
    AgentChatBIFederatedRun selectOwnedRunByResult(
        @Param("queryId") Long queryId,
        @Param("ownerId") Long ownerId
    );

    /**
     * 获取{@code Sources}。
     *
     * @param runId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, run_id, sequence_no, dataset_id, temp_table, trace_id, planned_sql,
               effective_sql, query_id, status, row_count, result_truncated, repair_count,
               error_summary, started_at, finished_at, created_at
        FROM agent_chatbi_federated_source
        WHERE run_id = #{runId}
        ORDER BY sequence_no, id
        """)
    List<AgentChatBIFederatedSource> selectSources(@Param("runId") Long runId);

    /**
     * 获取数据集IdsBy结果。
     *
     * @param queryId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT s.dataset_id
        FROM agent_chatbi_federated_run r
        INNER JOIN agent_chatbi_federated_source s ON s.run_id = r.id
        WHERE r.result_query_id = #{queryId}
        ORDER BY s.sequence_no
        """)
    List<Long> selectDatasetIdsByResult(@Param("queryId") Long queryId);

    /**
     * 获取Owned数据集IdsBy结果。
     *
     * @param queryId 资源标识
     * @param ownerId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT s.dataset_id
        FROM agent_chatbi_federated_run r
        INNER JOIN agent_chatbi_federated_source s ON s.run_id = r.id
        WHERE r.result_query_id = #{queryId} AND r.owner_id = #{ownerId}
        ORDER BY s.sequence_no
        """)
    List<Long> selectOwnedDatasetIdsByResult(
        @Param("queryId") Long queryId,
        @Param("ownerId") Long ownerId
    );

    /**
     * 处理bind会话并返回对应结果。
     *
     * @param runId 资源标识
     * @param ownerId 资源标识
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_federated_run
        SET conversation_id = #{conversationId}
        WHERE id = #{runId} AND owner_id = #{ownerId} AND conversation_id IS NULL
        """)
    int bindConversation(
        @Param("runId") Long runId,
        @Param("ownerId") Long ownerId,
        @Param("conversationId") Long conversationId
    );

    /**
     * 处理{@code skipPendingSources}并返回对应结果。
     *
     * @param runId 资源标识
     * @param reason {@code reason}参数
     * @param finishedAt {@code finishedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_federated_source
        SET status = 'skipped', error_summary = #{reason}, finished_at = #{finishedAt}
        WHERE run_id = #{runId} AND status = 'pending'
        """)
    int skipPendingSources(
        @Param("runId") Long runId,
        @Param("reason") String reason,
        @Param("finishedAt") LocalDateTime finishedAt
    );
}
