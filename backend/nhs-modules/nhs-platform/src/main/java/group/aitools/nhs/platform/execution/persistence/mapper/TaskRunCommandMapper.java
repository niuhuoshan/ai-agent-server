package group.aitools.nhs.platform.execution.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.agent.persistence.row.AgentVersionBindingRow;
import group.aitools.nhs.platform.execution.domain.AgentRunStep;
import group.aitools.nhs.platform.execution.domain.AgentTaskRun;
import group.aitools.nhs.platform.execution.persistence.row.TaskRunDefinitionRow;
import group.aitools.nhs.platform.execution.persistence.row.ConversationTaskRunRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义任务Run命令相关的数据访问契约。
 * Atomic task-run commands; every query bypasses inherited tenant and department interceptors. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface TaskRunCommandMapper {

    /**
 * 获取ActiveRunsFor会话。
 *
     * Lists only runs created by the conversation owner.  Tasks are enterprise
     * shared, but a global chat cancel must never cancel another user's run
     * merely because the task happens to be rooted in the same conversation.
     */
    @Select("""
        SELECT r.task_id, r.id AS run_id, r.trace_id, r.status
        FROM agent_task_run r
        JOIN agent_task t ON t.id = r.task_id AND t.del_flag = '0'
        WHERE t.source_conversation_id = #{conversationId}
          AND r.created_by = #{userId}
          AND r.status IN ('queued', 'preparing', 'running', 'waiting_approval',
                          'waiting_input', 'blocked', 'paused')
        ORDER BY r.created_at, r.id
        """)
    List<ConversationTaskRunRow> selectActiveRunsForConversation(
        @Param("conversationId") Long conversationId,
        @Param("userId") Long userId
    );

    /**
     * 处理lock任务并返回对应结果。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Select("SELECT #{taskId}::bigint FROM pg_advisory_xact_lock(#{taskId})")
    Long lockTask(@Param("taskId") Long taskId);

    /**
     * 获取定义。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT t.id AS task_id, tv.id AS task_version_id, t.project_id, t.owner_id,
               t.owner_principal_type,
               t.source_conversation_id, t.latest_run_id, lr.status AS latest_run_status,
               t.status AS task_status, t.orchestration_mode,
               t.title AS task_title, t.objective AS task_objective,
               t.start_at,
               tv.title AS task_version_title, tv.objective AS task_version_objective,
               tv.context_snapshot_json::text AS task_context_snapshot_json,
               tv.resource_snapshot_json::text AS task_resource_snapshot_json,
               tv.acceptance_snapshot_json::text AS task_acceptance_snapshot_json,
               tv.input_snapshot_json::text AS task_input_snapshot_json,
               tv.content_hash AS task_content_hash, t.budget_json::text AS task_budget_json,
               tv.workflow_version_id,
               av.id AS agent_version_id, av.agent_id, d.agent_key, d.name AS agent_name,
               d.status AS agent_status, av.status AS agent_version_status,
               av.published_at AS agent_published_at, av.system_prompt, av.model_id,
               av.synthesis_model_id,
               av.runtime_config_json::text AS agent_runtime_config_json,
               av.welcome_config_json::text AS agent_welcome_config_json,
               av.routing_tags_json::text AS agent_routing_tags_json,
               av.content_hash AS agent_content_hash
        FROM agent_task t
        JOIN agent_task_version tv ON tv.id = t.current_version_id AND tv.task_id = t.id
        JOIN agent_definition_version av ON av.id = tv.agent_version_id
        JOIN agent_definition d ON d.id = av.agent_id AND d.del_flag = '0'
        LEFT JOIN agent_task_run lr ON lr.id = t.latest_run_id AND lr.task_id = t.id
        WHERE t.id = #{taskId} AND t.del_flag = '0'
        """)
    TaskRunDefinitionRow selectDefinition(@Param("taskId") Long taskId);

    /**
     * 获取{@code Relations}。
     *
     * @param taskId 资源标识
     * @param principalId 资源标识
     * @param principalType 业务类型
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT DISTINCT relation
        FROM (
            SELECT 'OWNER' AS relation
            FROM agent_task
            WHERE id = #{taskId} AND owner_id = #{principalId}
              AND owner_principal_type = #{principalType} AND del_flag = '0'
            UNION ALL
            SELECT CASE participant_type
                WHEN 'owner' THEN 'OWNER'
                WHEN 'assignee' THEN 'ASSIGNEE'
                WHEN 'collaborator' THEN 'COLLABORATOR'
                WHEN 'acceptor' THEN 'ACCEPTOR'
                WHEN 'watcher' THEN 'WATCHER'
            END AS relation
            FROM agent_task_participant
            WHERE #{principalType} = 'human' AND task_id = #{taskId}
              AND user_id = #{principalId} AND status = 'active'
            UNION ALL
            SELECT 'PROJECT_ADMIN' AS relation
            FROM agent_task t
            JOIN agent_project p ON p.id = t.project_id AND p.del_flag = '0'
            LEFT JOIN agent_project_member pm
              ON pm.project_id = p.id AND pm.user_id = #{principalId} AND pm.status = 'active'
            WHERE #{principalType} = 'human' AND t.id = #{taskId} AND t.del_flag = '0'
              AND (p.owner_id = #{principalId} OR pm.member_role IN ('owner', 'manager'))
        ) relations
        WHERE relation IS NOT NULL
        """)
    List<String> selectRelations(
        @Param("taskId") Long taskId,
        @Param("principalId") Long principalId,
        @Param("principalType") String principalType
    );

    /**
     * 获取{@code Bindings}。
     *
     * @param agentVersionId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, 'tool' AS resource_type, resource_id, permission,
               config_json::text AS config_json
        FROM agent_agent_version_tool WHERE agent_version_id = #{agentVersionId}
        UNION ALL
        SELECT id, 'skill' AS resource_type, resource_id, permission,
               config_json::text AS config_json
        FROM agent_agent_version_skill WHERE agent_version_id = #{agentVersionId}
        UNION ALL
        SELECT id, 'knowledge_base' AS resource_type, resource_id, permission,
               config_json::text AS config_json
        FROM agent_agent_version_knowledge WHERE agent_version_id = #{agentVersionId}
        ORDER BY resource_type, resource_id
        """)
    List<AgentVersionBindingRow> selectBindings(@Param("agentVersionId") Long agentVersionId);

    /**
     * 获取{@code NextAttempt}。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Select("SELECT COALESCE(max(attempt_no), 0) + 1 FROM agent_task_run WHERE task_id = #{taskId}")
    int selectNextAttempt(@Param("taskId") Long taskId);

    /**
     * 获取By链路追踪。
     *
     * @param taskId 资源标识
     * @param traceId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_id, task_version_id, workflow_version_id, trace_id, status,
               attempt_no, parent_run_id, worker_id, lease_until, started_at, finished_at,
               wait_reason, error_code, error_summary, cancel_reason,
               authorization_snapshot_json::text AS authorization_snapshot_json,
               runtime_snapshot_json::text AS runtime_snapshot_json,
               budget_snapshot_json::text AS budget_snapshot_json,
               usage_json::text AS usage_json, created_by, created_at
        FROM agent_task_run
        WHERE task_id = #{taskId} AND trace_id = #{traceId}
        """)
    AgentTaskRun selectByTrace(@Param("taskId") Long taskId, @Param("traceId") String traceId);

    /**
     * 获取{@code Run}。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_id, task_version_id, workflow_version_id, trace_id, status,
               attempt_no, parent_run_id, worker_id, lease_until, started_at, finished_at,
               wait_reason, error_code, error_summary, cancel_reason,
               authorization_snapshot_json::text AS authorization_snapshot_json,
               runtime_snapshot_json::text AS runtime_snapshot_json,
               budget_snapshot_json::text AS budget_snapshot_json,
               usage_json::text AS usage_json, created_by, created_at
        FROM agent_task_run
        WHERE task_id = #{taskId} AND id = #{runId}
        """)
    AgentTaskRun selectRun(@Param("taskId") Long taskId, @Param("runId") Long runId);

    /**
     * 获取Step运行时快照。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT runtime_snapshot_json::text
        FROM agent_run_step
        WHERE run_id = #{runId} AND id = #{stepId}
        """)
    String selectStepRuntimeSnapshot(
        @Param("runId") Long runId,
        @Param("stepId") Long stepId
    );

    /**
     * 获取{@code Runs}。
     *
     * @param taskId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, task_id, task_version_id, workflow_version_id, trace_id, status,
               attempt_no, parent_run_id, worker_id, lease_until, started_at, finished_at,
               wait_reason, error_code, error_summary, cancel_reason,
               NULL::text AS authorization_snapshot_json,
               NULL::text AS runtime_snapshot_json,
               budget_snapshot_json::text AS budget_snapshot_json,
               usage_json::text AS usage_json, created_by, created_at
        FROM agent_task_run
        WHERE task_id = #{taskId}
        ORDER BY attempt_no DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentTaskRun> selectRuns(@Param("taskId") Long taskId, @Param("limit") int limit);

    /**
     * 获取{@code Steps}。
     *
     * @param runId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, run_id, step_key, parent_step_id, step_type, role_key, sequence_no,
               status, agent_version_id, tool_id, input_summary, output_summary,
               input_json::text AS input_json, output_json::text AS output_json,
               depends_on_json::text AS depends_on_json, wait_reason,
               error_code, error_summary, started_at, finished_at, retry_count, created_at
        FROM agent_run_step
        WHERE run_id = #{runId}
        ORDER BY sequence_no
        """)
    List<AgentRunStep> selectSteps(@Param("runId") Long runId);

    /**
     * 创建并保存{@code Run}。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_task_run (
            id, task_id, task_version_id, workflow_version_id, trace_id, status,
            attempt_no, parent_run_id, authorization_snapshot_json, runtime_snapshot_json,
            budget_snapshot_json, usage_json, created_by, created_at
        ) VALUES (
            #{id}, #{taskId}, #{taskVersionId}, #{workflowVersionId}, #{traceId}, #{status},
            #{attemptNo}, #{parentRunId}, CAST(#{authorizationSnapshotJson} AS jsonb),
            CAST(#{runtimeSnapshotJson} AS jsonb), CAST(#{budgetSnapshotJson} AS jsonb),
            CAST(#{usageJson} AS jsonb), #{createdBy}, #{createdAt}
        )
        """)
    int insertRun(AgentTaskRun run);

    /**
     * 创建并保存{@code Step}。
     *
     * @param step {@code step}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_run_step (
            id, run_id, step_key, parent_step_id, step_type, role_key, sequence_no, status,
            agent_version_id, tool_id, input_summary, input_json,
            runtime_template_json, runtime_snapshot_json, authorization_snapshot_json,
            depends_on_json, wait_reason, created_at
        ) VALUES (
            #{id}, #{runId}, #{stepKey}, #{parentStepId}, #{stepType}, #{roleKey},
            #{sequenceNo}, #{status}, #{agentVersionId}, #{toolId}, #{inputSummary},
            CAST(#{inputJson} AS jsonb), CAST(#{runtimeTemplateJson} AS jsonb),
            CAST(#{runtimeSnapshotJson} AS jsonb), CAST(#{authorizationSnapshotJson} AS jsonb),
            CAST(#{dependsOnJson} AS jsonb), #{waitReason}, #{createdAt}
        )
        """)
    int insertStep(AgentRunStep step);

    /**
     * 处理{@code bindLatestRun}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param taskVersionId 资源标识
     * @param runId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task
        SET latest_run_id = #{runId}, update_by = #{userId}, update_time = CURRENT_TIMESTAMP
        WHERE id = #{taskId} AND current_version_id = #{taskVersionId} AND del_flag = '0'
        """)
    int bindLatestRun(
        @Param("taskId") Long taskId,
        @Param("taskVersionId") Long taskVersionId,
        @Param("runId") Long runId,
        @Param("userId") Long userId
    );

    /**
     * 处理{@code claimRun}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param workerId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_run
        SET status = 'running', worker_id = #{workerId},
            lease_until = CURRENT_TIMESTAMP + INTERVAL '30 minutes',
            started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
            wait_reason = NULL, error_code = NULL, error_summary = NULL
        WHERE id = #{runId} AND task_id = #{taskId}
          AND (
            status = 'queued'
            OR (status IN ('preparing', 'running') AND lease_until < CURRENT_TIMESTAMP)
          )
        """)
    int claimRun(
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("workerId") String workerId
    );

    /**
     * 处理{@code startStep}并返回对应结果。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_run_step
        SET status = 'running', started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
            retry_count = retry_count + CASE WHEN status = 'running' THEN 1 ELSE 0 END
        WHERE id = #{stepId} AND run_id = #{runId} AND status IN ('pending', 'waiting', 'running')
        """)
    int startStep(@Param("runId") Long runId, @Param("stepId") Long stepId);

    /**
     * 处理mark任务Running并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task
        SET status = 'running', update_by = #{userId}, update_time = CURRENT_TIMESTAMP
        WHERE id = #{taskId} AND latest_run_id = #{runId} AND del_flag = '0'
          AND status IN ('ready', 'scheduled', 'rework', 'blocked', 'cancelled')
        """)
    int markTaskRunning(
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("userId") Long userId
    );

    /**
     * 处理{@code renewLease}并返回对应结果。
     *
     * @param runId 资源标识
     * @param workerId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_run
        SET lease_until = CURRENT_TIMESTAMP + INTERVAL '30 minutes'
        WHERE id = #{runId} AND status = 'running' AND worker_id = #{workerId}
        """)
    int renewLease(@Param("runId") Long runId, @Param("workerId") String workerId);

    /**
     * 处理{@code markWaiting}并返回对应结果。
     *
     * @param runId 资源标识
     * @param workerId 资源标识
     * @param status 目标状态
     * @param waitReason {@code waitReason}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_run
        SET status = #{status}, wait_reason = #{waitReason}, lease_until = NULL
        WHERE id = #{runId} AND worker_id = #{workerId} AND status = 'running'
        """)
    int markWaiting(
        @Param("runId") Long runId,
        @Param("workerId") String workerId,
        @Param("status") String status,
        @Param("waitReason") String waitReason
    );

    /**
     * 处理{@code markStepWaiting}并返回对应结果。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_run_step
        SET status = 'waiting'
        WHERE id = #{stepId} AND run_id = #{runId} AND status = 'running'
        """)
    int markStepWaiting(@Param("runId") Long runId, @Param("stepId") Long stepId);

    /**
     * 处理{@code markSucceeded}并返回对应结果。
     *
     * @param runId 资源标识
     * @param workerId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_run
        SET status = 'succeeded', finished_at = CURRENT_TIMESTAMP, lease_until = NULL,
            worker_id = NULL, wait_reason = NULL,
            usage_json = agent_task_run_token_usage(id)
        WHERE id = #{runId} AND worker_id = #{workerId} AND status = 'running'
        """)
    int markSucceeded(@Param("runId") Long runId, @Param("workerId") String workerId);

    /**
     * 处理{@code markStepSucceeded}并返回对应结果。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_run_step
        SET status = 'succeeded', finished_at = CURRENT_TIMESTAMP
        WHERE id = #{stepId} AND run_id = #{runId} AND status = 'running'
        """)
    int markStepSucceeded(@Param("runId") Long runId, @Param("stepId") Long stepId);

    /**
     * 处理mark任务Verifying并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task
        SET status = CASE
                WHEN lifecycle_level = 'L3_recurring_task' THEN 'scheduled'
                ELSE 'verifying'
            END,
            update_time = CURRENT_TIMESTAMP
        WHERE id = #{taskId} AND latest_run_id = #{runId} AND status = 'running' AND del_flag = '0'
        """)
    int markTaskVerifying(@Param("taskId") Long taskId, @Param("runId") Long runId);

    /**
     * 处理{@code markFailed}并返回对应结果。
     *
     * @param runId 资源标识
     * @param workerId 资源标识
     * @param errorCode {@code errorCode}参数
     * @param errorSummary {@code errorSummary}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_run
        SET status = 'failed', error_code = #{errorCode}, error_summary = #{errorSummary},
            finished_at = CURRENT_TIMESTAMP, lease_until = NULL, worker_id = NULL,
            usage_json = agent_task_run_token_usage(id)
        WHERE id = #{runId} AND worker_id = #{workerId}
          AND status IN ('preparing', 'running')
        """)
    int markFailed(
        @Param("runId") Long runId,
        @Param("workerId") String workerId,
        @Param("errorCode") String errorCode,
        @Param("errorSummary") String errorSummary
    );

    /**
     * 处理{@code failRun}并返回对应结果。
     *
     * @param runId 资源标识
     * @param errorCode {@code errorCode}参数
     * @param errorSummary {@code errorSummary}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_run
        SET status = 'failed', error_code = #{errorCode}, error_summary = #{errorSummary},
            finished_at = CURRENT_TIMESTAMP, lease_until = NULL, worker_id = NULL,
            usage_json = agent_task_run_token_usage(id)
        WHERE id = #{runId}
          AND status IN ('queued', 'preparing', 'running', 'waiting_approval',
                         'waiting_input', 'blocked', 'paused')
        """)
    int failRun(
        @Param("runId") Long runId,
        @Param("errorCode") String errorCode,
        @Param("errorSummary") String errorSummary
    );

    /**
     * 处理{@code markStepFailed}并返回对应结果。
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
    int markStepFailed(
        @Param("runId") Long runId,
        @Param("stepId") Long stepId,
        @Param("errorCode") String errorCode,
        @Param("errorSummary") String errorSummary
    );

    /**
     * 处理{@code failStep}并返回对应结果。
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
        WHERE id = #{stepId} AND run_id = #{runId}
          AND status IN ('pending', 'running', 'waiting')
        """)
    int failStep(
        @Param("runId") Long runId,
        @Param("stepId") Long stepId,
        @Param("errorCode") String errorCode,
        @Param("errorSummary") String errorSummary
    );

    /**
     * 处理mark任务Blocked并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task
        SET status = 'blocked', update_time = CURRENT_TIMESTAMP
        WHERE id = #{taskId} AND latest_run_id = #{runId} AND status = 'running' AND del_flag = '0'
        """)
    int markTaskBlocked(@Param("taskId") Long taskId, @Param("runId") Long runId);

    /**
     * 判断{@code celRun}是否满足要求。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_run
        SET status = 'cancelled', cancel_reason = #{reason}, finished_at = CURRENT_TIMESTAMP,
            lease_until = NULL, worker_id = NULL,
            usage_json = agent_task_run_token_usage(id)
        WHERE id = #{runId} AND task_id = #{taskId}
          AND status IN ('queued', 'preparing', 'running', 'waiting_approval', 'waiting_input', 'blocked', 'paused')
        """)
    int cancelRun(
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("reason") String reason
    );

    /**
     * 处理mark运行时Cancelled并返回对应结果。
     *
     * @param runId 资源标识
     * @param workerId 资源标识
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_run
        SET status = 'cancelled', cancel_reason = #{reason}, finished_at = CURRENT_TIMESTAMP,
            lease_until = NULL, worker_id = NULL,
            usage_json = agent_task_run_token_usage(id)
        WHERE id = #{runId} AND worker_id = #{workerId}
          AND status IN ('preparing', 'running')
        """)
    int markRuntimeCancelled(
        @Param("runId") Long runId,
        @Param("workerId") String workerId,
        @Param("reason") String reason
    );

    /**
     * 处理{@code pauseRun}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_run
        SET status = 'paused', wait_reason = #{reason}, lease_until = NULL, worker_id = NULL
        WHERE id = #{runId} AND task_id = #{taskId}
          AND status IN ('preparing', 'running')
        """)
    int pauseRun(
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("reason") String reason
    );

    /**
     * 处理{@code claimResumedRun}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param workerId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_run
        SET status = 'running', worker_id = #{workerId},
            lease_until = CURRENT_TIMESTAMP + INTERVAL '30 minutes', wait_reason = NULL,
            error_code = NULL, error_summary = NULL, finished_at = NULL
        WHERE id = #{runId} AND task_id = #{taskId}
          AND status IN ('paused', 'blocked', 'waiting_input')
        """)
    int claimResumedRun(
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("workerId") String workerId
    );

    /**
     * 处理{@code claimApprovedRun}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param workerId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_run
        SET status = 'running', worker_id = #{workerId},
            lease_until = CURRENT_TIMESTAMP + INTERVAL '30 minutes', wait_reason = NULL,
            error_code = NULL, error_summary = NULL, finished_at = NULL
        WHERE id = #{runId} AND task_id = #{taskId} AND status = 'waiting_approval'
        """)
    int claimApprovedRun(
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("workerId") String workerId
    );

    /**
     * 处理expire审批Run并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_run
        SET status = 'expired', error_code = 'APPROVAL_EXPIRED',
            error_summary = '工具审批已过期', finished_at = CURRENT_TIMESTAMP,
            lease_until = NULL, worker_id = NULL,
            usage_json = agent_task_run_token_usage(id)
        WHERE id = #{runId} AND task_id = #{taskId} AND status = 'waiting_approval'
        """)
    int expireApprovalRun(@Param("taskId") Long taskId, @Param("runId") Long runId);

    /**
     * 判断{@code celSteps}是否满足要求。
     *
     * @param runId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_run_step
        SET status = 'cancelled', finished_at = CURRENT_TIMESTAMP
        WHERE run_id = #{runId} AND status IN ('pending', 'running', 'waiting')
        """)
    int cancelSteps(@Param("runId") Long runId);

    /**
     * 处理mark任务Cancelled并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task
        SET status = 'cancelled', update_by = #{userId}, update_time = CURRENT_TIMESTAMP
        WHERE id = #{taskId} AND latest_run_id = #{runId} AND del_flag = '0'
          AND status IN ('ready', 'scheduled', 'running', 'blocked', 'rework')
        """)
    int markTaskCancelled(
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("userId") Long userId
    );
}
