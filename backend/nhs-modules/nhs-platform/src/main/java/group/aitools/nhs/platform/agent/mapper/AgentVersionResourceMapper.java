package group.aitools.nhs.platform.agent.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.agent.persistence.row.AgentResourceSnapshotRow;
import group.aitools.nhs.platform.agent.persistence.row.AgentVersionBindingRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取工具快照。
 *
 * 定义智能体版本资源相关的数据访问契约。
 * Resource validation, snapshots and immutable version bindings. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentVersionResourceMapper {

    @Select("""
        SELECT id, status,
               jsonb_build_object(
                 'toolKey', tool_key,
                 'name', name,
                 'description', COALESCE(description, ''),
                 'toolType', tool_type,
                 'riskLevel', risk_level,
                 'versionNo', version_no,
                 'connectorId', connector_id,
                 'externalName', external_name,
                 'parameterSchema', COALESCE(parameter_schema_json, '{}'::jsonb),
                 'executionPolicy', COALESCE(execution_policy_json, '{}'::jsonb)
               )::text AS snapshot_json
        FROM agent_tool t
        WHERE t.id = #{resourceId} AND t.del_flag = '0' AND t.status = 'active'
          AND t.is_available = TRUE
          AND (t.connector_id IS NULL OR EXISTS (
            SELECT 1 FROM agent_connector c
            WHERE c.id = t.connector_id AND c.del_flag = '0' AND c.status = 'active'
          ))
        FOR SHARE OF t
        """)
    AgentResourceSnapshotRow selectToolSnapshot(@Param("resourceId") Long resourceId);

    /**
     * 获取技能快照。
     *
     * @param resourceId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT s.id, s.status,
               jsonb_build_object(
                 'skillKey', s.skill_key,
                 'name', s.name,
                 'description', COALESCE(s.description, ''),
                 'scopeType', s.scope_type,
                 'scopeId', s.scope_id,
                 'versionId', v.id,
                 'versionNo', v.version_no,
                 'content', COALESCE(f.content, v.content),
                 'contentHash', v.content_hash,
                 'instructionHash', COALESCE(f.content_hash, v.content_hash),
                 'fileBundleHash', v.file_bundle_hash,
                 'manifest', COALESCE(v.manifest_json, '{}'::jsonb),
                 'runtimeRequirements', COALESCE(v.runtime_requirements_json, '{}'::jsonb)
               )::text AS snapshot_json
        FROM agent_skill s
        JOIN LATERAL (
          SELECT id, version_no, content, content_hash, file_bundle_hash,
                 manifest_json, runtime_requirements_json
          FROM agent_skill_version
          WHERE skill_id = s.id AND status = 'published'
          ORDER BY version_no DESC
          LIMIT 1
        ) v ON TRUE
        LEFT JOIN LATERAL (
          SELECT content, content_hash
          FROM agent_skill_file
          WHERE skill_id = s.id AND version_id = v.id
            AND path = 'SKILL.md' AND file_kind = 'file' AND del_flag = '0'
          LIMIT 1
        ) f ON TRUE
        WHERE s.id = #{resourceId} AND s.del_flag = '0' AND s.status = 'active'
        FOR SHARE OF s
        """)
    AgentResourceSnapshotRow selectSkillSnapshot(@Param("resourceId") Long resourceId);

    /**
     * 获取知识库快照。
     *
     * @param resourceId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, status,
               jsonb_build_object(
                 'knowledgeKey', knowledge_key,
                 'name', name,
                 'description', COALESCE(description, ''),
                 'providerType', provider_type,
                 'connectorId', connector_id,
                 'externalId', external_id,
                 'visibility', visibility,
                 'config', COALESCE(config_json, '{}'::jsonb)
               )::text AS snapshot_json
        FROM agent_knowledge_base
        WHERE id = #{resourceId} AND del_flag = '0' AND status = 'active'
        FOR SHARE
        """)
    AgentResourceSnapshotRow selectKnowledgeSnapshot(@Param("resourceId") Long resourceId);

    /**
     * 创建并保存工具Binding。
     *
     * @param id 资源标识
     * @param versionId 资源标识
     * @param resourceId 资源标识
     * @param permission 权限参数
     * @param configJson {@code configJson}参数
     * @param createdAt {@code createdAt}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_agent_version_tool (
            id, agent_version_id, resource_id, permission, config_json, created_at
        ) VALUES (
            #{id}, #{versionId}, #{resourceId}, #{permission}, CAST(#{configJson} AS jsonb), #{createdAt}
        )
        """)
    int insertToolBinding(
        @Param("id") Long id,
        @Param("versionId") Long versionId,
        @Param("resourceId") Long resourceId,
        @Param("permission") String permission,
        @Param("configJson") String configJson,
        @Param("createdAt") LocalDateTime createdAt
    );

    /**
     * 创建并保存技能Binding。
     *
     * @param id 资源标识
     * @param versionId 资源标识
     * @param resourceId 资源标识
     * @param permission 权限参数
     * @param configJson {@code configJson}参数
     * @param createdAt {@code createdAt}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_agent_version_skill (
            id, agent_version_id, resource_id, permission, config_json, created_at
        ) VALUES (
            #{id}, #{versionId}, #{resourceId}, #{permission}, CAST(#{configJson} AS jsonb), #{createdAt}
        )
        """)
    int insertSkillBinding(
        @Param("id") Long id,
        @Param("versionId") Long versionId,
        @Param("resourceId") Long resourceId,
        @Param("permission") String permission,
        @Param("configJson") String configJson,
        @Param("createdAt") LocalDateTime createdAt
    );

    /**
     * 创建并保存知识库Binding。
     *
     * @param id 资源标识
     * @param versionId 资源标识
     * @param resourceId 资源标识
     * @param permission 权限参数
     * @param configJson {@code configJson}参数
     * @param createdAt {@code createdAt}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_agent_version_knowledge (
            id, agent_version_id, resource_id, permission, config_json, created_at
        ) VALUES (
            #{id}, #{versionId}, #{resourceId}, #{permission}, CAST(#{configJson} AS jsonb), #{createdAt}
        )
        """)
    int insertKnowledgeBinding(
        @Param("id") Long id,
        @Param("versionId") Long versionId,
        @Param("resourceId") Long resourceId,
        @Param("permission") String permission,
        @Param("configJson") String configJson,
        @Param("createdAt") LocalDateTime createdAt
    );

    /**
     * 删除工具Bindings。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Delete("DELETE FROM agent_agent_version_tool WHERE agent_version_id = #{versionId}")
    int deleteToolBindings(@Param("versionId") Long versionId);

    /**
     * 删除技能Bindings。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Delete("DELETE FROM agent_agent_version_skill WHERE agent_version_id = #{versionId}")
    int deleteSkillBindings(@Param("versionId") Long versionId);

    /**
     * 删除知识库Bindings。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Delete("DELETE FROM agent_agent_version_knowledge WHERE agent_version_id = #{versionId}")
    int deleteKnowledgeBindings(@Param("versionId") Long versionId);

    /**
     * 获取{@code Bindings}。
     *
     * @param versionId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, 'tool' AS resource_type, resource_id, permission,
               config_json::text AS config_json
        FROM agent_agent_version_tool WHERE agent_version_id = #{versionId}
        UNION ALL
        SELECT id, 'skill' AS resource_type, resource_id, permission,
               config_json::text AS config_json
        FROM agent_agent_version_skill WHERE agent_version_id = #{versionId}
        UNION ALL
        SELECT id, 'knowledge_base' AS resource_type, resource_id, permission,
               config_json::text AS config_json
        FROM agent_agent_version_knowledge WHERE agent_version_id = #{versionId}
        ORDER BY resource_type ASC, resource_id ASC
        """)
    List<AgentVersionBindingRow> selectBindings(@Param("versionId") Long versionId);
}
