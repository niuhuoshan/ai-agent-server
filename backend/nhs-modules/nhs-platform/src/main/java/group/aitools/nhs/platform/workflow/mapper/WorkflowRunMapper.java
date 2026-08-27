package group.aitools.nhs.platform.workflow.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.execution.persistence.row.WorkflowAgentDefinitionRow;
import group.aitools.nhs.platform.workflow.persistence.row.WorkflowRunStepRow;

import java.util.List;

/**
 * 定义工作流Run相关的数据访问契约。
 */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface WorkflowRunMapper {

    /**
     * 获取智能体定义。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT av.id AS agent_version_id, av.agent_id, d.agent_key, d.name AS agent_name,
               d.status AS agent_status, av.status AS agent_version_status,
               av.published_at AS agent_published_at, av.system_prompt, av.model_id,
               av.synthesis_model_id,
               av.runtime_config_json::text AS agent_runtime_config_json,
               av.welcome_config_json::text AS agent_welcome_config_json,
               av.routing_tags_json::text AS agent_routing_tags_json,
               av.content_hash AS agent_content_hash
        FROM agent_definition_version av
        JOIN agent_definition d ON d.id = av.agent_id AND d.del_flag = '0'
        WHERE av.id = #{versionId}
        """)
    WorkflowAgentDefinitionRow selectAgentDefinition(@Param("versionId") Long versionId);

    /**
     * 处理{@code lockSteps}并返回对应结果。
     *
     * @param runId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, run_id, step_key, step_type, role_key, sequence_no, status,
               agent_version_id, depends_on_json::text AS depends_on_json,
               runtime_template_json::text AS runtime_template_json,
               runtime_snapshot_json::text AS runtime_snapshot_json,
               authorization_snapshot_json::text AS authorization_snapshot_json,
               input_json::text AS input_json, output_summary,
               output_json::text AS output_json, wait_reason
        FROM agent_run_step
        WHERE run_id = #{runId}
        ORDER BY sequence_no
        FOR UPDATE
        """)
    List<WorkflowRunStepRow> lockSteps(@Param("runId") Long runId);

    /**
     * 获取{@code Step}。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, run_id, step_key, step_type, role_key, sequence_no, status,
               agent_version_id, depends_on_json::text AS depends_on_json,
               runtime_template_json::text AS runtime_template_json,
               runtime_snapshot_json::text AS runtime_snapshot_json,
               authorization_snapshot_json::text AS authorization_snapshot_json,
               input_json::text AS input_json, output_summary,
               output_json::text AS output_json, wait_reason
        FROM agent_run_step
        WHERE run_id = #{runId} AND id = #{stepId}
        """)
    WorkflowRunStepRow selectStep(
        @Param("runId") Long runId,
        @Param("stepId") Long stepId
    );

    /**
     * 获取{@code ActiveSteps}。
     *
     * @param runId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, run_id, step_key, step_type, role_key, sequence_no, status,
               agent_version_id, depends_on_json::text AS depends_on_json,
               runtime_template_json::text AS runtime_template_json,
               runtime_snapshot_json::text AS runtime_snapshot_json,
               authorization_snapshot_json::text AS authorization_snapshot_json,
               input_json::text AS input_json, output_summary,
               output_json::text AS output_json, wait_reason
        FROM agent_run_step
        WHERE run_id = #{runId} AND status IN ('running', 'waiting')
          AND runtime_snapshot_json IS NOT NULL
        ORDER BY sequence_no
        """)
    List<WorkflowRunStepRow> selectActiveSteps(@Param("runId") Long runId);

    /**
     * 获取{@code RunStatus}。
     *
     * @param runId 资源标识
     * @return 处理结果
     */
    @Select("SELECT status FROM agent_task_run WHERE id = #{runId}")
    String selectRunStatus(@Param("runId") Long runId);

    /**
     * 处理{@code materializeAndStart}并返回对应结果。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param runtimeJson 运行时Json参数
     * @param inputJson {@code inputJson}参数
     * @param inputSummary {@code inputSummary}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_run_step
        SET runtime_snapshot_json = CAST(#{runtimeJson} AS jsonb),
            status = 'running', wait_reason = NULL,
            started_at = COALESCE(started_at, CURRENT_TIMESTAMP)
        WHERE id = #{stepId} AND run_id = #{runId} AND status = 'pending'
          AND runtime_template_json IS NOT NULL
        """)
    int materializeAndStart(
        @Param("runId") Long runId,
        @Param("stepId") Long stepId,
        @Param("runtimeJson") String runtimeJson,
        @Param("inputJson") String inputJson,
        @Param("inputSummary") String inputSummary
    );

    /**
     * 处理{@code resumeStep}并返回对应结果。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_run_step
        SET status = 'running', wait_reason = NULL,
            started_at = COALESCE(started_at, CURRENT_TIMESTAMP), retry_count = retry_count + 1
        WHERE id = #{stepId} AND run_id = #{runId} AND status = 'waiting'
          AND runtime_snapshot_json IS NOT NULL
        """)
    int resumeStep(@Param("runId") Long runId, @Param("stepId") Long stepId);

    /**
     * 处理{@code pauseRunningSteps}并返回对应结果。
     *
     * @param runId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_run_step
        SET status = 'waiting', wait_reason = 'user_pause'
        WHERE run_id = #{runId} AND status = 'running'
          AND runtime_snapshot_json IS NOT NULL
        """)
    int pauseRunningSteps(@Param("runId") Long runId);

    /**
     * 处理{@code recordOutput}并返回对应结果。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param summary {@code summary}参数
     * @param outputJson {@code outputJson}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_run_step
        SET output_summary = #{summary}, output_json = CAST(#{outputJson} AS jsonb)
        WHERE id = #{stepId} AND run_id = #{runId} AND status = 'running'
        """)
    int recordOutput(
        @Param("runId") Long runId,
        @Param("stepId") Long stepId,
        @Param("summary") String summary,
        @Param("outputJson") String outputJson
    );

    /**
     * 处理{@code markWaiting}并返回对应结果。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param waitReason {@code waitReason}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_run_step
        SET status = 'waiting', wait_reason = #{waitReason}
        WHERE id = #{stepId} AND run_id = #{runId} AND status = 'running'
        """)
    int markWaiting(
        @Param("runId") Long runId,
        @Param("stepId") Long stepId,
        @Param("waitReason") String waitReason
    );

    /**
     * 处理{@code markSucceeded}并返回对应结果。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_run_step
        SET status = 'succeeded', wait_reason = NULL, finished_at = CURRENT_TIMESTAMP
        WHERE id = #{stepId} AND run_id = #{runId} AND status = 'running'
        """)
    int markSucceeded(@Param("runId") Long runId, @Param("stepId") Long stepId);

    /**
     * 处理{@code completeAggregate}并返回对应结果。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param summary {@code summary}参数
     * @param outputJson {@code outputJson}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_run_step
        SET status = 'succeeded', output_summary = #{summary},
            output_json = CAST(#{outputJson} AS jsonb), finished_at = CURRENT_TIMESTAMP
        WHERE id = #{stepId} AND run_id = #{runId}
          AND status = 'pending' AND step_type = 'aggregate'
        """)
    int completeAggregate(
        @Param("runId") Long runId,
        @Param("stepId") Long stepId,
        @Param("summary") String summary,
        @Param("outputJson") String outputJson
    );

    /**
     * 处理{@code failRunningStep}并返回对应结果。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param errorCode {@code errorCode}参数
     * @param errorSummary {@code errorSummary}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_run_step
        SET status = 'failed', error_code = #{errorCode}, error_summary = #{errorSummary},
            finished_at = CURRENT_TIMESTAMP
        WHERE id = #{stepId} AND run_id = #{runId} AND status = 'running'
        """)
    int failRunningStep(
        @Param("runId") Long runId,
        @Param("stepId") Long stepId,
        @Param("errorCode") String errorCode,
        @Param("errorSummary") String errorSummary
    );

    /**
     * 处理项目RunStatus并返回对应结果。
     *
     * @param runId 资源标识
     * @param status 目标状态
     * @param waitReason {@code waitReason}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_run
        SET status = #{status}, wait_reason = #{waitReason},
            lease_until = CASE WHEN #{status} = 'running'
                THEN CURRENT_TIMESTAMP + INTERVAL '30 minutes' ELSE NULL END
        WHERE id = #{runId} AND workflow_version_id IS NOT NULL
          AND status IN ('preparing', 'running', 'waiting_approval', 'waiting_input', 'paused', 'blocked')
        """)
    int projectRunStatus(
        @Param("runId") Long runId,
        @Param("status") String status,
        @Param("waitReason") String waitReason
    );

    /**
     * 处理{@code finishRun}并返回对应结果。
     *
     * @param runId 资源标识
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_run
        SET status = 'succeeded', finished_at = CURRENT_TIMESTAMP, lease_until = NULL,
            worker_id = NULL, wait_reason = NULL
        WHERE id = #{runId} AND task_id = #{taskId} AND workflow_version_id IS NOT NULL
          AND status IN ('preparing', 'running', 'waiting_approval', 'waiting_input', 'blocked')
        """)
    int finishRun(@Param("runId") Long runId, @Param("taskId") Long taskId);
}
