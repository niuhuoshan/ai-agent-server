package group.aitools.nhs.platform.nhs.portal.example;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义智能体对话BIExample相关的数据访问契约。
 * SQL access for the local ChatBI example catalogue. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
@Mapper
public interface AgentChatBIExampleMapper {

    String COLUMNS = "id, trace_id, agent_id, dataset_id, user_query, refined_query, context_summary, sql_text, "
        + "sql_metadata_json::text AS sql_metadata_json, category, enhance_status, ai_answer, feedback_type, "
        + "review_status, error_message, use_count, local_sync_status, local_sync_error, local_synced_at, "
        + "created_by, created_at, updated_at, del_flag";

    /**
     * 获取{@code ById}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, trace_id, agent_id, dataset_id, user_query, refined_query, context_summary, sql_text,
               sql_metadata_json::text AS sql_metadata_json, category, enhance_status, ai_answer, feedback_type,
               review_status, error_message, use_count, local_sync_status, local_sync_error, local_synced_at,
               created_by, created_at, updated_at, del_flag
        FROM agent_chatbi_example
        WHERE id = #{id} AND del_flag = '0'
        """)
    AgentChatBIExample selectById(@Param("id") Long id);

    /**
     * 获取By链路追踪。
     *
     * @param traceId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, trace_id, agent_id, dataset_id, user_query, refined_query, context_summary, sql_text,
               sql_metadata_json::text AS sql_metadata_json, category, enhance_status, ai_answer, feedback_type,
               review_status, error_message, use_count, local_sync_status, local_sync_error, local_synced_at,
               created_by, created_at, updated_at, del_flag
        FROM agent_chatbi_example
        WHERE trace_id = #{traceId} AND del_flag = '0'
        """)
    AgentChatBIExample selectByTrace(@Param("traceId") String traceId);

    /**
     * 获取{@code Page}。
     *
     * @param ownerId 资源标识
     * @param admin {@code admin}参数
     * @param id 资源标识
     * @param agentId 资源标识
     * @param datasetId 资源标识
     * @param reviewStatus 目标状态
     * @param category {@code category}参数
     * @param search {@code search}参数
     * @param limit 数量上限
     * @param offset 起始位置或序号
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT id, trace_id, agent_id, dataset_id, user_query, refined_query, context_summary, sql_text,
               sql_metadata_json::text AS sql_metadata_json, category, enhance_status, ai_answer, feedback_type,
               review_status, error_message, use_count, local_sync_status, local_sync_error, local_synced_at,
               created_by, created_at, updated_at, del_flag
        FROM agent_chatbi_example
        WHERE del_flag = '0'
          AND (#{admin} = TRUE OR created_by = #{ownerId})
          <if test="id != null">AND id = #{id}</if>
          <if test="agentId != null and agentId != ''">AND agent_id = #{agentId}</if>
          <if test="datasetId != null">AND dataset_id = #{datasetId}</if>
          <if test="reviewStatus != null and reviewStatus != ''">AND review_status = #{reviewStatus}</if>
          <if test="category != null and category != ''">AND category = #{category}</if>
          <if test="search != null and search != ''">
            AND (
              position(lower(#{search}) in lower(user_query)) &gt; 0
              OR position(lower(#{search}) in lower(coalesce(refined_query, ''))) &gt; 0
              OR position(lower(#{search}) in lower(sql_text)) &gt; 0
              OR position(lower(#{search}) in lower(trace_id)) &gt; 0
            )
          </if>
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<AgentChatBIExample> selectPage(
        @Param("ownerId") Long ownerId,
        @Param("admin") boolean admin,
        @Param("id") Long id,
        @Param("agentId") String agentId,
        @Param("datasetId") Long datasetId,
        @Param("reviewStatus") String reviewStatus,
        @Param("category") String category,
        @Param("search") String search,
        @Param("limit") int limit,
        @Param("offset") int offset
    );

    /**
     * 获取运行时Candidates。
     *
     * @param datasetIds 资源标识集合
     * @param datasetId 资源标识
     * @param search {@code search}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT id, trace_id, agent_id, dataset_id, user_query, refined_query, context_summary, sql_text,
               sql_metadata_json::text AS sql_metadata_json, category, enhance_status, ai_answer, feedback_type,
               review_status, error_message, use_count, local_sync_status, local_sync_error, local_synced_at,
               created_by, created_at, updated_at, del_flag
        FROM agent_chatbi_example
        WHERE del_flag = '0'
          AND review_status = 'approved'
          AND feedback_type &lt;&gt; 'down'
          AND local_sync_status = 'synced'
          AND dataset_id IN
          <foreach collection="datasetIds" item="datasetId" open="(" separator="," close=")">
            #{datasetId}
          </foreach>
          <if test="datasetId != null">AND dataset_id = #{datasetId}</if>
          <if test="search != null and search != ''">
            AND (
              position(lower(#{search}) in lower(user_query)) &gt; 0
              OR position(lower(#{search}) in lower(coalesce(refined_query, ''))) &gt; 0
              OR position(lower(#{search}) in lower(coalesce(context_summary, ''))) &gt; 0
            )
          </if>
        ORDER BY use_count DESC, created_at DESC, id DESC
        LIMIT #{limit}
        </script>
        """)
    List<AgentChatBIExample> selectRuntimeCandidates(
        @Param("datasetIds") List<Long> datasetIds,
        @Param("datasetId") Long datasetId,
        @Param("search") String search,
        @Param("limit") int limit
    );

    /**
     * 处理{@code countVisible}并返回对应结果。
     *
     * @param ownerId 资源标识
     * @param admin {@code admin}参数
     * @param id 资源标识
     * @param agentId 资源标识
     * @param datasetId 资源标识
     * @param reviewStatus 目标状态
     * @param category {@code category}参数
     * @param search {@code search}参数
     * @return 处理结果
     */
    @Select("""
        <script>
        SELECT count(*)
        FROM agent_chatbi_example
        WHERE del_flag = '0'
          AND (#{admin} = TRUE OR created_by = #{ownerId})
          <if test="id != null">AND id = #{id}</if>
          <if test="agentId != null and agentId != ''">AND agent_id = #{agentId}</if>
          <if test="datasetId != null">AND dataset_id = #{datasetId}</if>
          <if test="reviewStatus != null and reviewStatus != ''">AND review_status = #{reviewStatus}</if>
          <if test="category != null and category != ''">AND category = #{category}</if>
          <if test="search != null and search != ''">
            AND (position(lower(#{search}) in lower(user_query)) &gt; 0
              OR position(lower(#{search}) in lower(coalesce(refined_query, ''))) &gt; 0
              OR position(lower(#{search}) in lower(sql_text)) &gt; 0
              OR position(lower(#{search}) in lower(trace_id)) &gt; 0)
          </if>
        </script>
        """)
    long countVisible(
        @Param("ownerId") Long ownerId,
        @Param("admin") boolean admin,
        @Param("id") Long id,
        @Param("agentId") String agentId,
        @Param("datasetId") Long datasetId,
        @Param("reviewStatus") String reviewStatus,
        @Param("category") String category,
        @Param("search") String search
    );

