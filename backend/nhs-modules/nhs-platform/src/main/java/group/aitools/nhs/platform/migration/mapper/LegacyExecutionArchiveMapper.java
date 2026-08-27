package group.aitools.nhs.platform.migration.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.migration.domain.LegacyExecutionArchive;

import java.util.List;

/**
 * 查询{@code search}列表。
 *
 * 定义Legacy执行Archive相关的数据访问契约。
 * Bounded read-only archive query. The redacted payload column is intentionally not selected. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface LegacyExecutionArchiveMapper {

    @Select("""
        <script>
        SELECT id, migration_run_id, source_system, source_trace_id, source_execution_id,
               source_agent_id, source_user_id, source_conversation_id, source_status,
               started_at, finished_at, summary, content_hash, created_at
          FROM agent_legacy_execution_archive
         WHERE source_system = 'nhs'
          <if test="traceId != null">AND source_trace_id = #{traceId}</if>
          <if test="executionId != null">AND source_execution_id = #{executionId}</if>
          <if test="sourceStatus != null">AND source_status = #{sourceStatus}</if>
          <if test="beforeId != null">AND id &lt; #{beforeId}</if>
         ORDER BY id DESC
         LIMIT #{limit}
        </script>
        """)
    List<LegacyExecutionArchive> search(
        @Param("traceId") String traceId,
        @Param("executionId") String executionId,
        @Param("sourceStatus") String sourceStatus,
        @Param("beforeId") Long beforeId,
        @Param("limit") int limit
    );
}
