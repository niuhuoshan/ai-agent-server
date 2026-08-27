package group.aitools.nhs.platform.search.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.search.domain.SearchInvocation;
import group.aitools.nhs.platform.search.domain.SearchProviderState;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义Search提供方相关的数据访问契约。
 */
@Mapper
public interface SearchProviderMapper {

    String CONNECTOR_COLUMNS = """
        id, connector_key, name, provider_type, scope_type, owner_id, endpoint_url,
        credential_ref, config_json::text AS config_json, status, last_check_at,
        last_error, revision_no, last_discovery_id, create_by, create_time,
        update_by, update_time, del_flag, extra_json::text AS extra_json
        """;

    /**
     * 获取{@code VisibleActiveProviders}。
     *
     * @param principalId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("SELECT " + CONNECTOR_COLUMNS + """
        FROM agent_connector
        WHERE provider_type = 'search' AND status = 'active' AND del_flag = '0'
          AND (scope_type = 'global' OR (scope_type = 'personal' AND owner_id = #{principalId}))
        ORDER BY CASE WHEN scope_type = 'personal' THEN 0 ELSE 1 END, name, id
        """)
    List<AgentConnector> selectVisibleActiveProviders(@Param("principalId") Long principalId);

    /**
     * 获取连接器。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("SELECT " + CONNECTOR_COLUMNS + " FROM agent_connector WHERE id = #{id} AND del_flag = '0'")
    AgentConnector selectConnector(@Param("id") Long id);

    /**
     * 获取{@code State}。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT connector_id, circuit_state, consecutive_failures, total_requests,
               total_failures, last_latency_ms, last_success_at, last_failure_at,
               opened_at, next_probe_at, last_error, updated_at
        FROM agent_search_provider_state
        WHERE connector_id = #{connectorId}
        """)
    SearchProviderState selectState(@Param("connectorId") Long connectorId);

    /**
     * 处理{@code acquireHalfOpenProbe}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_search_provider_state
        SET circuit_state = 'half_open', updated_at = #{now}
        WHERE connector_id = #{connectorId} AND circuit_state = 'open'
          AND (next_probe_at IS NULL OR next_probe_at <= #{now})
        """)
    int acquireHalfOpenProbe(
        @Param("connectorId") Long connectorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code markSuccess}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param latencyMs {@code latencyMs}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_search_provider_state (
            connector_id, circuit_state, consecutive_failures, total_requests,
            total_failures, last_latency_ms, last_success_at, last_failure_at,
            opened_at, next_probe_at, last_error, updated_at
        ) VALUES (
            #{connectorId}, 'closed', 0, 1, 0, #{latencyMs}, #{now}, NULL,
            NULL, NULL, NULL, #{now}
        )
        ON CONFLICT (connector_id) DO UPDATE SET
            circuit_state = 'closed', consecutive_failures = 0,
            total_requests = agent_search_provider_state.total_requests + 1,
            last_latency_ms = EXCLUDED.last_latency_ms,
            last_success_at = EXCLUDED.last_success_at,
            opened_at = NULL, next_probe_at = NULL, last_error = NULL,
            updated_at = EXCLUDED.updated_at
        """)
    int markSuccess(
        @Param("connectorId") Long connectorId,
        @Param("latencyMs") int latencyMs,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code markFailure}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param failureThreshold {@code failureThreshold}参数
     * @param latencyMs {@code latencyMs}参数
     * @param error {@code error}参数
     * @param now {@code now}参数
     * @param nextProbeAt {@code nextProbeAt}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_search_provider_state (
            connector_id, circuit_state, consecutive_failures, total_requests,
            total_failures, last_latency_ms, last_success_at, last_failure_at,
            opened_at, next_probe_at, last_error, updated_at
        ) VALUES (
            #{connectorId},
            CASE WHEN #{failureThreshold} <= 1 THEN 'open' ELSE 'closed' END,
            1, 1, 1, #{latencyMs}, NULL, #{now},
            CASE WHEN #{failureThreshold} <= 1 THEN #{now} ELSE NULL END,
            CASE WHEN #{failureThreshold} <= 1 THEN #{nextProbeAt} ELSE NULL END,
            #{error}, #{now}
        )
        ON CONFLICT (connector_id) DO UPDATE SET
            circuit_state = CASE
                WHEN agent_search_provider_state.circuit_state = 'half_open'
                  OR agent_search_provider_state.consecutive_failures + 1 >= #{failureThreshold}
                THEN 'open' ELSE 'closed' END,
            consecutive_failures = agent_search_provider_state.consecutive_failures + 1,
            total_requests = agent_search_provider_state.total_requests + 1,
            total_failures = agent_search_provider_state.total_failures + 1,
            last_latency_ms = EXCLUDED.last_latency_ms,
            last_failure_at = EXCLUDED.last_failure_at,
            opened_at = CASE
                WHEN agent_search_provider_state.circuit_state = 'half_open'
                  OR agent_search_provider_state.consecutive_failures + 1 >= #{failureThreshold}
                THEN #{now} ELSE NULL END,
            next_probe_at = CASE
                WHEN agent_search_provider_state.circuit_state = 'half_open'
                  OR agent_search_provider_state.consecutive_failures + 1 >= #{failureThreshold}
                THEN #{nextProbeAt} ELSE NULL END,
            last_error = EXCLUDED.last_error,
            updated_at = EXCLUDED.updated_at
        """)
    int markFailure(
        @Param("connectorId") Long connectorId,
        @Param("failureThreshold") int failureThreshold,
        @Param("latencyMs") int latencyMs,
        @Param("error") String error,
        @Param("now") LocalDateTime now,
        @Param("nextProbeAt") LocalDateTime nextProbeAt
    );

    /**
     * 处理{@code countRecentInvocations}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param since {@code since}参数
     * @return 处理结果
     */
    @Select("""
        SELECT count(*)
        FROM agent_search_invocation
        WHERE connector_id = #{connectorId} AND occurred_at >= #{since}
        """)
    int countRecentInvocations(
        @Param("connectorId") Long connectorId,
        @Param("since") LocalDateTime since
    );

    /**
     * 创建并保存调用。
     *
     * @param invocation 调用参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_search_invocation (
            id, connector_id, actor_id, run_id, trace_id, query_sha256,
            result_count, status, latency_ms, error_code, occurred_at
        ) VALUES (
            #{id}, #{connectorId}, #{actorId}, #{runId}, #{traceId}, #{querySha256},
            #{resultCount}, #{status}, #{latencyMs}, #{errorCode}, #{occurredAt}
        )
        """)
    int insertInvocation(SearchInvocation invocation);

    /**
     * 处理mark连接器Healthy并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param revision {@code revision}参数
     * @param checkedAt {@code checkedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_connector
        SET last_check_at = #{checkedAt}, last_error = NULL
        WHERE id = #{connectorId} AND revision_no = #{revision} AND del_flag = '0'
        """)
    int markConnectorHealthy(
        @Param("connectorId") Long connectorId,
        @Param("revision") Long revision,
        @Param("checkedAt") LocalDateTime checkedAt
    );

    /**
     * 处理mark连接器Unhealthy并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param revision {@code revision}参数
     * @param error {@code error}参数
     * @param checkedAt {@code checkedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_connector
        SET last_check_at = #{checkedAt}, last_error = #{error}
        WHERE id = #{connectorId} AND revision_no = #{revision} AND del_flag = '0'
        """)
    int markConnectorUnhealthy(
        @Param("connectorId") Long connectorId,
        @Param("revision") Long revision,
        @Param("error") String error,
        @Param("checkedAt") LocalDateTime checkedAt
    );
}
