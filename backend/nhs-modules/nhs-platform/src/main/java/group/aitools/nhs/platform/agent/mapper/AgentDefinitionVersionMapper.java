package group.aitools.nhs.platform.agent.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.agent.domain.AgentDefinitionVersion;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取{@code Versions}。
 *
 * 定义智能体定义版本相关的数据访问契约。
 * Draft editing and immutable Agent-version lifecycle persistence. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentDefinitionVersionMapper {

    @Select("""
        SELECT id, agent_id, version_no, system_prompt, model_id, synthesis_model_id,
               runtime_config_json::text AS runtime_config_json,
               welcome_config_json::text AS welcome_config_json,
               routing_tags_json::text AS routing_tags_json,
               status, content_hash, published_at, created_by, created_at
        FROM agent_definition_version
        WHERE agent_id = #{agentId}
        ORDER BY version_no DESC, id DESC
        """)
    List<AgentDefinitionVersion> selectVersions(@Param("agentId") Long agentId);

    /**
     * 获取版本。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, agent_id, version_no, system_prompt, model_id, synthesis_model_id,
               runtime_config_json::text AS runtime_config_json,
               welcome_config_json::text AS welcome_config_json,
               routing_tags_json::text AS routing_tags_json,
               status, content_hash, published_at, created_by, created_at
        FROM agent_definition_version
        WHERE id = #{versionId} AND agent_id = #{agentId}
        """)
    AgentDefinitionVersion selectVersion(
        @Param("agentId") Long agentId,
        @Param("versionId") Long versionId
    );

    /**
     * 获取Published版本ById。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, agent_id, version_no, system_prompt, model_id, synthesis_model_id,
               runtime_config_json::text AS runtime_config_json,
               welcome_config_json::text AS welcome_config_json,
               routing_tags_json::text AS routing_tags_json,
               status, content_hash, published_at, created_by, created_at
        FROM agent_definition_version
        WHERE id = #{versionId} AND status = 'published'
        """)
    AgentDefinitionVersion selectPublishedVersionById(@Param("versionId") Long versionId);

    /**
     * 获取Next版本No。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    @Select("SELECT COALESCE(max(version_no), 0) + 1 FROM agent_definition_version WHERE agent_id = #{agentId}")
    int selectNextVersionNo(@Param("agentId") Long agentId);

    /**
     * 创建并保存版本。
     *
     * @param version 版本参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_definition_version (
            id, agent_id, version_no, system_prompt, model_id, synthesis_model_id,
            runtime_config_json, welcome_config_json, routing_tags_json,
            status, content_hash, created_by, created_at
        ) VALUES (
            #{id}, #{agentId}, #{versionNo}, #{systemPrompt}, #{modelId}, #{synthesisModelId},
            CAST(#{runtimeConfigJson} AS jsonb), CAST(#{welcomeConfigJson} AS jsonb),
            CAST(#{routingTagsJson} AS jsonb), 'draft', #{contentHash}, #{createdBy}, #{createdAt}
        )
        """)
    int insertVersion(AgentDefinitionVersion version);

    /**
     * 更新{@code Draft}。
     *
     * @param version 版本参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_definition_version
        SET system_prompt = #{systemPrompt}, model_id = #{modelId},
            synthesis_model_id = #{synthesisModelId},
            runtime_config_json = CAST(#{runtimeConfigJson} AS jsonb),
            welcome_config_json = CAST(#{welcomeConfigJson} AS jsonb),
            routing_tags_json = CAST(#{routingTagsJson} AS jsonb),
            content_hash = #{contentHash}
        WHERE id = #{id} AND agent_id = #{agentId} AND status = 'draft'
        """)
    int updateDraft(AgentDefinitionVersion version);

    /**
     * 处理{@code archivePreviouslyPublished}并返回对应结果。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_definition_version
        SET status = 'archived'
        WHERE agent_id = #{agentId} AND status = 'published' AND id <> #{versionId}
        """)
    int archivePreviouslyPublished(
        @Param("agentId") Long agentId,
        @Param("versionId") Long versionId
    );

    /**
     * 处理{@code publishDraft}并返回对应结果。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @param publishedAt {@code publishedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_definition_version
        SET status = 'published', published_at = #{publishedAt}
        WHERE id = #{versionId} AND agent_id = #{agentId} AND status = 'draft'
        """)
    int publishDraft(
        @Param("agentId") Long agentId,
        @Param("versionId") Long versionId,
        @Param("publishedAt") LocalDateTime publishedAt
    );

    /**
     * 处理archive版本并返回对应结果。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_definition_version
        SET status = 'archived'
        WHERE id = #{versionId} AND agent_id = #{agentId} AND status IN ('draft', 'published')
        """)
    int archiveVersion(
        @Param("agentId") Long agentId,
        @Param("versionId") Long versionId
    );

    /**
     * 删除{@code Draft}。
     *
     * @param agentId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Delete("""
        DELETE FROM agent_definition_version
        WHERE id = #{versionId} AND agent_id = #{agentId} AND status = 'draft'
        """)
    int deleteDraft(
        @Param("agentId") Long agentId,
        @Param("versionId") Long versionId
    );
}
