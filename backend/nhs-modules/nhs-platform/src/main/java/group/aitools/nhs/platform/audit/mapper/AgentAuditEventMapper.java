package group.aitools.nhs.platform.audit.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 创建并保存事件。
 *
 * 定义智能体审计事件相关的数据访问契约。
 * Append-only audit writer for authorization and platform actions. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentAuditEventMapper {

    @Insert("""
        INSERT INTO agent_audit_event (
            id, actor_type, actor_id, action, resource_type, resource_id,
            task_id, decision, decision_reason, request_summary, created_at
        ) VALUES (
            #{id}, #{actorType}, #{actorId}, #{action}, #{resourceType}, #{resourceId},
            #{taskId}, #{decision}, #{decisionReason}, #{requestSummary}, #{createdAt}
        )
        """)
    int insertEvent(
        @Param("id") Long id,
        @Param("actorType") String actorType,
        @Param("actorId") Long actorId,
        @Param("action") String action,
        @Param("resourceType") String resourceType,
        @Param("resourceId") Long resourceId,
        @Param("taskId") Long taskId,
        @Param("decision") String decision,
        @Param("decisionReason") String decisionReason,
        @Param("requestSummary") String requestSummary,
        @Param("createdAt") LocalDateTime createdAt
    );

    /**
     * 创建并保存工具调用。
     *
     * @param id 资源标识
     * @param traceId 资源标识
     * @param actorType 业务类型
     * @param actorId 资源标识
     * @param toolId 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param decision {@code decision}参数
     * @param decisionReason {@code decisionReason}参数
     * @param requestSummary {@code requestSummary}参数
     * @param resultSummary 结果Summary参数
     * @param metadataJson 元数据Json参数
     * @param createdAt {@code createdAt}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_audit_event (
            id, trace_id, actor_type, actor_id, action, resource_type, resource_id,
            task_id, run_id, decision, decision_reason, request_summary,
            result_summary, metadata_json, created_at
        ) VALUES (
            #{id}, #{traceId}, #{actorType}, #{actorId}, 'invoke', 'tool', #{toolId},
            #{taskId}, #{runId}, #{decision}, #{decisionReason}, #{requestSummary},
            #{resultSummary}, CAST(#{metadataJson} AS jsonb), #{createdAt}
        )
        """)
    int insertToolInvocation(
        @Param("id") Long id,
        @Param("traceId") String traceId,
        @Param("actorType") String actorType,
        @Param("actorId") Long actorId,
        @Param("toolId") Long toolId,
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("decision") String decision,
        @Param("decisionReason") String decisionReason,
        @Param("requestSummary") String requestSummary,
        @Param("resultSummary") String resultSummary,
        @Param("metadataJson") String metadataJson,
        @Param("createdAt") LocalDateTime createdAt
    );
}
