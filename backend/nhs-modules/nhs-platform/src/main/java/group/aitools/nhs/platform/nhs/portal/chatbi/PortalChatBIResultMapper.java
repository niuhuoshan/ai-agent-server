package group.aitools.nhs.platform.nhs.portal.chatbi;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.data.domain.AgentDataQuery;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建并保存上下文。
 *
 * 定义门户对话BI结果相关的数据访问契约。
 * Owner-scoped persistence for ChatBI result lineage, evidence and presentation state. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
@Mapper
public interface PortalChatBIResultMapper {

    @Insert("""
        INSERT INTO agent_chatbi_result_context (
            query_id, owner_id, conversation_id, parent_query_id,
            analysis_context_json, chart_config_json, pivot_config_json,
            revision_no, created_at
        ) VALUES (
            #{queryId}, #{ownerId}, #{conversationId}, #{parentQueryId},
            CAST(#{analysisContextJson} AS jsonb), CAST(#{chartConfigJson} AS jsonb),
            CAST(#{pivotConfigJson} AS jsonb), #{revisionNo}, #{createdAt}
        )
        ON CONFLICT (query_id) DO NOTHING
        """)
    int insertContext(AgentChatBIResultContext context);

    /**
     * 创建并保存{@code Evidence}。
     *
     * @param evidence {@code evidence}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_chatbi_evidence (
            id, query_id, owner_id, conversation_id, trace_id, dataset_id,
            evidence_type, producer, payload_digest, result_hash, source_ref,
            result_status, freshness, observed_at, source_as_of, expires_at,
            permission_snapshot_json, detail_json, created_at
        ) VALUES (
            #{id}, #{queryId}, #{ownerId}, #{conversationId}, #{traceId}, #{datasetId},
            #{evidenceType}, #{producer}, #{payloadDigest}, #{resultHash}, #{sourceRef},
            #{resultStatus}, #{freshness}, #{observedAt}, #{sourceAsOf}, #{expiresAt},
            CAST(#{permissionSnapshotJson} AS jsonb), CAST(#{detailJson} AS jsonb), #{createdAt}
        )
        ON CONFLICT (query_id) DO NOTHING
        """)
    int insertEvidence(AgentChatBIEvidence evidence);

    /**
     * 获取Owned上下文。
     *
     * @param queryId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT c.query_id, c.owner_id, c.conversation_id, c.parent_query_id,
               c.analysis_context_json::text AS analysis_context_json,
               c.chart_config_json::text AS chart_config_json,
               c.pivot_config_json::text AS pivot_config_json,
               c.revision_no, c.created_at, c.updated_at
        FROM agent_chatbi_result_context c
        INNER JOIN agent_data_query q
            ON q.id = c.query_id AND q.created_by = c.owner_id
           AND q.trace_id LIKE 'chatbi:%' AND q.status = 'succeeded'
        INNER JOIN agent_conversation conversation
            ON conversation.id = c.conversation_id AND conversation.user_id = c.owner_id
           AND conversation.principal_type = 'human' AND conversation.del_flag = '0'
        WHERE c.query_id = #{queryId} AND c.owner_id = #{userId}
        """)
    AgentChatBIResultContext selectOwnedContext(
        @Param("queryId") Long queryId,
        @Param("userId") Long userId
    );

    /**
     * 获取{@code OwnedEvidence}。
     *
     * @param queryId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT e.id, e.query_id, e.owner_id, e.conversation_id, e.trace_id,
               e.dataset_id, e.evidence_type, e.producer, e.payload_digest,
               e.result_hash, e.source_ref, e.result_status, e.freshness,
               e.observed_at, e.source_as_of, e.expires_at,
               e.permission_snapshot_json::text AS permission_snapshot_json,
               e.detail_json::text AS detail_json, e.created_at
        FROM agent_chatbi_evidence e
        INNER JOIN agent_data_query q
            ON q.id = e.query_id AND q.created_by = e.owner_id
           AND q.trace_id LIKE 'chatbi:%' AND q.status = 'succeeded'
        INNER JOIN agent_conversation conversation
            ON conversation.id = e.conversation_id AND conversation.user_id = e.owner_id
           AND conversation.principal_type = 'human' AND conversation.del_flag = '0'
        WHERE e.query_id = #{queryId} AND e.owner_id = #{userId}
        """)
    AgentChatBIEvidence selectOwnedEvidence(
        @Param("queryId") Long queryId,
        @Param("userId") Long userId
    );

    /**
     * 获取{@code OwnedStackQueries}。
     *
     * @param conversationId 资源标识
     * @param userId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT result.id, result.task_id, result.run_id, result.conversation_id,
               result.trace_id, result.data_source_id, result.dataset_id,
               result.data_source_revision, result.dataset_revision, result.user_query,
               result.sql_plan_json, result.sql_text, result.sql_hash,
               result.permission_summary_json, result.row_count, result.result_bytes,
               result.result_truncated, result.status, result.error_summary,
               result.started_at, result.finished_at, result.created_by, result.created_at
        FROM (
            SELECT q.id, q.task_id, q.run_id, q.conversation_id, q.trace_id,
                   q.data_source_id, q.dataset_id, q.data_source_revision, q.dataset_revision,
                   q.user_query, q.sql_plan_json::text AS sql_plan_json, q.sql_text, q.sql_hash,
                   q.permission_summary_json::text AS permission_summary_json,
                   q.row_count, q.result_bytes, q.result_truncated, q.status, q.error_summary,
                   q.started_at, q.finished_at, q.created_by, q.created_at
            FROM agent_data_query q
            INNER JOIN agent_data_query_result snapshot
                ON snapshot.query_id = q.id AND snapshot.created_by = q.created_by
            INNER JOIN agent_conversation conversation
                ON conversation.id = q.conversation_id AND conversation.user_id = q.created_by
               AND conversation.principal_type = 'human' AND conversation.del_flag = '0'
            WHERE q.conversation_id = #{conversationId}
              AND q.created_by = #{userId}
              AND q.trace_id LIKE 'chatbi:%'
              AND q.status = 'succeeded'
            ORDER BY q.created_at DESC, q.id DESC
            LIMIT #{limit}
        ) result
        ORDER BY result.created_at, result.id
        """)
    List<AgentDataQuery> selectOwnedStackQueries(
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId,
        @Param("limit") int limit
    );

    /**
     * 更新{@code Presentation}。
     *
     * @param queryId 资源标识
     * @param userId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     * @param chartConfigJson {@code chartConfigJson}参数
     * @param pivotConfigJson {@code pivotConfigJson}参数
     * @param updatedAt {@code updatedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_result_context
        SET chart_config_json = CAST(#{chartConfigJson} AS jsonb),
            pivot_config_json = CAST(#{pivotConfigJson} AS jsonb),
            revision_no = revision_no + 1,
            updated_at = #{updatedAt}
        WHERE query_id = #{queryId}
          AND owner_id = #{userId}
          AND revision_no = #{expectedRevision}
        """)
    int updatePresentation(
        @Param("queryId") Long queryId,
        @Param("userId") Long userId,
        @Param("expectedRevision") int expectedRevision,
        @Param("chartConfigJson") String chartConfigJson,
        @Param("pivotConfigJson") String pivotConfigJson,
        @Param("updatedAt") LocalDateTime updatedAt
    );
}
