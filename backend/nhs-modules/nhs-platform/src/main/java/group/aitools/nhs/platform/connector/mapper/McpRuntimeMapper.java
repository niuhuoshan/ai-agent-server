package group.aitools.nhs.platform.connector.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.connector.domain.McpRuntimeHealth;
import group.aitools.nhs.platform.connector.domain.McpRuntimeMount;
import group.aitools.nhs.platform.connector.domain.McpUsageDetail;
import group.aitools.nhs.platform.connector.persistence.row.McpAgentUsageRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义Mcp运行时相关的数据访问契约。
 * MyBatis facts for MCP runtime mounts, health and service usage. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface McpRuntimeMapper {

    String HEALTH_COLUMNS = """
        h.connector_id, h.health_status, h.circuit_state, h.consecutive_failures,
        h.total_connections, h.total_reconnections, h.total_invocations,
        h.total_successes, h.total_failures, h.last_success_at, h.last_failure_at,
        h.last_reconnect_at, h.circuit_open_until, h.last_latency_ms,
        h.last_error_summary, h.updated_at, h.revision_no,
        (SELECT count(*) FROM agent_mcp_runtime_mount m
          WHERE m.connector_id = h.connector_id
            AND m.status IN ('mounting', 'mounted', 'idle', 'degraded')) AS active_mount_count
        """;

    String MOUNT_COLUMNS = """
        id, connector_id, connector_revision, scope_type, scope_key, user_id,
        conversation_id, task_id, run_id, step_id, session_id, execution_id,
        trace_id, status, connection_attempts, reconnect_count, invocation_count,
        failure_count, opened_at, last_used_at, closed_at, last_error_summary
        """;

    String USAGE_COLUMNS = """
        id, mount_id, connector_id, connector_revision, tool_id, external_tool_name,
        user_id, conversation_id, task_id, run_id, step_id, session_id,
        execution_id, trace_id, status, attempt_count, latency_ms, request_bytes,
        response_bytes, error_summary, started_at, completed_at
        """;

    /**
     * 获取健康状态。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    @Select("SELECT " + HEALTH_COLUMNS + " FROM agent_mcp_runtime_health h WHERE h.connector_id = #{connectorId}")
    McpRuntimeHealth selectHealth(@Param("connectorId") Long connectorId);

    /**
     * 处理upsert健康状态并返回对应结果。
     *
     * @param health 健康状态参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_mcp_runtime_health (
            connector_id, health_status, circuit_state, consecutive_failures,
            total_connections, total_reconnections, total_invocations,
            total_successes, total_failures, last_success_at, last_failure_at,
            last_reconnect_at, circuit_open_until, last_latency_ms,
            last_error_summary, updated_at, revision_no
        ) VALUES (
            #{connectorId}, #{healthStatus}, #{circuitState}, #{consecutiveFailures},
            #{totalConnections}, #{totalReconnections}, #{totalInvocations},
            #{totalSuccesses}, #{totalFailures}, #{lastSuccessAt}, #{lastFailureAt},
            #{lastReconnectAt}, #{circuitOpenUntil}, #{lastLatencyMs},
            #{lastErrorSummary}, #{updatedAt}, #{revisionNo}
        )
        ON CONFLICT (connector_id) DO UPDATE SET
            health_status = EXCLUDED.health_status,
            circuit_state = EXCLUDED.circuit_state,
            consecutive_failures = EXCLUDED.consecutive_failures,
            total_connections = EXCLUDED.total_connections,
            total_reconnections = EXCLUDED.total_reconnections,
            total_invocations = EXCLUDED.total_invocations,
            total_successes = EXCLUDED.total_successes,
            total_failures = EXCLUDED.total_failures,
            last_success_at = EXCLUDED.last_success_at,
            last_failure_at = EXCLUDED.last_failure_at,
            last_reconnect_at = EXCLUDED.last_reconnect_at,
            circuit_open_until = EXCLUDED.circuit_open_until,
            last_latency_ms = EXCLUDED.last_latency_ms,
            last_error_summary = EXCLUDED.last_error_summary,
            updated_at = EXCLUDED.updated_at,
            revision_no = agent_mcp_runtime_health.revision_no + 1
        """)
    int upsertHealth(McpRuntimeHealth health);

    /**
     * 清理或重置健康状态。
     *
     * @param connectorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_mcp_runtime_health
        SET health_status = 'unknown', circuit_state = 'closed', consecutive_failures = 0,
            circuit_open_until = NULL, last_error_summary = NULL,
            updated_at = #{now}, revision_no = revision_no + 1
        WHERE connector_id = #{connectorId}
        """)
    int resetHealth(
        @Param("connectorId") Long connectorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存{@code Mount}。
     *
     * @param mount {@code mount}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_mcp_runtime_mount (
            id, connector_id, connector_revision, scope_type, scope_key, user_id,
            conversation_id, task_id, run_id, step_id, session_id, execution_id,
            trace_id, status, connection_attempts, reconnect_count, invocation_count,
            failure_count, opened_at, last_used_at, closed_at, last_error_summary
        ) VALUES (
            #{id}, #{connectorId}, #{connectorRevision}, #{scopeType}, #{scopeKey}, #{userId},
            #{conversationId}, #{taskId}, #{runId}, #{stepId}, #{sessionId}, #{executionId},
            #{traceId}, #{status}, #{connectionAttempts}, #{reconnectCount}, #{invocationCount},
            #{failureCount}, #{openedAt}, #{lastUsedAt}, #{closedAt}, #{lastErrorSummary}
        )
        """)
    int insertMount(McpRuntimeMount mount);

    /**
     * 获取{@code ActiveMount}。
     *
     * @param connectorId 资源标识
     * @param connectorRevision 连接器Revision参数
     * @param scopeType 业务类型
     * @param scopeKey 范围Key参数
     * @return 处理结果
     */
    @Select("""
        SELECT
        """ + MOUNT_COLUMNS + """
        FROM agent_mcp_runtime_mount
        WHERE connector_id = #{connectorId}
          AND connector_revision = #{connectorRevision}
          AND scope_type = #{scopeType}
          AND scope_key = #{scopeKey}
          AND status IN ('mounting', 'mounted', 'idle', 'degraded')
        ORDER BY opened_at DESC
        LIMIT 1
        """)
    McpRuntimeMount selectActiveMount(
        @Param("connectorId") Long connectorId,
        @Param("connectorRevision") Long connectorRevision,
        @Param("scopeType") String scopeType,
        @Param("scopeKey") String scopeKey
    );

    /**
     * 处理{@code markMountMounted}并返回对应结果。
     *
     * @param id 资源标识
     * @param reconnected {@code reconnected}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_mcp_runtime_mount
        SET status = 'mounted', connection_attempts = connection_attempts + 1,
            reconnect_count = reconnect_count + #{reconnected},
            last_used_at = #{now}, closed_at = NULL, last_error_summary = NULL
        WHERE id = #{id}
        """)
    int markMountMounted(
        @Param("id") Long id,
        @Param("reconnected") int reconnected,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code markMountActive}并返回对应结果。
     *
     * @param id 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_mcp_runtime_mount
        SET status = 'mounted', last_used_at = #{now}, closed_at = NULL
        WHERE id = #{id} AND status = 'idle'
        """)
    int markMountActive(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 处理{@code markMountConnectionFailed}并返回对应结果。
     *
     * @param id 资源标识
     * @param now {@code now}参数
     * @param errorSummary {@code errorSummary}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_mcp_runtime_mount
        SET status = 'degraded', connection_attempts = connection_attempts + 1,
            last_used_at = #{now}, last_error_summary = #{errorSummary}
        WHERE id = #{id}
        """)
    int markMountConnectionFailed(
        @Param("id") Long id,
        @Param("now") LocalDateTime now,
        @Param("errorSummary") String errorSummary
    );

    /**
     * 处理{@code markMountUsed}并返回对应结果。
     *
     * @param id 资源标识
     * @param failureIncrement {@code failureIncrement}参数
     * @param now {@code now}参数
     * @param errorSummary {@code errorSummary}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_mcp_runtime_mount
        SET invocation_count = invocation_count + 1,
            failure_count = failure_count + #{failureIncrement},
            last_used_at = #{now},
            last_error_summary = #{errorSummary}
        WHERE id = #{id}
        """)
    int markMountUsed(
        @Param("id") Long id,
        @Param("failureIncrement") int failureIncrement,
        @Param("now") LocalDateTime now,
        @Param("errorSummary") String errorSummary
    );

    /**
     * 处理{@code markMountIdle}并返回对应结果。
     *
     * @param id 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_mcp_runtime_mount
        SET status = 'idle', last_used_at = #{now}
        WHERE id = #{id} AND status IN ('mounted', 'degraded', 'mounting')
        """)
    int markMountIdle(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 处理{@code closeMount}并返回对应结果。
     *
     * @param id 资源标识
     * @param status 目标状态
     * @param now {@code now}参数
     * @param errorSummary {@code errorSummary}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_mcp_runtime_mount
        SET status = #{status}, closed_at = #{now}, last_error_summary = #{errorSummary}
        WHERE id = #{id} AND status NOT IN ('closed', 'expired', 'abandoned')
        """)
    int closeMount(
        @Param("id") Long id,
        @Param("status") String status,
        @Param("now") LocalDateTime now,
        @Param("errorSummary") String errorSummary
    );

    /**
     * 获取{@code Mounts}。
     *
     * @param connectorId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT
        """ + MOUNT_COLUMNS + """
        FROM agent_mcp_runtime_mount
        WHERE connector_id = #{connectorId}
        ORDER BY opened_at DESC
        LIMIT #{limit}
        """)
    List<McpRuntimeMount> selectMounts(
        @Param("connectorId") Long connectorId,
        @Param("limit") int limit
    );

    /**
     * 处理{@code expireIdleMounts}并返回对应结果。
     *
     * @param cutoff {@code cutoff}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_mcp_runtime_mount
        SET status = 'expired', closed_at = #{now},
            last_error_summary = '会话挂载空闲超时，已自动释放'
        WHERE status = 'idle' AND last_used_at < #{cutoff}
        """)
    int expireIdleMounts(
        @Param("cutoff") LocalDateTime cutoff,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code abandonStaleMounts}并返回对应结果。
     *
     * @param cutoff {@code cutoff}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_mcp_runtime_mount
        SET status = 'abandoned', closed_at = #{now},
            last_error_summary = '服务重启后回收遗留挂载'
        WHERE status IN ('mounting', 'mounted', 'idle', 'degraded')
          AND COALESCE(last_used_at, opened_at) < #{cutoff}
        """)
    int abandonStaleMounts(
        @Param("cutoff") LocalDateTime cutoff,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存{@code Usage}。
     *
     * @param usage {@code usage}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_mcp_usage_detail (
            id, mount_id, connector_id, connector_revision, tool_id, external_tool_name,
            user_id, conversation_id, task_id, run_id, step_id, session_id,
            execution_id, trace_id, status, attempt_count, latency_ms, request_bytes,
            response_bytes, error_summary, started_at, completed_at
        ) VALUES (
            #{id}, #{mountId}, #{connectorId}, #{connectorRevision}, #{toolId}, #{externalToolName},
            #{userId}, #{conversationId}, #{taskId}, #{runId}, #{stepId}, #{sessionId},
            #{executionId}, #{traceId}, #{status}, #{attemptCount}, #{latencyMs}, #{requestBytes},
            #{responseBytes}, #{errorSummary}, #{startedAt}, #{completedAt}
        )
        """)
    int insertUsage(McpUsageDetail usage);

    /**
     * 获取{@code Usage}。
     *
     * @param connectorId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT
        """ + USAGE_COLUMNS + """
        FROM agent_mcp_usage_detail
        WHERE connector_id = #{connectorId}
        ORDER BY started_at DESC
        LIMIT #{limit}
        """)
    List<McpUsageDetail> selectUsage(
        @Param("connectorId") Long connectorId,
        @Param("limit") int limit
    );

    /**
 * 获取智能体Usage。
 * Returns immutable Agent-version bindings that reference MCP tools on a connector. */
    @Select("""
        SELECT d.id AS agent_id, d.name AS agent_name, d.status AS agent_status,
               v.id AS agent_version_id, v.status AS agent_version_status,
               t.id AS tool_id, t.status AS tool_status, t.is_available AS tool_available
        FROM agent_agent_version_tool b
        JOIN agent_definition_version v ON v.id = b.agent_version_id
        JOIN agent_definition d ON d.id = v.agent_id AND d.del_flag = '0'
        JOIN agent_tool t ON t.id = b.resource_id
        WHERE t.connector_id = #{connectorId}
          AND t.tool_type = 'mcp'
          AND t.del_flag = '0'
        ORDER BY d.name ASC, d.id ASC, v.version_no DESC, v.id DESC
        """)
    List<McpAgentUsageRow> selectAgentUsage(@Param("connectorId") Long connectorId);
}
