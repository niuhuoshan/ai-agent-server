package group.aitools.nhs.platform.approval.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.approval.domain.AgentApprovalRequest;

import java.util.List;

/**
 * 定义审批Request相关的数据访问契约。
 */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface ApprovalRequestMapper {

    /**
     * 创建并保存{@code Request}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_approval_request (
            id, task_id, run_id, step_id, tool_id, risk_level, action_summary,
            input_summary, impact_scope, credential_ref, status, requested_by,
            expires_at, created_at, request_event_id, reply_id, pending_actions_json
        ) VALUES (
            #{id}, #{taskId}, #{runId}, #{stepId}, #{toolId}, #{riskLevel}, #{actionSummary},
            #{inputSummary}, #{impactScope}, #{credentialRef}, #{status}, #{requestedBy},
            #{expiresAt}, #{createdAt}, #{requestEventId}, #{replyId},
            CAST(#{pendingActionsJson} AS jsonb)
        )
        ON CONFLICT DO NOTHING
        """)
    int insertRequest(AgentApprovalRequest request);

    /**
     * 获取By事件Id。
     *
     * @param eventId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_id, run_id, step_id, tool_id, risk_level, action_summary,
               input_summary, impact_scope, credential_ref, status, requested_by,
               reviewer_id, review_comment, expires_at, decision_token_hash, decided_at,
               created_at, request_event_id, reply_id,
               pending_actions_json::text AS pending_actions_json,
               decision_metadata_json::text AS decision_metadata_json,
               decision_key_hash
        FROM agent_approval_request
        WHERE request_event_id = #{eventId}
        """)
    AgentApprovalRequest selectByEventId(@Param("eventId") String eventId);

    /**
     * 获取{@code Recent}。
     *
     * @param status 目标状态
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, task_id, run_id, step_id, tool_id, risk_level, action_summary,
               input_summary, impact_scope, NULL::varchar AS credential_ref,
               status, requested_by, reviewer_id, review_comment, expires_at,
               NULL::char(64) AS decision_token_hash, decided_at, created_at,
               request_event_id, reply_id, NULL::text AS pending_actions_json,
               decision_metadata_json::text AS decision_metadata_json,
               decision_key_hash
        FROM agent_approval_request
        WHERE (#{status} IS NULL OR status = #{status})
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentApprovalRequest> selectRecent(
        @Param("status") String status,
        @Param("limit") int limit
    );

    /**
     * 获取{@code ById}。
     *
     * @param approvalId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_id, run_id, step_id, tool_id, risk_level, action_summary,
               input_summary, impact_scope, NULL::varchar AS credential_ref,
               status, requested_by, reviewer_id, review_comment, expires_at,
               NULL::char(64) AS decision_token_hash, decided_at, created_at,
               request_event_id, NULL::varchar AS reply_id,
               NULL::text AS pending_actions_json,
               decision_metadata_json::text AS decision_metadata_json,
               decision_key_hash
        FROM agent_approval_request
        WHERE id = #{approvalId}
        """)
    AgentApprovalRequest selectById(@Param("approvalId") Long approvalId);

    /**
     * 处理{@code lockById}并返回对应结果。
     *
     * @param approvalId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_id, run_id, step_id, tool_id, risk_level, action_summary,
               input_summary, impact_scope, credential_ref, status, requested_by,
               reviewer_id, review_comment, expires_at, decision_token_hash, decided_at,
               created_at, request_event_id, reply_id,
               pending_actions_json::text AS pending_actions_json,
               decision_metadata_json::text AS decision_metadata_json,
               decision_key_hash
        FROM agent_approval_request
        WHERE id = #{approvalId}
        FOR UPDATE
        """)
    AgentApprovalRequest lockById(@Param("approvalId") Long approvalId);

    /**
     * 处理{@code decide}并返回对应结果。
     *
     * @param approvalId 资源标识
     * @param status 目标状态
     * @param reviewerId 资源标识
     * @param comment {@code comment}参数
     * @param metadataJson 元数据Json参数
     * @param decisionKeyHash {@code decisionKeyHash}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_approval_request
        SET status = #{status}, reviewer_id = #{reviewerId}, review_comment = #{comment},
            decision_metadata_json = CAST(#{metadataJson} AS jsonb),
            decision_key_hash = #{decisionKeyHash}, decided_at = CURRENT_TIMESTAMP
        WHERE id = #{approvalId} AND status = 'pending'
          AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
        """)
    int decide(
        @Param("approvalId") Long approvalId,
        @Param("status") String status,
        @Param("reviewerId") Long reviewerId,
        @Param("comment") String comment,
        @Param("metadataJson") String metadataJson,
        @Param("decisionKeyHash") String decisionKeyHash
    );

    /**
     * 处理{@code expire}并返回对应结果。
     *
     * @param approvalId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_approval_request
        SET status = 'expired', decided_at = CURRENT_TIMESTAMP
        WHERE id = #{approvalId} AND status = 'pending'
          AND expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP
        """)
    int expire(@Param("approvalId") Long approvalId);
}
