package group.aitools.nhs.platform.execution.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.execution.domain.AgentExecutionTimelineSnapshot;

/**
 * 获取By链路追踪Id。
 *
 * 定义智能体执行时间线快照相关的数据访问契约。
 * Persistence boundary for immutable-by-content timeline projections. */
@Mapper
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentExecutionTimelineSnapshotMapper {

    @Select("""
        SELECT id, trace_id, conversation_id, task_id, run_id,
               timeline_json::text AS timeline_json, content_hash,
               generated_at, created_at, updated_at
        FROM agent_execution_timeline_snapshot
        WHERE trace_id = #{traceId}
        """)
    AgentExecutionTimelineSnapshot selectByTraceId(@Param("traceId") String traceId);

    /**
     * 处理{@code upsert}并返回对应结果。
     *
     * @param snapshot 快照参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_execution_timeline_snapshot (
            id, trace_id, conversation_id, task_id, run_id, timeline_json,
            content_hash, generated_at, created_at, updated_at
        ) VALUES (
            #{id}, #{traceId}, #{conversationId}, #{taskId}, #{runId},
            CAST(#{timelineJson} AS jsonb), #{contentHash}, #{generatedAt},
            #{createdAt}, #{updatedAt}
        )
        ON CONFLICT (trace_id) DO UPDATE SET
            conversation_id = EXCLUDED.conversation_id,
            task_id = EXCLUDED.task_id,
            run_id = EXCLUDED.run_id,
            timeline_json = EXCLUDED.timeline_json,
            content_hash = EXCLUDED.content_hash,
            generated_at = EXCLUDED.generated_at,
            updated_at = EXCLUDED.updated_at
        """)
    int upsert(AgentExecutionTimelineSnapshot snapshot);
}
