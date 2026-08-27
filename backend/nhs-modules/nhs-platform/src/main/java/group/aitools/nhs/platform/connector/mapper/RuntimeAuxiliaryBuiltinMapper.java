package group.aitools.nhs.platform.connector.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.connector.domain.AgentRuntimeConfirmation;

import java.time.LocalDateTime;

/**
 * 处理upsertDashboard上下文并返回对应结果。
 *
 * 定义运行时AuxiliaryBuiltin相关的数据访问契约。
 * Persistence boundary for stateful Nhs-compatible auxiliary builtins. */
@Mapper
public interface RuntimeAuxiliaryBuiltinMapper {

    @Insert("""
        INSERT INTO agent_runtime_dashboard_context (
            id, owner_id, conversation_id, room_name, metric_name, time_range,
            context_json, revision_no, created_at, updated_at
        ) VALUES (
            #{id}, #{ownerId}, #{conversationId}, #{roomName}, #{metricName}, #{timeRange},
            CAST(#{contextJson} AS jsonb), 1, #{now}, #{now}
        )
        ON CONFLICT (owner_id, (COALESCE(conversation_id, 0))) DO UPDATE SET
            room_name = EXCLUDED.room_name,
            metric_name = EXCLUDED.metric_name,
            time_range = EXCLUDED.time_range,
            context_json = EXCLUDED.context_json,
            revision_no = agent_runtime_dashboard_context.revision_no + 1,
            updated_at = EXCLUDED.updated_at
        """)
    int upsertDashboardContext(
        @Param("id") Long id,
        @Param("ownerId") Long ownerId,
        @Param("conversationId") Long conversationId,
        @Param("roomName") String roomName,
        @Param("metricName") String metricName,
        @Param("timeRange") String timeRange,
        @Param("contextJson") String contextJson,
        @Param("now") LocalDateTime now
    );

