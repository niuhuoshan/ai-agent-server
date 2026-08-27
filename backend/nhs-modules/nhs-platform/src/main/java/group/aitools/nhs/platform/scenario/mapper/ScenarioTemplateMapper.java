package group.aitools.nhs.platform.scenario.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.scenario.domain.AgentScenarioInstallRun;
import group.aitools.nhs.platform.scenario.domain.AgentScenarioInstance;
import group.aitools.nhs.platform.scenario.domain.AgentScenarioUninstallRun;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取{@code Instances}。
 *
 * 定义Scenario模板相关的数据访问契约。
 * Persistence for scenario delivery facts. Template manifests are code-owned and versioned. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface ScenarioTemplateMapper {

    @Select("""
        SELECT id, template_key, instance_key, display_name, description, status, owner_id,
               agent_id, agent_version_id, resource_bindings_json::text AS resource_bindings_json,
               acceptance_criteria_json::text AS acceptance_criteria_json,
               sample_questions_json::text AS sample_questions_json,
               next_steps_json::text AS next_steps_json, created_at, updated_at, del_flag
        FROM agent_scenario_instance
        WHERE del_flag = '0' AND (owner_id = #{ownerId} OR #{admin} = TRUE)
        ORDER BY updated_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentScenarioInstance> selectInstances(
        @Param("ownerId") Long ownerId,
        @Param("admin") boolean admin,
        @Param("limit") int limit
    );

    /**
     * 获取{@code Instance}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, template_key, instance_key, display_name, description, status, owner_id,
               agent_id, agent_version_id, resource_bindings_json::text AS resource_bindings_json,
               acceptance_criteria_json::text AS acceptance_criteria_json,
               sample_questions_json::text AS sample_questions_json,
               next_steps_json::text AS next_steps_json, created_at, updated_at, del_flag
        FROM agent_scenario_instance
        WHERE id = #{id} AND del_flag = '0'
        """)
    AgentScenarioInstance selectInstance(@Param("id") Long id);

    /**
     * 处理{@code lockInstanceById}并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, template_key, instance_key, display_name, description, status, owner_id,
               agent_id, agent_version_id, resource_bindings_json::text AS resource_bindings_json,
               acceptance_criteria_json::text AS acceptance_criteria_json,
               sample_questions_json::text AS sample_questions_json,
               next_steps_json::text AS next_steps_json, created_at, updated_at, del_flag
        FROM agent_scenario_instance
        WHERE id = #{id} AND del_flag = '0'
        FOR UPDATE
        """)
    AgentScenarioInstance lockInstanceById(@Param("id") Long id);

    /**
     * 处理{@code lockInstance}并返回对应结果。
     *
     * @param templateKey 模板Key参数
     * @param instanceKey {@code instanceKey}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, template_key, instance_key, display_name, description, status, owner_id,
               agent_id, agent_version_id, resource_bindings_json::text AS resource_bindings_json,
               acceptance_criteria_json::text AS acceptance_criteria_json,
               sample_questions_json::text AS sample_questions_json,
               next_steps_json::text AS next_steps_json, created_at, updated_at, del_flag
        FROM agent_scenario_instance
        WHERE template_key = #{templateKey} AND instance_key = #{instanceKey} AND del_flag = '0'
        FOR UPDATE
        """)
    AgentScenarioInstance lockInstance(
        @Param("templateKey") String templateKey,
        @Param("instanceKey") String instanceKey
    );

    /**
     * 创建并保存{@code Instance}。
     *
     * @param instance {@code instance}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_scenario_instance (
            id, template_key, instance_key, display_name, description, status, owner_id,
            agent_id, agent_version_id, resource_bindings_json, acceptance_criteria_json,
            sample_questions_json, next_steps_json, created_at, updated_at, del_flag
        ) VALUES (
            #{id}, #{templateKey}, #{instanceKey}, #{displayName}, #{description}, #{status}, #{ownerId},
            #{agentId}, #{agentVersionId}, CAST(#{resourceBindingsJson} AS jsonb),
            CAST(#{acceptanceCriteriaJson} AS jsonb), CAST(#{sampleQuestionsJson} AS jsonb),
            CAST(#{nextStepsJson} AS jsonb), #{createdAt}, #{updatedAt}, '0'
        )
        """)
    int insertInstance(AgentScenarioInstance instance);

    /**
     * 更新{@code Instance}。
     *
     * @param instance {@code instance}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_scenario_instance
        SET status = #{status}, agent_id = #{agentId}, agent_version_id = #{agentVersionId},
            resource_bindings_json = CAST(#{resourceBindingsJson} AS jsonb),
            updated_at = #{updatedAt}
        WHERE id = #{id} AND del_flag = '0'
        """)
    int updateInstance(AgentScenarioInstance instance);

    /**
     * 更新{@code InstanceStatus}。
     *
     * @param id 资源标识
     * @param status 目标状态
     * @param updatedAt {@code updatedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_scenario_instance
        SET status = #{status}, updated_at = #{updatedAt}
        WHERE id = #{id} AND del_flag = '0'
        """)
    int updateInstanceStatus(
        @Param("id") Long id,
        @Param("status") String status,
        @Param("updatedAt") LocalDateTime updatedAt
    );

    /**
     * 获取{@code RunByIdempotency}。
     *
     * @param templateKey 模板Key参数
     * @param idempotencyKey {@code idempotencyKey}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, instance_id, template_key, idempotency_key, status,
               precheck_json::text AS precheck_json,
               resource_bindings_json::text AS resource_bindings_json,
               error_summary, created_by, created_at, completed_at
        FROM agent_scenario_install_run
        WHERE template_key = #{templateKey} AND idempotency_key = #{idempotencyKey}
        """)
    AgentScenarioInstallRun selectRunByIdempotency(
        @Param("templateKey") String templateKey,
        @Param("idempotencyKey") String idempotencyKey
    );

    /**
     * 创建并保存{@code Run}。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_scenario_install_run (
            id, instance_id, template_key, idempotency_key, status, precheck_json,
            resource_bindings_json, created_by, created_at
        ) VALUES (
            #{id}, #{instanceId}, #{templateKey}, #{idempotencyKey}, #{status},
            CAST(#{precheckJson} AS jsonb), CAST(#{resourceBindingsJson} AS jsonb),
            #{createdBy}, #{createdAt}
        )
        """)
    int insertRun(AgentScenarioInstallRun run);

    /**
     * 处理{@code finishRun}并返回对应结果。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_scenario_install_run
        SET status = #{status}, error_summary = #{errorSummary}, completed_at = #{completedAt}
        WHERE id = #{id}
        """)
    int finishRun(AgentScenarioInstallRun run);

    /**
     * 获取{@code LatestRun}。
     *
     * @param instanceId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT r.id, r.instance_id, r.template_key, r.idempotency_key, r.status,
               r.precheck_json::text AS precheck_json,
               r.resource_bindings_json::text AS resource_bindings_json,
               r.error_summary, r.created_by, r.created_at, r.completed_at
        FROM agent_scenario_install_run r
        WHERE r.instance_id = #{instanceId}
        ORDER BY r.created_at DESC, r.id DESC
        LIMIT 1
        """)
    AgentScenarioInstallRun selectLatestRun(@Param("instanceId") Long instanceId);

    /**
     * 获取{@code UninstallRunByIdempotency}。
     *
     * @param instanceId 资源标识
     * @param idempotencyKey {@code idempotencyKey}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, instance_id, template_key, idempotency_key, status, reason,
               previous_status, agent_status, warning, created_by, created_at, completed_at
        FROM agent_scenario_uninstall_run
        WHERE instance_id = #{instanceId} AND idempotency_key = #{idempotencyKey}
        """)
    AgentScenarioUninstallRun selectUninstallRunByIdempotency(
        @Param("instanceId") Long instanceId,
        @Param("idempotencyKey") String idempotencyKey
    );

    /**
     * 创建并保存{@code UninstallRun}。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_scenario_uninstall_run (
            id, instance_id, template_key, idempotency_key, status, reason,
            previous_status, agent_status, warning, created_by, created_at, completed_at
        ) VALUES (
            #{id}, #{instanceId}, #{templateKey}, #{idempotencyKey}, #{status}, #{reason},
            #{previousStatus}, #{agentStatus}, #{warning}, #{createdBy}, #{createdAt}, #{completedAt}
        )
        """)
    int insertUninstallRun(AgentScenarioUninstallRun run);
}
