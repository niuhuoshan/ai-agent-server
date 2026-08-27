package group.aitools.nhs.platform.audit.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.audit.domain.AgentAuditEvent;
import group.aitools.nhs.platform.execution.domain.AgentExecutionEvent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 查询{@code search}列表。
 *
 * 定义智能体审计查询相关的数据访问契约。
 * Bounded audit search. Sensitive JSON columns are never selected. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentAuditQueryMapper {

    @Select("""
        <script>
        SELECT id, trace_id, actor_type, actor_id, action, resource_type, resource_id,
               task_id, run_id, decision, decision_reason, created_at
        FROM agent_audit_event
        WHERE 1 = 1
          <if test="actorType != null">AND actor_type = #{actorType}</if>
          <if test="actorId != null">AND actor_id = #{actorId}</if>
          <if test="action != null">AND action = #{action}</if>
          <if test="resourceType != null">AND resource_type = #{resourceType}</if>
          <if test="resourceId != null">AND resource_id = #{resourceId}</if>
          <if test="taskId != null">AND task_id = #{taskId}</if>
          <if test="runId != null">AND run_id = #{runId}</if>
          <if test="decision != null">AND decision = #{decision}</if>
          <if test="createdFrom != null">AND created_at &gt;= #{createdFrom}</if>
          <if test="createdTo != null">AND created_at &lt; #{createdTo}</if>
          <if test="beforeId != null">AND id &lt; #{beforeId}</if>
        ORDER BY id DESC
        LIMIT #{limit}
        </script>
        """)
    List<AgentAuditEvent> search(
        @Param("actorType") String actorType,
        @Param("actorId") Long actorId,
        @Param("action") String action,
        @Param("resourceType") String resourceType,
        @Param("resourceId") Long resourceId,
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("decision") String decision,
        @Param("createdFrom") LocalDateTime createdFrom,
        @Param("createdTo") LocalDateTime createdTo,
        @Param("beforeId") Long beforeId,
        @Param("limit") int limit
    );

    /**
     * 获取{@code ById}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, trace_id, actor_type, actor_id, action, resource_type, resource_id,
               task_id, run_id, permission_profile_version, decision, decision_reason,
               CAST(data_scope_json AS text) AS data_scope_json,
               request_summary, result_summary, ip_address, user_agent,
               CAST(metadata_json AS text) AS metadata_json, created_at
        FROM agent_audit_event
        WHERE id = #{id}
        """)
    AgentAuditEvent selectById(@Param("id") Long id);

    /**
     * 处理{@code count}并返回对应结果。
     *
     * @param actorType 业务类型
     * @param actorId 资源标识
     * @param action {@code action}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param decision {@code decision}参数
     * @param createdFrom {@code createdFrom}参数
     * @param createdTo {@code createdTo}参数
     * @return 处理结果
     */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM agent_audit_event
        WHERE 1 = 1
          <if test="actorType != null">AND actor_type = #{actorType}</if>
          <if test="actorId != null">AND actor_id = #{actorId}</if>
          <if test="action != null">AND action = #{action}</if>
          <if test="resourceType != null">AND resource_type = #{resourceType}</if>
          <if test="resourceId != null">AND resource_id = #{resourceId}</if>
          <if test="taskId != null">AND task_id = #{taskId}</if>
          <if test="runId != null">AND run_id = #{runId}</if>
          <if test="decision != null">AND decision = #{decision}</if>
          <if test="createdFrom != null">AND created_at &gt;= #{createdFrom}</if>
          <if test="createdTo != null">AND created_at &lt; #{createdTo}</if>
        </script>
        """)
    long count(
        @Param("actorType") String actorType,
        @Param("actorId") Long actorId,
        @Param("action") String action,
        @Param("resourceType") String resourceType,
        @Param("resourceId") Long resourceId,
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("decision") String decision,
        @Param("createdFrom") LocalDateTime createdFrom,
        @Param("createdTo") LocalDateTime createdTo
    );

    /**
     * 处理统计并返回对应结果。
     *
     * @param actorType 业务类型
     * @param actorId 资源标识
     * @param action {@code action}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param decision {@code decision}参数
     * @param createdFrom {@code createdFrom}参数
     * @param createdTo {@code createdTo}参数
     * @return 处理结果
     */
    @Select("""
        <script>
        SELECT COUNT(*) AS total,
               COUNT(*) FILTER (WHERE decision = 'allow') AS allow_count,
               COUNT(*) FILTER (WHERE decision = 'deny') AS deny_count,
               COUNT(*) FILTER (WHERE decision = 'approval_required') AS approval_required_count,
               COUNT(*) FILTER (WHERE decision = 'success') AS success_count,
               COUNT(*) FILTER (WHERE decision = 'failure') AS failure_count,
               COUNT(DISTINCT NULLIF(actor_type || ':' || COALESCE(actor_id::text, ''), '')) AS distinct_actors,
               COUNT(DISTINCT trace_id) FILTER (WHERE trace_id IS NOT NULL) AS distinct_traces
        FROM agent_audit_event
        WHERE 1 = 1
          <if test="actorType != null">AND actor_type = #{actorType}</if>
          <if test="actorId != null">AND actor_id = #{actorId}</if>
          <if test="action != null">AND action = #{action}</if>
          <if test="resourceType != null">AND resource_type = #{resourceType}</if>
          <if test="resourceId != null">AND resource_id = #{resourceId}</if>
          <if test="taskId != null">AND task_id = #{taskId}</if>
          <if test="runId != null">AND run_id = #{runId}</if>
          <if test="decision != null">AND decision = #{decision}</if>
          <if test="createdFrom != null">AND created_at &gt;= #{createdFrom}</if>
          <if test="createdTo != null">AND created_at &lt; #{createdTo}</if>
        </script>
        """)
    AuditStatisticsRow statistics(
        @Param("actorType") String actorType,
        @Param("actorId") Long actorId,
        @Param("action") String action,
        @Param("resourceType") String resourceType,
        @Param("resourceId") Long resourceId,
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("decision") String decision,
        @Param("createdFrom") LocalDateTime createdFrom,
        @Param("createdTo") LocalDateTime createdTo
    );

    /**
     * 处理{@code distinctActions}并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT DISTINCT action
        FROM agent_audit_event
        WHERE action IS NOT NULL
        ORDER BY action
        LIMIT 500
        """)
    List<String> distinctActions();

    /**
     * 处理distinct资源Types并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT DISTINCT resource_type
        FROM agent_audit_event
        WHERE resource_type IS NOT NULL
        ORDER BY resource_type
        LIMIT 500
        """)
    List<String> distinctResourceTypes();

    /**
     * 处理{@code distinctActorTypes}并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT DISTINCT actor_type
        FROM agent_audit_event
        ORDER BY actor_type
        """)
    List<String> distinctActorTypes();

    /**
     * 处理{@code distinctDecisions}并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT DISTINCT decision
        FROM agent_audit_event
        ORDER BY decision
        """)
    List<String> distinctDecisions();

    /**
     * 处理导出并返回对应结果。
     *
     * @param actorType 业务类型
     * @param actorId 资源标识
     * @param action {@code action}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param decision {@code decision}参数
     * @param createdFrom {@code createdFrom}参数
     * @param createdTo {@code createdTo}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT id, trace_id, actor_type, actor_id, action, resource_type, resource_id,
               task_id, run_id, decision, decision_reason, created_at
        FROM agent_audit_event
        WHERE 1 = 1
          <if test="actorType != null">AND actor_type = #{actorType}</if>
          <if test="actorId != null">AND actor_id = #{actorId}</if>
          <if test="action != null">AND action = #{action}</if>
          <if test="resourceType != null">AND resource_type = #{resourceType}</if>
          <if test="resourceId != null">AND resource_id = #{resourceId}</if>
          <if test="taskId != null">AND task_id = #{taskId}</if>
          <if test="runId != null">AND run_id = #{runId}</if>
          <if test="decision != null">AND decision = #{decision}</if>
          <if test="createdFrom != null">AND created_at &gt;= #{createdFrom}</if>
          <if test="createdTo != null">AND created_at &lt; #{createdTo}</if>
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        </script>
        """)
    List<AgentAuditEvent> export(
        @Param("actorType") String actorType,
        @Param("actorId") Long actorId,
        @Param("action") String action,
        @Param("resourceType") String resourceType,
        @Param("resourceId") Long resourceId,
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("decision") String decision,
        @Param("createdFrom") LocalDateTime createdFrom,
        @Param("createdTo") LocalDateTime createdTo,
        @Param("limit") int limit
    );

    /**
     * 获取链路追踪Events。
     *
     * @param traceId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, event_id, trace_id, conversation_id, run_id, step_id, cursor,
               event_type, event_status, summary, CAST(payload_json AS text) AS payload_json,
               CAST(query_projection_json AS text) AS query_projection_json,
               sensitive_level, occurred_at, created_at
        FROM agent_execution_event
        WHERE trace_id = #{traceId}
        ORDER BY cursor, id
        LIMIT #{limit}
        """)
    List<AgentExecutionEvent> selectTraceEvents(
        @Param("traceId") String traceId,
        @Param("limit") int limit
    );
}
