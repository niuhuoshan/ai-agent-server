package group.aitools.nhs.platform.connector.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.domain.AgentMcpDiscovery;
import group.aitools.nhs.platform.connector.domain.AgentTool;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义连接器目录相关的数据访问契约。
 * Connector, versioned tool and MCP discovery persistence. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface ConnectorCatalogMapper {

    String CONNECTOR_COLUMNS = """
        id, connector_key, name, provider_type, scope_type, owner_id, endpoint_url, credential_ref,
        config_json::text AS config_json, status, last_check_at, last_error,
        revision_no, last_discovery_id, create_by, create_time, update_by,
        update_time, del_flag, extra_json::text AS extra_json
        """;

    String TOOL_COLUMNS = """
        id, tool_key, name, description, connector_id, tool_type, risk_level,
        parameter_schema_json::text AS parameter_schema_json,
        execution_policy_json::text AS execution_policy_json, external_name,
        status, version_no, discovery_id, remote_schema_hash, is_available,
        create_by, create_time, update_by, update_time, del_flag,
        extra_json::text AS extra_json,
        (SELECT count(*)
           FROM agent_agent_version_tool usage_binding
           JOIN agent_tool usage_tool ON usage_tool.id = usage_binding.resource_id
          WHERE usage_tool.tool_key = agent_tool.tool_key) AS usage_count
        """;

    /**
     * 获取{@code Connectors}。
     *
     * @param providerType 业务类型
     * @param search {@code search}参数
     * @param includeInactive {@code includeInactive}参数
     * @param scope 范围参数
     * @param principalId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT
        """ + CONNECTOR_COLUMNS + """
        FROM agent_connector
        WHERE del_flag = '0'
        <if test="providerType != null and providerType != ''">
          AND provider_type = #{providerType}
        </if>
        <if test="!includeInactive">
          AND status = 'active'
        </if>
        <if test="scope != null and scope != ''">
          AND scope_type = #{scope}
        </if>
        AND (scope_type = 'global' OR (scope_type = 'personal' AND owner_id = #{principalId}))
        <if test="search != null and search != ''">
          AND (position(lower(#{search}) in lower(name)) &gt; 0
               OR position(lower(#{search}) in lower(connector_key)) &gt; 0)
        </if>
        ORDER BY name ASC, id ASC
        LIMIT #{limit}
        </script>
        """)
    List<AgentConnector> selectConnectors(
        @Param("providerType") String providerType,
        @Param("search") String search,
        @Param("includeInactive") boolean includeInactive,
        @Param("scope") String scope,
        @Param("principalId") Long principalId,
        @Param("limit") int limit
    );

    /**
     * 获取连接器ById。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("SELECT " + CONNECTOR_COLUMNS + " FROM agent_connector WHERE id = #{id} AND del_flag = '0'")
    AgentConnector selectConnectorById(@Param("id") Long id);

    /**
     * 处理lock连接器并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("SELECT id FROM agent_connector WHERE id = #{id} AND del_flag = '0' FOR UPDATE")
    Long lockConnector(@Param("id") Long id);

    /**
     * 创建并保存连接器。
     *
     * @param connector 连接器参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_connector (
            id, connector_key, name, provider_type, scope_type, owner_id, endpoint_url, credential_ref,
            config_json, status, revision_no, create_by, create_time, del_flag, extra_json
        ) VALUES (
            #{id}, #{connectorKey}, #{name}, #{providerType}, #{scopeType}, #{ownerId},
            #{endpointUrl}, #{credentialRef},
            CAST(#{configJson} AS jsonb), #{status}, #{revisionNo}, #{createBy}, #{createTime},
            '0', CAST(#{extraJson} AS jsonb)
        )
        """)
    int insertConnector(AgentConnector connector);

    /**
     * 更新连接器。
     *
     * @param connector 连接器参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_connector
        SET name = #{name}, provider_type = #{providerType}, endpoint_url = #{endpointUrl},
            credential_ref = #{credentialRef}, config_json = CAST(#{configJson} AS jsonb),
            status = #{status}, revision_no = revision_no + 1,
            update_by = #{updateBy}, update_time = #{updateTime}
        WHERE id = #{id} AND del_flag = '0' AND revision_no = #{revisionNo}
        """)
    int updateConnector(AgentConnector connector);

    /**
     * 处理invalidate连接器Tools并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_tool
        SET status = 'deprecated', is_available = FALSE,
            update_by = #{actorId}, update_time = #{now}
        WHERE connector_id = #{connectorId} AND del_flag = '0'
          AND (status <> 'deprecated' OR is_available = TRUE)
        """)
    int invalidateConnectorTools(
        @Param("connectorId") Long connectorId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 清理或重置连接器Discovery。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_connector
        SET last_discovery_id = NULL, last_error = NULL
        WHERE id = #{connectorId} AND del_flag = '0'
        """)
    int clearConnectorDiscovery(@Param("connectorId") Long connectorId);

    /**
     * 处理softDelete连接器并返回对应结果。
     *
     * @param id 资源标识
     * @param revision {@code revision}参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_connector
        SET status = 'disabled', del_flag = '1', revision_no = revision_no + 1,
            update_by = #{actorId}, update_time = #{now}
        WHERE id = #{id} AND del_flag = '0' AND revision_no = #{revision}
        """)
    int softDeleteConnector(
        @Param("id") Long id,
        @Param("revision") Long revision,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理count连接器Tools并返回对应结果。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    @Select("SELECT count(*) FROM agent_tool WHERE connector_id = #{connectorId} AND del_flag = '0'")
    int countConnectorTools(@Param("connectorId") Long connectorId);

    /**
     * 处理count连接器ManagedTools并返回对应结果。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT count(*) FROM agent_tool
        WHERE connector_id = #{connectorId} AND del_flag = '0' AND tool_type <> 'mcp'
        """)
    int countConnectorManagedTools(@Param("connectorId") Long connectorId);

    /**
     * 创建并保存{@code Discovery}。
     *
     * @param discovery {@code discovery}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_mcp_discovery (
            id, connector_id, connector_revision, status, tool_count,
            started_by, started_at
        ) VALUES (
            #{id}, #{connectorId}, #{connectorRevision}, #{status}, #{toolCount},
            #{startedBy}, #{startedAt}
        )
        """)
    int insertDiscovery(AgentMcpDiscovery discovery);

    /**
     * 处理{@code failStaleDiscoveries}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param cutoff {@code cutoff}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_mcp_discovery
        SET status = 'failed', error_summary = '发现任务超时，已自动回收', completed_at = #{now}
        WHERE connector_id = #{connectorId} AND status = 'running' AND started_at < #{cutoff}
        """)
    int failStaleDiscoveries(
        @Param("connectorId") Long connectorId,
        @Param("cutoff") LocalDateTime cutoff,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取{@code Discoveries}。
     *
     * @param connectorId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, connector_id, connector_revision, status, protocol_version,
               server_info_json::text AS server_info_json, tool_count, content_hash,
               error_summary, started_by, started_at, completed_at
        FROM agent_mcp_discovery
        WHERE connector_id = #{connectorId}
        ORDER BY started_at DESC
        LIMIT #{limit}
        """)
    List<AgentMcpDiscovery> selectDiscoveries(
        @Param("connectorId") Long connectorId,
        @Param("limit") int limit
    );

    /**
     * 处理{@code completeDiscovery}并返回对应结果。
     *
     * @param discovery {@code discovery}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_mcp_discovery
        SET status = 'succeeded', protocol_version = #{protocolVersion},
            server_info_json = CAST(#{serverInfoJson} AS jsonb), tool_count = #{toolCount},
            content_hash = #{contentHash}, error_summary = NULL, completed_at = #{completedAt}
        WHERE id = #{id} AND status = 'running'
        """)
    int completeDiscovery(AgentMcpDiscovery discovery);

    /**
     * 处理{@code failDiscovery}并返回对应结果。
     *
     * @param discovery {@code discovery}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_mcp_discovery
        SET status = 'failed', error_summary = #{errorSummary}, completed_at = #{completedAt}
        WHERE id = #{id} AND status = 'running'
        """)
    int failDiscovery(AgentMcpDiscovery discovery);

    /**
     * 处理{@code markDiscoverySucceeded}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param connectorRevision 连接器Revision参数
     * @param discoveryId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_connector
        SET last_discovery_id = #{discoveryId}, last_check_at = #{now}, last_error = NULL
        WHERE id = #{connectorId} AND del_flag = '0' AND revision_no = #{connectorRevision}
        """)
    int markDiscoverySucceeded(
        @Param("connectorId") Long connectorId,
        @Param("connectorRevision") Long connectorRevision,
        @Param("discoveryId") Long discoveryId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code markDiscoveryFailed}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param connectorRevision 连接器Revision参数
     * @param errorSummary {@code errorSummary}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_connector
        SET last_check_at = #{now}, last_error = #{errorSummary}
        WHERE id = #{connectorId} AND del_flag = '0' AND revision_no = #{connectorRevision}
        """)
    int markDiscoveryFailed(
        @Param("connectorId") Long connectorId,
        @Param("connectorRevision") Long connectorRevision,
        @Param("errorSummary") String errorSummary,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理mark连接器CheckSucceeded并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param connectorRevision 连接器Revision参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_connector
        SET last_check_at = #{now}, last_error = NULL
        WHERE id = #{connectorId} AND del_flag = '0' AND revision_no = #{connectorRevision}
        """)
    int markConnectorCheckSucceeded(
        @Param("connectorId") Long connectorId,
        @Param("connectorRevision") Long connectorRevision,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理mark连接器CheckFailed并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param connectorRevision 连接器Revision参数
     * @param errorSummary {@code errorSummary}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_connector
        SET last_check_at = #{now}, last_error = #{errorSummary}
        WHERE id = #{connectorId} AND del_flag = '0' AND revision_no = #{connectorRevision}
        """)
    int markConnectorCheckFailed(
        @Param("connectorId") Long connectorId,
        @Param("connectorRevision") Long connectorRevision,
        @Param("errorSummary") String errorSummary,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取工具ById。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT
        """ + TOOL_COLUMNS + """
        FROM agent_tool
        WHERE id = #{id} AND del_flag = '0'
        """)
    AgentTool selectToolById(@Param("id") Long id);

    /**
     * 获取LatestRemote工具。
     *
     * @param connectorId 资源标识
     * @param externalName 名称
     * @return 处理结果
     */
    @Select("""
        SELECT
        """ + TOOL_COLUMNS + """
        FROM agent_tool
        WHERE connector_id = #{connectorId} AND external_name = #{externalName}
          AND del_flag = '0'
        ORDER BY version_no DESC
        LIMIT 1
        """)
    AgentTool selectLatestRemoteTool(
        @Param("connectorId") Long connectorId,
        @Param("externalName") String externalName
    );

    /**
     * 获取Latest连接器Tools。
     *
     * @param connectorId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT DISTINCT ON (tool_key)
        """ + TOOL_COLUMNS + """
        FROM agent_tool
        WHERE connector_id = #{connectorId} AND del_flag = '0'
        ORDER BY tool_key, version_no DESC
        """)
    List<AgentTool> selectLatestConnectorTools(@Param("connectorId") Long connectorId);

    /**
     * 获取{@code LatestTools}。
     *
     * @param toolType 业务类型
     * @param connectorId 资源标识
     * @param search {@code search}参数
     * @param includeInactive {@code includeInactive}参数
     * @param principalId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT DISTINCT ON (tool_key)
        """ + TOOL_COLUMNS + """
        FROM agent_tool
        WHERE del_flag = '0'
        AND (connector_id IS NULL OR EXISTS (
          SELECT 1 FROM agent_connector c
          WHERE c.id = agent_tool.connector_id AND c.del_flag = '0'
            <if test="!includeInactive">AND c.status = 'active'</if>
            AND (c.scope_type = 'global' OR (c.scope_type = 'personal' AND c.owner_id = #{principalId}))
        ))
        <if test="toolType != null and toolType != ''">AND tool_type = #{toolType}</if>
        <if test="connectorId != null">AND connector_id = #{connectorId}</if>
        <if test="!includeInactive">AND status = 'active' AND is_available = TRUE</if>
        <if test="search != null and search != ''">
          AND (position(lower(#{search}) in lower(name)) &gt; 0
               OR position(lower(#{search}) in lower(tool_key)) &gt; 0)
        </if>
        ORDER BY tool_key, version_no DESC
        LIMIT #{limit}
        </script>
        """)
    List<AgentTool> selectLatestTools(
        @Param("toolType") String toolType,
        @Param("connectorId") Long connectorId,
        @Param("search") String search,
        @Param("includeInactive") boolean includeInactive,
        @Param("principalId") Long principalId,
        @Param("limit") int limit
    );

    /**
     * 获取工具Versions。
     *
     * @param toolKey 工具Key参数
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT
        """ + TOOL_COLUMNS + """
        FROM agent_tool
        WHERE tool_key = #{toolKey} AND del_flag = '0'
        ORDER BY version_no DESC
        """)
    List<AgentTool> selectToolVersions(@Param("toolKey") String toolKey);

    /**
     * 处理lock工具Key并返回对应结果。
     *
     * @param toolKey 工具Key参数
     * @return 处理结果
     */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{toolKey}, 0))")
    Long lockToolKey(@Param("toolKey") String toolKey);

    /**
     * 获取Next工具版本。
     *
     * @param toolKey 工具Key参数
     * @return 处理结果
     */
    @Select("SELECT COALESCE(max(version_no), 0) + 1 FROM agent_tool WHERE tool_key = #{toolKey}")
    int selectNextToolVersion(@Param("toolKey") String toolKey);

    /**
     * 创建并保存工具。
     *
     * @param tool 工具参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_tool (
            id, tool_key, name, description, connector_id, tool_type, risk_level,
            parameter_schema_json, execution_policy_json, external_name, status,
            version_no, discovery_id, remote_schema_hash, is_available,
            create_by, create_time, del_flag, extra_json
        ) VALUES (
            #{id}, #{toolKey}, #{name}, #{description}, #{connectorId}, #{toolType}, #{riskLevel},
            CAST(#{parameterSchemaJson} AS jsonb), CAST(#{executionPolicyJson} AS jsonb),
            #{externalName}, #{status}, #{versionNo}, #{discoveryId}, #{remoteSchemaHash},
            #{isAvailable}, #{createBy}, #{createTime}, '0', CAST(#{extraJson} AS jsonb)
        )
        """)
    int insertTool(AgentTool tool);

    /**
     * 处理markRemote工具Seen并返回对应结果。
     *
     * @param id 资源标识
     * @param discoveryId 资源标识
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_tool
        SET discovery_id = #{discoveryId}, is_available = TRUE,
            update_by = #{actorId}, update_time = #{now}
        WHERE id = #{id} AND del_flag = '0'
        """)
    int markRemoteToolSeen(
        @Param("id") Long id,
        @Param("discoveryId") Long discoveryId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
 * 处理recoverRemote工具并返回对应结果。
 * Restores a remotely reappeared tool only when it belongs to the current connector revision. */
    @Update("""
        UPDATE agent_tool
        SET status = 'disabled', is_available = TRUE, discovery_id = #{discoveryId},
            update_by = #{actorId}, update_time = #{now}
        WHERE id = #{id} AND del_flag = '0'
          AND remote_schema_hash = #{schemaHash}
          AND execution_policy_json ->> 'connectorRevision' = CAST(#{connectorRevision} AS text)
        """)
    int recoverRemoteTool(
        @Param("id") Long id,
        @Param("connectorRevision") Long connectorRevision,
        @Param("schemaHash") String schemaHash,
        @Param("discoveryId") Long discoveryId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理deprecateRemote工具并返回对应结果。
     *
     * @param id 资源标识
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_tool
        SET status = 'deprecated', is_available = FALSE,
            update_by = #{actorId}, update_time = #{now}
        WHERE id = #{id} AND del_flag = '0'
        """)
    int deprecateRemoteTool(
        @Param("id") Long id,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 更新工具Status。
     *
     * @param id 资源标识
     * @param expectedStatus 目标状态
     * @param newStatus 目标状态
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_tool
        SET status = #{newStatus}, update_by = #{actorId}, update_time = #{now}
        WHERE id = #{id} AND del_flag = '0' AND status = #{expectedStatus}
        """)
    int updateToolStatus(
        @Param("id") Long id,
        @Param("expectedStatus") String expectedStatus,
        @Param("newStatus") String newStatus,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
 * 处理publishAvailable连接器Tools并返回对应结果。
 * Publishes every currently available MCP tool discovered for one connector. */
    @Update("""
        UPDATE agent_tool
        SET status = 'active', update_by = #{actorId}, update_time = #{now}
        WHERE connector_id = #{connectorId} AND tool_type = 'mcp'
          AND status = 'disabled' AND is_available = TRUE AND del_flag = '0'
        """)
    int publishAvailableConnectorTools(
        @Param("connectorId") Long connectorId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理countActive工具References并返回对应结果。
     *
     * @param toolId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT count(*) FROM agent_agent_version_tool b
        JOIN agent_definition_version v ON v.id = b.agent_version_id
        WHERE b.resource_id = #{toolId} AND v.status IN ('draft', 'published')
        """)
    int countActiveToolReferences(@Param("toolId") Long toolId);

    /**
     * 处理countActive工具FamilyReferences并返回对应结果。
     *
     * @param toolKey 工具Key参数
     * @return 处理结果
     */
    @Select("""
        SELECT count(*)
        FROM agent_agent_version_tool b
        JOIN agent_definition_version v ON v.id = b.agent_version_id
        JOIN agent_tool t ON t.id = b.resource_id
        WHERE t.tool_key = #{toolKey} AND t.del_flag = '0'
          AND v.status IN ('draft', 'published')
        """)
    int countActiveToolFamilyReferences(@Param("toolKey") String toolKey);

    /**
     * 处理softDelete工具Family并返回对应结果。
     *
     * @param toolKey 工具Key参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_tool
        SET status = 'deprecated', is_available = FALSE, del_flag = '1',
            update_by = #{actorId}, update_time = #{now}
        WHERE tool_key = #{toolKey} AND del_flag = '0'
        """)
    int softDeleteToolFamily(
        @Param("toolKey") String toolKey,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理softDelete连接器Tools并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_tool
        SET status = 'deprecated', is_available = FALSE, del_flag = '1',
            update_by = #{actorId}, update_time = #{now}
        WHERE connector_id = #{connectorId} AND del_flag = '0'
        """)
    int softDeleteConnectorTools(
        @Param("connectorId") Long connectorId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );
}
