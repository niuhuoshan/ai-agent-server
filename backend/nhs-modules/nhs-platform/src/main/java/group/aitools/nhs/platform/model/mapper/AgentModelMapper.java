package group.aitools.nhs.platform.model.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.persistence.row.ModelReferenceRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取{@code Models}。
 *
 * 定义智能体模型相关的数据访问契约。
 * Explicit model-registry persistence without tenant or department interceptors. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentModelMapper {

    @Select("""
        <script>
        SELECT id, model_key, display_name, provider_type, model_name, model_type,
               endpoint_url, credential_ref, context_size, max_output_tokens,
               reasoning_config_json::text AS reasoning_config_json, status,
               capability_json::text AS capability_json, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_model
        WHERE del_flag = '0'
        <if test="!includeInactive">
          AND status = 'active'
        </if>
        <if test="modelType != null and modelType != ''">
          AND model_type = #{modelType}
        </if>
        <if test="providerType != null and providerType != ''">
          AND provider_type = #{providerType}
        </if>
        <if test="search != null and search != ''">
          AND (
            position(lower(#{search}) in lower(display_name)) &gt; 0
            OR position(lower(#{search}) in lower(model_key)) &gt; 0
            OR position(lower(#{search}) in lower(model_name)) &gt; 0
          )
        </if>
        ORDER BY display_name ASC, id ASC
        LIMIT #{limit}
        </script>
        """)
    List<AgentModel> selectModels(
        @Param("modelType") String modelType,
        @Param("providerType") String providerType,
        @Param("search") String search,
        @Param("includeInactive") boolean includeInactive,
        @Param("limit") int limit
    );

    /**
     * 获取{@code ActiveMultimodalByKey}。
     *
     * @param modelKey 模型Key参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, model_key, display_name, provider_type, model_name, model_type,
               endpoint_url, credential_ref, context_size, max_output_tokens,
               reasoning_config_json::text AS reasoning_config_json, status,
               capability_json::text AS capability_json, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_model
        WHERE model_key = #{modelKey} AND model_type = 'multimodal'
          AND status = 'active' AND del_flag = '0'
        LIMIT 1
        """)
    AgentModel selectActiveMultimodalByKey(@Param("modelKey") String modelKey);

    /**
     * 获取模型ById。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, model_key, display_name, provider_type, model_name, model_type,
               endpoint_url, credential_ref, context_size, max_output_tokens,
               reasoning_config_json::text AS reasoning_config_json, status,
               capability_json::text AS capability_json, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_model
        WHERE id = #{id} AND del_flag = '0'
        """)
    AgentModel selectModelById(@Param("id") Long id);

    /**
     * 处理lock模型并返回对应结果。
     *
     * @param modelId 资源标识
     * @return 处理结果
     */
    @Select("SELECT #{modelId}::bigint FROM pg_advisory_xact_lock(#{modelId})")
    Long lockModel(@Param("modelId") Long modelId);

    /**
     * 创建并保存模型。
     *
     * @param model 模型参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_model (
            id, model_key, display_name, provider_type, model_name, model_type,
            endpoint_url, credential_ref, context_size, max_output_tokens,
            reasoning_config_json, status, capability_json,
            create_by, create_time, del_flag, extra_json
        ) VALUES (
            #{id}, #{modelKey}, #{displayName}, #{providerType}, #{modelName}, #{modelType},
            #{endpointUrl}, #{credentialRef}, #{contextSize}, #{maxOutputTokens},
            CAST(#{reasoningConfigJson} AS jsonb), #{status}, CAST(#{capabilityJson} AS jsonb),
            #{createBy}, #{createTime}, '0', CAST(#{extraJson} AS jsonb)
        )
        """)
    int insertModel(AgentModel model);

    /**
     * 更新模型。
     *
     * @param model 模型参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_model
        SET display_name = #{displayName},
            provider_type = #{providerType},
            model_name = #{modelName},
            model_type = #{modelType},
            endpoint_url = #{endpointUrl},
            credential_ref = #{credentialRef},
            context_size = #{contextSize},
            max_output_tokens = #{maxOutputTokens},
            reasoning_config_json = CAST(#{reasoningConfigJson} AS jsonb),
            status = #{status},
            capability_json = CAST(#{capabilityJson} AS jsonb),
            update_by = #{updateBy},
            update_time = #{updateTime}
        WHERE id = #{id} AND del_flag = '0'
        """)
    int updateModel(AgentModel model);

    /**
     * 处理{@code softDelete}并返回对应结果。
     *
     * @param id 资源标识
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_model
        SET status = 'disabled', del_flag = '1', update_by = #{userId}, update_time = #{now}
        WHERE id = #{id} AND del_flag = '0'
        """)
    int softDelete(
        @Param("id") Long id,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取{@code ActiveReferences}。
     *
     * @param modelId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT v.agent_id,
               d.name AS agent_name,
               v.id AS version_id,
               v.version_no,
               v.status AS version_status,
               (v.model_id = #{modelId}) AS primary_model,
               (v.synthesis_model_id = #{modelId}) AS synthesis_model
        FROM agent_definition_version v
        JOIN agent_definition d ON d.id = v.agent_id AND d.del_flag = '0'
        WHERE (v.model_id = #{modelId} OR v.synthesis_model_id = #{modelId})
          AND v.status IN ('draft', 'published')
        ORDER BY d.name ASC, v.version_no DESC
        LIMIT 500
        """)
    List<ModelReferenceRow> selectActiveReferences(@Param("modelId") Long modelId);
}
