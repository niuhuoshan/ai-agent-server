package group.aitools.nhs.platform.execution.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.execution.persistence.row.AgentRunRuntimeRow;

/**
 * 定义智能体Run运行时相关的数据访问契约。
 */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentRunRuntimeMapper {

    /**
     * 获取运行时快照。
     *
     * @param runId 资源标识
     * @param traceId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_id, trace_id, status,
               CAST(runtime_snapshot_json AS text) AS runtime_snapshot_json
        FROM agent_task_run
        WHERE id = #{runId}
          AND trace_id = #{traceId}
        """)
    AgentRunRuntimeRow selectRuntimeSnapshot(
        @Param("runId") Long runId,
        @Param("traceId") String traceId
    );

    /**
     * 获取运行时快照ByRunId。
     *
     * @param runId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_id, trace_id, status,
               CAST(runtime_snapshot_json AS text) AS runtime_snapshot_json
        FROM agent_task_run
        WHERE id = #{runId}
        """)
    AgentRunRuntimeRow selectRuntimeSnapshotByRunId(@Param("runId") Long runId);

    /**
     * 获取运行时快照ForStep。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param traceId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT r.id, r.task_id, r.trace_id, r.status, r.workflow_version_id,
               s.id AS step_id, s.status AS step_status,
               COALESCE(s.runtime_snapshot_json, r.runtime_snapshot_json)::text
                   AS runtime_snapshot_json
        FROM agent_task_run r
        LEFT JOIN agent_run_step s ON s.run_id = r.id AND s.id = #{stepId}
        WHERE r.id = #{runId} AND r.trace_id = #{traceId}
        """)
    AgentRunRuntimeRow selectRuntimeSnapshotForStep(
        @Param("runId") Long runId,
        @Param("stepId") Long stepId,
        @Param("traceId") String traceId
    );

    /**
     * 获取运行时快照ByRunAndStep。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT r.id, r.task_id, r.trace_id, r.status, r.workflow_version_id,
               s.id AS step_id, s.status AS step_status,
               COALESCE(s.runtime_snapshot_json, r.runtime_snapshot_json)::text
                   AS runtime_snapshot_json
        FROM agent_task_run r
        LEFT JOIN agent_run_step s ON s.run_id = r.id AND s.id = #{stepId}
        WHERE r.id = #{runId}
        """)
    AgentRunRuntimeRow selectRuntimeSnapshotByRunAndStep(
        @Param("runId") Long runId,
        @Param("stepId") Long stepId
    );
}