    /**
     * 将输入数据转换为{@code uchScratchpad}。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param sessionKey 会话Key参数
     * @param storagePath 存储Path参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_runtime_scratchpad (
            id, owner_id, session_key, storage_path, last_used_at, created_at
        ) VALUES (#{id}, #{ownerId}, #{sessionKey}, #{storagePath}, #{now}, #{now})
        ON CONFLICT (owner_id, session_key) DO UPDATE SET
            storage_path = EXCLUDED.storage_path,
            last_used_at = EXCLUDED.last_used_at
        """)
    int touchScratchpad(
        @Param("id") Long id,
        @Param("ownerId") Long ownerId,
        @Param("sessionKey") String sessionKey,
        @Param("storagePath") String storagePath,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存{@code Confirmation}。
     *
     * @param id 资源标识
     * @param confirmationKey {@code confirmationKey}参数
     * @param ownerId 资源标识
     * @param executionId 资源标识
     * @param conversationId 资源标识
     * @param title {@code title}参数
     * @param fieldsJson {@code fieldsJson}参数
     * @param uiJson {@code uiJson}参数
     * @param expiresAt {@code expiresAt}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_runtime_confirmation (
            id, confirmation_key, owner_id, execution_id, conversation_id,
            title, fields_json, ui_json, status, expires_at, created_at, updated_at
        ) VALUES (
            #{id}, #{confirmationKey}, #{ownerId}, #{executionId}, #{conversationId},
            #{title}, CAST(#{fieldsJson} AS jsonb), CAST(#{uiJson} AS jsonb),
            'awaiting_user', #{expiresAt}, #{now}, #{now}
        )
        """)
    int insertConfirmation(
        @Param("id") Long id,
        @Param("confirmationKey") String confirmationKey,
        @Param("ownerId") Long ownerId,
        @Param("executionId") String executionId,
        @Param("conversationId") Long conversationId,
        @Param("title") String title,
        @Param("fieldsJson") String fieldsJson,
        @Param("uiJson") String uiJson,
        @Param("expiresAt") LocalDateTime expiresAt,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存{@code SuspendedConfirmation}。
     *
     * @param id 资源标识
     * @param confirmationKey {@code confirmationKey}参数
     * @param ownerId 资源标识
     * @param executionId 资源标识
     * @param conversationId 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param approvalId 资源标识
     * @param requestEventId 资源标识
     * @param replyId 资源标识
     * @param toolCallId 资源标识
     * @param toolName 名称
     * @param title {@code title}参数
     * @param fieldsJson {@code fieldsJson}参数
     * @param uiJson {@code uiJson}参数
     * @param expiresAt {@code expiresAt}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_runtime_confirmation (
            id, confirmation_key, owner_id, execution_id, conversation_id,
            task_id, run_id, step_id, approval_id, request_event_id, reply_id,
            tool_call_id, tool_name, title, fields_json, ui_json, status,
            expires_at, created_at, updated_at
        ) VALUES (
            #{id}, #{confirmationKey}, #{ownerId}, #{executionId}, #{conversationId},
            #{taskId}, #{runId}, #{stepId}, #{approvalId}, #{requestEventId}, #{replyId},
            #{toolCallId}, #{toolName}, #{title}, CAST(#{fieldsJson} AS jsonb),
            CAST(#{uiJson} AS jsonb), 'awaiting_user', #{expiresAt}, #{now}, #{now}
        )
        ON CONFLICT (confirmation_key) DO NOTHING
        """)
    int insertSuspendedConfirmation(
        @Param("id") Long id,
        @Param("confirmationKey") String confirmationKey,
        @Param("ownerId") Long ownerId,
        @Param("executionId") String executionId,
        @Param("conversationId") Long conversationId,
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("stepId") Long stepId,
        @Param("approvalId") Long approvalId,
        @Param("requestEventId") String requestEventId,
        @Param("replyId") String replyId,
        @Param("toolCallId") String toolCallId,
        @Param("toolName") String toolName,
        @Param("title") String title,
        @Param("fieldsJson") String fieldsJson,
        @Param("uiJson") String uiJson,
        @Param("expiresAt") LocalDateTime expiresAt,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存会话Confirmation。
     *
     * @param id 资源标识
     * @param confirmationKey {@code confirmationKey}参数
     * @param ownerId 资源标识
     * @param executionId 资源标识
     * @param conversationId 资源标识
     * @param conversationTurnId 资源标识
     * @param requestEventId 资源标识
     * @param replyId 资源标识
     * @param toolCallId 资源标识
     * @param toolName 名称
     * @param title {@code title}参数
     * @param fieldsJson {@code fieldsJson}参数
     * @param uiJson {@code uiJson}参数
     * @param pendingActionsJson {@code pendingActionsJson}参数
     * @param expiresAt {@code expiresAt}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_runtime_confirmation (
            id, confirmation_key, owner_id, execution_id, conversation_id,
            conversation_turn_id, request_event_id, reply_id, tool_call_id, tool_name,
            title, fields_json, ui_json, pending_actions_json, status,
            expires_at, created_at, updated_at
        ) VALUES (
            #{id}, #{confirmationKey}, #{ownerId}, #{executionId}, #{conversationId},
            #{conversationTurnId}, #{requestEventId}, #{replyId}, #{toolCallId}, #{toolName},
            #{title}, CAST(#{fieldsJson} AS jsonb), CAST(#{uiJson} AS jsonb),
            CAST(#{pendingActionsJson} AS jsonb), 'awaiting_user',
            #{expiresAt}, #{now}, #{now}
        )
        ON CONFLICT (confirmation_key) DO NOTHING
        """)
    int insertConversationConfirmation(
        @Param("id") Long id,
        @Param("confirmationKey") String confirmationKey,
        @Param("ownerId") Long ownerId,
        @Param("executionId") String executionId,
        @Param("conversationId") Long conversationId,
        @Param("conversationTurnId") Long conversationTurnId,
        @Param("requestEventId") String requestEventId,
        @Param("replyId") String replyId,
        @Param("toolCallId") String toolCallId,
        @Param("toolName") String toolName,
        @Param("title") String title,
        @Param("fieldsJson") String fieldsJson,
        @Param("uiJson") String uiJson,
        @Param("pendingActionsJson") String pendingActionsJson,
        @Param("expiresAt") LocalDateTime expiresAt,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code lockConfirmation}并返回对应结果。
     *
     * @param confirmationKey {@code confirmationKey}参数
     * @param ownerId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, confirmation_key, owner_id, execution_id, conversation_id,
               conversation_turn_id,
               task_id, run_id, step_id, approval_id, request_event_id, reply_id,
               tool_call_id, tool_name, title, fields_json::text AS fields_json,
               ui_json::text AS ui_json, pending_actions_json::text AS pending_actions_json,
               status, reviewer_id,
               decision_metadata_json::text AS decision_metadata_json,
               decision_key_hash, expires_at, decided_at, consumed_at, created_at, updated_at
        FROM agent_runtime_confirmation
        WHERE confirmation_key = #{confirmationKey} AND owner_id = #{ownerId}
        FOR UPDATE
        """)
    AgentRuntimeConfirmation lockConfirmation(
        @Param("confirmationKey") String confirmationKey,
        @Param("ownerId") Long ownerId
    );

    /**
     * 获取{@code UnconsumedConfirmed}。
     *
     * @param ownerId 资源标识
     * @param executionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, confirmation_key, owner_id, execution_id, conversation_id,
               conversation_turn_id,
               task_id, run_id, step_id, approval_id, request_event_id, reply_id,
               tool_call_id, tool_name, title, fields_json::text AS fields_json,
               ui_json::text AS ui_json, pending_actions_json::text AS pending_actions_json,
               status, reviewer_id,
               decision_metadata_json::text AS decision_metadata_json,
               decision_key_hash, expires_at, decided_at, consumed_at, created_at, updated_at
        FROM agent_runtime_confirmation
        WHERE owner_id = #{ownerId} AND execution_id = #{executionId}
          AND status = 'confirmed' AND consumed_at IS NULL
        ORDER BY decided_at DESC, id DESC
        LIMIT 1
        """)
    AgentRuntimeConfirmation selectUnconsumedConfirmed(
        @Param("ownerId") Long ownerId,
        @Param("executionId") String executionId
    );

    /**
     * 处理countBy审批Id并返回对应结果。
     *
     * @param approvalId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT count(*)
        FROM agent_runtime_confirmation
        WHERE approval_id = #{approvalId}
        """)
    int countByApprovalId(@Param("approvalId") Long approvalId);

    /**
     * 处理{@code decideConfirmation}并返回对应结果。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param status 目标状态
     * @param fieldsJson {@code fieldsJson}参数
     * @param uiJson {@code uiJson}参数
     * @param reviewerId 资源标识
     * @param metadataJson 元数据Json参数
     * @param decisionKeyHash {@code decisionKeyHash}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_runtime_confirmation
        SET status = #{status}, fields_json = CAST(#{fieldsJson} AS jsonb),
            ui_json = CAST(#{uiJson} AS jsonb), reviewer_id = #{reviewerId},
            decision_metadata_json = CAST(#{metadataJson} AS jsonb),
            decision_key_hash = #{decisionKeyHash}, decided_at = #{now}, updated_at = #{now}
        WHERE id = #{id} AND owner_id = #{ownerId} AND status = 'awaiting_user'
          AND expires_at > #{now}
        """)
    int decideConfirmation(
        @Param("id") Long id,
        @Param("ownerId") Long ownerId,
        @Param("status") String status,
        @Param("fieldsJson") String fieldsJson,
        @Param("uiJson") String uiJson,
        @Param("reviewerId") Long reviewerId,
        @Param("metadataJson") String metadataJson,
        @Param("decisionKeyHash") String decisionKeyHash,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code consumeConfirmation}并返回对应结果。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_runtime_confirmation
        SET consumed_at = #{now}, updated_at = #{now}
        WHERE id = #{id} AND owner_id = #{ownerId}
          AND status = 'confirmed' AND consumed_at IS NULL
        """)
    int consumeConfirmation(
        @Param("id") Long id,
        @Param("ownerId") Long ownerId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code expireConfirmation}并返回对应结果。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_runtime_confirmation
        SET status = 'expired', decided_at = #{now}, updated_at = #{now}
        WHERE id = #{id} AND owner_id = #{ownerId} AND status = 'awaiting_user'
          AND expires_at <= #{now}
        """)
    int expireConfirmation(
        @Param("id") Long id,
        @Param("ownerId") Long ownerId,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存{@code Delegation}。
     *
     * @param id 资源标识
     * @param delegationKey {@code delegationKey}参数
     * @param ownerId 资源标识
     * @param executionId 资源标识
     * @param conversationId 资源标识
     * @param agentName 名称
     * @param queryText 待处理内容
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_runtime_delegation (
            id, delegation_key, owner_id, execution_id, conversation_id,
            agent_name, query_text, status, created_at, updated_at
        ) VALUES (
            #{id}, #{delegationKey}, #{ownerId}, #{executionId}, #{conversationId},
            #{agentName}, #{queryText}, 'queued', #{now}, #{now}
        )
        """)
    int insertDelegation(
        @Param("id") Long id,
        @Param("delegationKey") String delegationKey,
        @Param("ownerId") Long ownerId,
        @Param("executionId") String executionId,
        @Param("conversationId") Long conversationId,
        @Param("agentName") String agentName,
        @Param("queryText") String queryText,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code markDelegationRunning}并返回对应结果。
     *
     * @param delegationKey {@code delegationKey}参数
     * @param ownerId 资源标识
     * @param parentTaskId 资源标识
     * @param parentRunId 资源标识
     * @param parentStepId 资源标识
     * @param childTaskId 资源标识
     * @param targetAgentId 资源标识
     * @param targetAgentVersionId 资源标识
     * @param childRunId 资源标识
     * @param childStepId 资源标识
     * @param childTraceId 资源标识
     * @param startedAt {@code startedAt}参数
     * @param timeoutAt {@code timeoutAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_runtime_delegation
        SET status = 'running', parent_task_id = #{parentTaskId}, parent_run_id = #{parentRunId},
            parent_step_id = #{parentStepId}, target_agent_id = #{targetAgentId},
            target_agent_version_id = #{targetAgentVersionId}, child_task_id = #{childTaskId},
            child_run_id = #{childRunId},
            child_step_id = #{childStepId}, child_trace_id = #{childTraceId},
            started_at = #{startedAt}, timeout_at = #{timeoutAt}, updated_at = #{startedAt}
        WHERE delegation_key = #{delegationKey} AND owner_id = #{ownerId} AND status = 'queued'
        """)
    int markDelegationRunning(
        @Param("delegationKey") String delegationKey,
        @Param("ownerId") Long ownerId,
        @Param("parentTaskId") Long parentTaskId,
        @Param("parentRunId") Long parentRunId,
        @Param("parentStepId") Long parentStepId,
        @Param("childTaskId") Long childTaskId,
        @Param("targetAgentId") Long targetAgentId,
        @Param("targetAgentVersionId") Long targetAgentVersionId,
        @Param("childRunId") Long childRunId,
        @Param("childStepId") Long childStepId,
        @Param("childTraceId") String childTraceId,
        @Param("startedAt") LocalDateTime startedAt,
        @Param("timeoutAt") LocalDateTime timeoutAt
    );

    /**
     * 更新{@code Delegation}。
     *
     * @param delegationKey {@code delegationKey}参数
     * @param ownerId 资源标识
     * @param status 目标状态
     * @param resultText 待处理内容
     * @param errorSummary {@code errorSummary}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_runtime_delegation
        SET status = #{status}, result_text = #{resultText}, error_summary = #{errorSummary},
            finished_at = CASE WHEN #{status} IN
                ('succeeded', 'completed', 'approval_required', 'timed_out', 'failed', 'cancelled')
                THEN #{now} ELSE finished_at END,
            updated_at = #{now}
        WHERE delegation_key = #{delegationKey} AND owner_id = #{ownerId}
          AND status IN ('queued', 'running')
        """)
    int updateDelegation(
        @Param("delegationKey") String delegationKey,
        @Param("ownerId") Long ownerId,
        @Param("status") String status,
        @Param("resultText") String resultText,
        @Param("errorSummary") String errorSummary,
        @Param("now") LocalDateTime now
    );
}