    /**
     * 创建并保存{@code insert}。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_chatbi_example (
            id, trace_id, agent_id, dataset_id, user_query, refined_query, context_summary, sql_text,
            sql_metadata_json, category, enhance_status, ai_answer, feedback_type, review_status,
            error_message, use_count, local_sync_status, local_sync_error, local_synced_at,
            created_by, created_at, updated_at, del_flag
        ) VALUES (
            #{id}, #{traceId}, #{agentId}, #{datasetId}, #{userQuery}, #{refinedQuery}, #{contextSummary}, #{sqlText},
            CAST(#{sqlMetadataJson} AS jsonb), #{category}, #{enhanceStatus}, #{aiAnswer}, #{feedbackType}, #{reviewStatus},
            #{errorMessage}, #{useCount}, #{localSyncStatus}, #{localSyncError}, #{localSyncedAt},
            #{createdBy}, #{createdAt}, #{updatedAt}, '0'
        )
        """)
    int insert(AgentChatBIExample value);

    /**
     * 处理upsert反馈Candidate并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_chatbi_example (
            id, trace_id, agent_id, dataset_id, user_query, refined_query, context_summary, sql_text,
            sql_metadata_json, category, enhance_status, ai_answer, feedback_type, review_status,
            error_message, use_count, local_sync_status, local_sync_error, local_synced_at,
            created_by, created_at, updated_at, del_flag
        ) VALUES (
            #{id}, #{traceId}, #{agentId}, #{datasetId}, #{userQuery}, NULL, NULL, #{sqlText},
            CAST(#{sqlMetadataJson} AS jsonb), #{category}, 'not_requested', #{aiAnswer}, #{feedbackType}, 'pending',
            NULL, 0, 'pending', NULL, NULL, #{createdBy}, #{createdAt}, #{updatedAt}, '0'
        )
        ON CONFLICT (trace_id) DO UPDATE
        SET agent_id = EXCLUDED.agent_id, dataset_id = EXCLUDED.dataset_id,
            user_query = EXCLUDED.user_query, refined_query = NULL, context_summary = NULL,
            sql_text = EXCLUDED.sql_text, sql_metadata_json = EXCLUDED.sql_metadata_json,
            category = EXCLUDED.category, enhance_status = 'not_requested',
            ai_answer = EXCLUDED.ai_answer, feedback_type = EXCLUDED.feedback_type,
            review_status = 'pending', error_message = NULL,
            local_sync_status = 'pending', local_sync_error = NULL, local_synced_at = NULL,
            updated_at = EXCLUDED.updated_at, del_flag = '0'
        WHERE agent_chatbi_example.created_by = EXCLUDED.created_by
        """)
    int upsertFeedbackCandidate(AgentChatBIExample value);

    /**
     * 更新{@code Content}。
     *
     * @param value {@code value}参数
     * @param ownerId 资源标识
     * @param admin {@code admin}参数
     * @param expectedUpdatedAt {@code expectedUpdatedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_example
        SET user_query = #{value.userQuery}, refined_query = #{value.refinedQuery},
            context_summary = #{value.contextSummary}, sql_text = #{value.sqlText},
            sql_metadata_json = CAST(#{value.sqlMetadataJson} AS jsonb), category = #{value.category},
            review_status = #{value.reviewStatus}, local_sync_status = 'pending', local_sync_error = NULL,
            local_synced_at = NULL, updated_at = #{value.updatedAt}
        WHERE id = #{value.id} AND del_flag = '0'
          AND (#{admin} = TRUE OR created_by = #{ownerId})
          AND (#{expectedUpdatedAt} IS NULL OR updated_at = #{expectedUpdatedAt})
        """)
    int updateContent(
        @Param("value") AgentChatBIExample value,
        @Param("ownerId") Long ownerId,
        @Param("admin") boolean admin,
        @Param("expectedUpdatedAt") LocalDateTime expectedUpdatedAt
    );

    /**
     * 更新{@code Review}。
     *
     * @param id 资源标识
     * @param reviewStatus 目标状态
     * @param updatedAt {@code updatedAt}参数
     * @param expectedStatus 目标状态
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_example
        SET review_status = #{reviewStatus}, local_sync_status =
            CASE WHEN #{reviewStatus} IN ('approved', 'deprecated') THEN 'pending' ELSE 'failed' END,
            local_sync_error = NULL, updated_at = #{updatedAt}
        WHERE id = #{id} AND del_flag = '0'
          AND review_status = #{expectedStatus}
        """)
    int updateReview(
        @Param("id") Long id,
        @Param("reviewStatus") String reviewStatus,
        @Param("updatedAt") LocalDateTime updatedAt,
        @Param("expectedStatus") String expectedStatus
    );

    /**
     * 处理{@code claimEnhancement}并返回对应结果。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param admin {@code admin}参数
     * @param updatedAt {@code updatedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_example
        SET enhance_status = 'running', error_message = NULL, updated_at = #{updatedAt}
        WHERE id = #{id} AND del_flag = '0'
          AND (#{admin} = TRUE OR created_by = #{ownerId})
          AND (enhance_status &lt;&gt; 'running' OR updated_at &lt; #{updatedAt} - INTERVAL '5 minutes')
        """)
    int claimEnhancement(
        @Param("id") Long id,
        @Param("ownerId") Long ownerId,
        @Param("admin") boolean admin,
        @Param("updatedAt") LocalDateTime updatedAt
    );

    /**
     * 处理{@code completeEnhancement}并返回对应结果。
     *
     * @param id 资源标识
     * @param refinedQuery refined查询参数
     * @param contextSummary 待处理内容
     * @param sqlMetadataJson sql元数据Json参数
     * @param updatedAt {@code updatedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_example
        SET enhance_status = 'succeeded', error_message = NULL,
            refined_query = #{refinedQuery}, context_summary = #{contextSummary},
            sql_metadata_json = CAST(#{sqlMetadataJson} AS jsonb),
            review_status = 'pending',
            local_sync_status = 'pending', local_sync_error = NULL, local_synced_at = NULL,
            updated_at = #{updatedAt}
        WHERE id = #{id} AND del_flag = '0' AND enhance_status = 'running'
        """)
    int completeEnhancement(
        @Param("id") Long id,
        @Param("refinedQuery") String refinedQuery,
        @Param("contextSummary") String contextSummary,
        @Param("sqlMetadataJson") String sqlMetadataJson,
        @Param("updatedAt") LocalDateTime updatedAt
    );

    /**
     * 处理{@code failEnhancement}并返回对应结果。
     *
     * @param id 资源标识
     * @param errorMessage 待处理内容
     * @param updatedAt {@code updatedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_example
        SET enhance_status = 'failed', error_message = #{errorMessage}, updated_at = #{updatedAt}
        WHERE id = #{id} AND del_flag = '0' AND enhance_status = 'running'
        """)
    int failEnhancement(
        @Param("id") Long id,
        @Param("errorMessage") String errorMessage,
        @Param("updatedAt") LocalDateTime updatedAt
    );

    /**
     * 更新{@code LocalSync}。
     *
     * @param id 资源标识
     * @param status 目标状态
     * @param error {@code error}参数
     * @param syncedAt {@code syncedAt}参数
     * @param updatedAt {@code updatedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_example
        SET local_sync_status = #{status}, local_sync_error = #{error}, local_synced_at = #{syncedAt},
            updated_at = #{updatedAt}
        WHERE id = #{id} AND del_flag = '0'
        """)
    int updateLocalSync(
        @Param("id") Long id,
        @Param("status") String status,
        @Param("error") String error,
        @Param("syncedAt") LocalDateTime syncedAt,
        @Param("updatedAt") LocalDateTime updatedAt
    );

    /**
     * 处理{@code incrementUseCount}并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_example
        SET use_count = use_count + 1, updated_at = CURRENT_TIMESTAMP
        WHERE id = #{id} AND del_flag = '0'
        """)
    int incrementUseCount(@Param("id") Long id);

    /**
     * 删除{@code delete}。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param admin {@code admin}参数
     * @param updatedAt {@code updatedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_chatbi_example
        SET del_flag = '1', updated_at = #{updatedAt}
        WHERE id = #{id} AND del_flag = '0'
          AND (#{admin} = TRUE OR created_by = #{ownerId})
        """)
    int delete(
        @Param("id") Long id,
        @Param("ownerId") Long ownerId,
        @Param("admin") boolean admin,
        @Param("updatedAt") LocalDateTime updatedAt
    );
}
