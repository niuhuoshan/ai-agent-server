package group.aitools.nhs.platform.agent.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.agent.domain.AgentDefinition;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取{@code Definitions}。
 *
 * 定义智能体定义相关的数据访问契约。
 * Agent identity persistence with explicit single-enterprise visibility. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentDefinitionMapper {

    @Select("""
        <script>
        SELECT d.id, d.agent_key, d.name, d.description, d.agent_type, d.engine_type,
               d.avatar_url, d.is_system, d.is_default, d.status, d.owner_id, d.sort_order,
               d.engine_config_json::text AS engine_config_json,
               d.create_by, d.create_time, d.update_by, d.update_time, d.del_flag,
               d.extra_json::text AS extra_json,
               p.id AS published_version_id
        FROM agent_definition d
        LEFT JOIN agent_definition_version p
          ON p.agent_id = d.id AND p.status = 'published'
        WHERE d.del_flag = '0'
        <if test="!includeArchived">
          AND d.status &lt;&gt; 'archived'
        </if>
        <if test="search != null and search != ''">
          AND (
            position(lower(#{search}) in lower(d.name)) &gt; 0
            OR position(lower(#{search}) in lower(d.agent_key)) &gt; 0
          )
        </if>
        ORDER BY d.sort_order DESC, d.name ASC, d.id ASC
        LIMIT #{limit}
        </script>
        """)
    List<AgentDefinition> selectDefinitions(
        @Param("search") String search,
        @Param("includeArchived") boolean includeArchived,
        @Param("limit") int limit
    );

    /**
     * 获取定义ById。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT d.id, d.agent_key, d.name, d.description, d.agent_type, d.engine_type,
               d.avatar_url, d.is_system, d.is_default, d.status, d.owner_id, d.sort_order,
               d.engine_config_json::text AS engine_config_json,
               d.create_by, d.create_time, d.update_by, d.update_time, d.del_flag,
               d.extra_json::text AS extra_json,
               p.id AS published_version_id
        FROM agent_definition d
        LEFT JOIN agent_definition_version p
          ON p.agent_id = d.id AND p.status = 'published'
        WHERE d.id = #{agentId} AND d.del_flag = '0'
        """)
    AgentDefinition selectDefinitionById(@Param("agentId") Long agentId);

    /**
     * 获取定义ByKey。
     *
     * @param agentKey 智能体Key参数
     * @return 处理结果
     */
    @Select("""
        SELECT d.id, d.agent_key, d.name, d.description, d.agent_type, d.engine_type,
               d.avatar_url, d.is_system, d.is_default, d.status, d.owner_id, d.sort_order,
               d.engine_config_json::text AS engine_config_json,
               d.create_by, d.create_time, d.update_by, d.update_time, d.del_flag,
               d.extra_json::text AS extra_json,
               p.id AS published_version_id
        FROM agent_definition d
        LEFT JOIN agent_definition_version p
          ON p.agent_id = d.id AND p.status = 'published'
        WHERE d.agent_key = #{agentKey} AND d.del_flag = '0'
        """)
    AgentDefinition selectDefinitionByKey(@Param("agentKey") String agentKey);

    /**
     * 获取Onboarding智能体Id。
     *
     * @param ownerId 资源标识
     * @param onboardingKey {@code onboardingKey}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id
        FROM agent_definition
        WHERE owner_id = #{ownerId}
          AND extra_json ->> 'onboardingKey' = #{onboardingKey}
          AND del_flag = '0'
        LIMIT 1
        """)
    Long selectOnboardingAgentId(
        @Param("ownerId") Long ownerId,
        @Param("onboardingKey") String onboardingKey
    );

    /**
     * 获取{@code ActiveCandidates}。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT d.id, d.agent_key, d.name, d.description, d.agent_type, d.engine_type,
               d.avatar_url, d.is_system, d.is_default, d.status, d.owner_id, d.sort_order,
               d.engine_config_json::text AS engine_config_json,
               d.create_by, d.create_time, d.update_by, d.update_time, d.del_flag,
               d.extra_json::text AS extra_json,
               p.id AS published_version_id
        FROM agent_definition d
        JOIN agent_definition_version p
          ON p.agent_id = d.id AND p.status = 'published'
        WHERE d.del_flag = '0' AND d.status = 'active'
        ORDER BY d.is_default DESC, d.sort_order DESC, d.name ASC, d.id ASC
        LIMIT #{limit}
        """)
    List<AgentDefinition> selectActiveCandidates(@Param("limit") int limit);

    /**
     * 处理lock智能体并返回对应结果。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    @Select("SELECT #{agentId}::bigint FROM pg_advisory_xact_lock(#{agentId})")
    Long lockAgent(@Param("agentId") Long agentId);

    /**
     * 处理{@code lockOnboardingKey}并返回对应结果。
     *
     * @param ownerId 资源标识
     * @param onboardingKey {@code onboardingKey}参数
     * @return 处理结果
     */
    @Select("""
        SELECT hashtextextended('agent-onboarding:' || #{ownerId}::text || ':' || #{onboardingKey}, 0)
        FROM pg_advisory_xact_lock(
            hashtextextended('agent-onboarding:' || #{ownerId}::text || ':' || #{onboardingKey}, 0)
        )
        """)
    Long lockOnboardingKey(
        @Param("ownerId") Long ownerId,
        @Param("onboardingKey") String onboardingKey
    );

    /**
     * 创建并保存定义。
     *
     * @param definition 定义参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_definition (
            id, agent_key, name, description, agent_type, engine_type, avatar_url,
            is_system, is_default, status, owner_id, sort_order, engine_config_json,
            create_by, create_time, del_flag, extra_json
        ) VALUES (
            #{id}, #{agentKey}, #{name}, #{description}, #{agentType}, #{engineType}, #{avatarUrl},
            #{isSystem}, #{isDefault}, #{status}, #{ownerId}, #{sortOrder},
            CAST(#{engineConfigJson} AS jsonb), #{createBy}, #{createTime}, '0',
            CAST(#{extraJson} AS jsonb)
        )
        """)
    int insertDefinition(AgentDefinition definition);

    /**
     * 更新定义。
     *
     * @param definition 定义参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_definition
        SET name = #{name}, description = #{description}, agent_type = #{agentType},
            engine_type = #{engineType}, avatar_url = #{avatarUrl},
            is_default = #{isDefault}, sort_order = #{sortOrder},
            engine_config_json = CAST(#{engineConfigJson} AS jsonb),
            update_by = #{updateBy}, update_time = #{updateTime}
        WHERE id = #{id} AND del_flag = '0' AND status <> 'archived'
        """)
    int updateDefinition(AgentDefinition definition);

    /**
     * 更新{@code SortOrder}。
     *
     * @param agentId 资源标识
     * @param sortOrder {@code sortOrder}参数
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_definition
        SET sort_order = #{sortOrder}, update_by = #{userId}, update_time = #{now}
        WHERE id = #{agentId} AND del_flag = '0'
        """)
    int updateSortOrder(
        @Param("agentId") Long agentId,
        @Param("sortOrder") int sortOrder,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );

    /**
     * 更新Onboarding元数据。
     *
     * @param agentId 资源标识
     * @param onboardingKey {@code onboardingKey}参数
     * @param versionId 资源标识
     * @param requestHash {@code requestHash}参数
     * @param step {@code step}参数
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_definition
        SET extra_json = COALESCE(extra_json, '{}'::jsonb) || jsonb_build_object(
                'onboardingKey', #{onboardingKey},
                'onboardingVersionId', #{versionId},
                'onboardingRequestHash', #{requestHash},
                'onboardingStep', #{step}
            ),
            update_by = #{userId}, update_time = #{now}
        WHERE id = #{agentId} AND del_flag = '0'
        """)
    int updateOnboardingMetadata(
        @Param("agentId") Long agentId,
        @Param("onboardingKey") String onboardingKey,
        @Param("versionId") Long versionId,
        @Param("requestHash") String requestHash,
        @Param("step") String step,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );

    /**
     * 更新{@code OnboardingStep}。
     *
     * @param agentId 资源标识
     * @param step {@code step}参数
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_definition
        SET extra_json = jsonb_set(extra_json, '{onboardingStep}', to_jsonb(#{step}::text), true),
            update_by = #{userId}, update_time = #{now}
        WHERE id = #{agentId} AND del_flag = '0' AND extra_json ? 'onboardingKey'
        """)
    int updateOnboardingStep(
        @Param("agentId") Long agentId,
        @Param("step") String step,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );

    /**
     * 更新{@code Status}。
     *
     * @param agentId 资源标识
     * @param status 目标状态
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_definition
        SET status = #{status},
            is_default = CASE WHEN #{status} = 'active' THEN is_default ELSE FALSE END,
            update_by = #{userId}, update_time = #{now}
        WHERE id = #{agentId} AND del_flag = '0'
        """)
    int updateStatus(
        @Param("agentId") Long agentId,
        @Param("status") String status,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );

    /**
     * 清理或重置{@code OtherDefaults}。
     *
     * @param agentId 资源标识
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_definition
        SET is_default = FALSE, update_by = #{userId}, update_time = #{now}
        WHERE id <> #{agentId} AND is_default = TRUE AND del_flag = '0'
        """)
    int clearOtherDefaults(
        @Param("agentId") Long agentId,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code countVersions}并返回对应结果。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    @Select("SELECT count(*) FROM agent_definition_version WHERE agent_id = #{agentId}")
    int countVersions(@Param("agentId") Long agentId);

    /**
     * 处理{@code softDeleteEmptyDraft}并返回对应结果。
     *
     * @param agentId 资源标识
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_definition
        SET status = 'archived', is_default = FALSE, del_flag = '1',
            update_by = #{userId}, update_time = #{now}
        WHERE id = #{agentId} AND del_flag = '0' AND is_system = FALSE AND status = 'draft'
        """)
    int softDeleteEmptyDraft(
        @Param("agentId") Long agentId,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );
}
