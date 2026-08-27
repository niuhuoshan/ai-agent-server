package group.aitools.nhs.platform.knowledge.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 创建并保存事件。
 *
 * 定义知识库Metrics相关的数据访问契约。
 * Read/write projection for local knowledge retrieval operations. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface KnowledgeMetricsMapper {

    @Insert("""
        INSERT INTO agent_knowledge_retrieval_event (
            id, user_id, conversation_id, query_hash, query_length,
            knowledge_base_ids, status, citation_count, citation_document_ids,
            latency_ms, created_at
        ) VALUES (
            #{id}, #{userId}, #{conversationId}, #{queryHash}, #{queryLength},
            CAST(#{knowledgeBaseIdsJson} AS jsonb), #{status}, #{citationCount},
            CAST(#{citationDocumentIdsJson} AS jsonb), #{latencyMs}, #{createdAt}
        )
        """)
    int insertEvent(
        @Param("id") Long id,
        @Param("userId") Long userId,
        @Param("conversationId") Long conversationId,
        @Param("queryHash") String queryHash,
        @Param("queryLength") int queryLength,
        @Param("knowledgeBaseIdsJson") String knowledgeBaseIdsJson,
        @Param("status") String status,
        @Param("citationCount") int citationCount,
        @Param("citationDocumentIdsJson") String citationDocumentIdsJson,
        @Param("latencyMs") int latencyMs,
        @Param("createdAt") LocalDateTime createdAt
    );

    /**
     * 获取{@code Summary}。
     *
     * @param from {@code from}参数
     * @param to {@code to}参数
     * @param userIds 资源标识集合
     * @return 处理结果
     */
    @Select("""
        <script>
        SELECT
          COUNT(*) FILTER (WHERE e.status = 'ok') AS retrieval_count,
          COUNT(*) FILTER (WHERE e.status = 'empty') AS empty_count,
          COUNT(*) FILTER (WHERE e.status = 'failed') AS failed_count,
          COALESCE(SUM(e.citation_count), 0) AS citation_count,
          COALESCE(AVG(e.latency_ms), 0) AS average_latency_ms
        FROM agent_knowledge_retrieval_event e
        WHERE e.created_at &gt;= #{from} AND e.created_at &lt; #{to}
          AND e.user_id IN
          <foreach collection="userIds" item="userId" open="(" separator="," close=")">
            #{userId}
          </foreach>
        </script>
        """)
    Map<String, Object> selectSummary(
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        @Param("userIds") List<Long> userIds
    );

    /**
     * 获取Active用户Ids。
     *
     * @return 符合条件的数据集合
     */
    @Select("SELECT user_id FROM sys_user WHERE del_flag = '0' AND status = '0' ORDER BY user_id")
    List<Long> selectActiveUserIds();

    /**
     * 获取{@code DailyTrend}。
     *
     * @param from {@code from}参数
     * @param to {@code to}参数
     * @param userIds 资源标识集合
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT DATE(e.created_at) AS day,
               COUNT(*) FILTER (WHERE e.status = 'ok') AS retrieval_count,
               COUNT(*) FILTER (WHERE e.status = 'empty') AS empty_count,
               COUNT(*) FILTER (WHERE e.status = 'failed') AS failed_count,
               COALESCE(SUM(e.citation_count), 0) AS citation_count
        FROM agent_knowledge_retrieval_event e
        WHERE e.created_at &gt;= #{from} AND e.created_at &lt; #{to}
          AND e.user_id IN
          <foreach collection="userIds" item="userId" open="(" separator="," close=")">
            #{userId}
          </foreach>
        GROUP BY DATE(e.created_at)
        ORDER BY day
        </script>
        """)
    List<Map<String, Object>> selectDailyTrend(
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        @Param("userIds") List<Long> userIds
    );

    /**
     * 获取{@code BaseStats}。
     *
     * @param from {@code from}参数
     * @param to {@code to}参数
     * @param userIds 资源标识集合
     * @param baseIds 资源标识集合
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT b.id, b.name, b.knowledge_key,
               COALESCE(d.document_count, 0) AS document_count,
               COALESCE(c.chunk_count, 0) AS chunk_count,
               COALESCE(e.retrieval_count, 0) AS retrieval_count,
               COALESCE(e.citation_count, 0) AS citation_count
        FROM agent_knowledge_base b
        LEFT JOIN (
          SELECT knowledge_base_id, COUNT(*) AS document_count
          FROM agent_knowledge_document
          WHERE del_flag = '0'
          GROUP BY knowledge_base_id
        ) d ON d.knowledge_base_id = b.id
        LEFT JOIN (
          SELECT knowledge_base_id, COUNT(*) AS chunk_count
          FROM agent_knowledge_chunk
          WHERE status = 'active'
          GROUP BY knowledge_base_id
        ) c ON c.knowledge_base_id = b.id
        LEFT JOIN (
          SELECT expanded.base_id,
                 COUNT(*) FILTER (WHERE expanded.status = 'ok') AS retrieval_count,
                 COALESCE(SUM(expanded.citation_count), 0) AS citation_count
          FROM (
            SELECT (jsonb_array_elements_text(event.knowledge_base_ids))::bigint AS base_id,
                   event.status, event.citation_count
            FROM agent_knowledge_retrieval_event event
            WHERE event.created_at &gt;= #{from} AND event.created_at &lt; #{to}
              AND event.user_id IN
              <foreach collection="userIds" item="userId" open="(" separator="," close=")">
                #{userId}
              </foreach>
          ) expanded
          GROUP BY expanded.base_id
        ) e ON e.base_id = b.id
        WHERE b.id IN
          <foreach collection="baseIds" item="baseId" open="(" separator="," close=")">
            #{baseId}
          </foreach>
          AND b.del_flag = '0'
        ORDER BY COALESCE(e.retrieval_count, 0) DESC, b.name
        </script>
        """)
    List<Map<String, Object>> selectBaseStats(
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        @Param("userIds") List<Long> userIds,
        @Param("baseIds") List<Long> baseIds
    );

    /**
     * 获取文档Stats。
     *
     * @param baseIds 资源标识集合
     * @return 处理结果
     */
    @Select("""
        <script>
        SELECT COUNT(*) AS document_count,
               COUNT(*) FILTER (WHERE status = 'ready') AS ready_document_count,
               COUNT(*) FILTER (WHERE status = 'failed') AS failed_document_count,
               COALESCE(SUM(chunk_count), 0) AS chunk_count
        FROM agent_knowledge_document
        WHERE knowledge_base_id IN
          <foreach collection="baseIds" item="baseId" open="(" separator="," close=")">
            #{baseId}
          </foreach>
          AND del_flag = '0'
        </script>
        """)
    Map<String, Object> selectDocumentStats(@Param("baseIds") List<Long> baseIds);
}
