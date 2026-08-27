package group.aitools.nhs.platform.task.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.task.domain.AgentTask;

import java.util.List;

/**
 * 创建并保存{@code IfAbsent}。
 *
 * 定义智能体任务相关的数据访问契约。
 * Task persistence with idempotent conversion and visibility-aware read paths. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentTaskMapper extends BaseMapper<AgentTask> {

    @Insert("""
        INSERT INTO agent_task (
            id, task_key, project_id, title, objective, background,
            source_conversation_id, context_snapshot_json, visibility,
            category, orchestration_mode, lifecycle_level, risk_level, status,
            importance, urgency, queue_priority, owner_id, owner_principal_type, start_at,
            acceptance_mode, acceptance_config_json, budget_json, external_refs_json,
            tags_json, create_by, create_time, del_flag, extra_json
        ) VALUES (
            #{id}, #{taskKey}, #{projectId}, #{title}, #{objective}, #{background},
            #{sourceConversationId}, CAST(#{contextSnapshotJson} AS jsonb), #{visibility},
            #{category}, #{orchestrationMode}, #{lifecycleLevel}, #{riskLevel}, #{status},
            #{importance}, #{urgency}, #{queuePriority}, #{ownerId}, #{ownerPrincipalType}, #{startAt},
            #{acceptanceMode}, CAST(#{acceptanceConfigJson} AS jsonb), CAST(#{budgetJson} AS jsonb),
            CAST(#{externalRefsJson} AS jsonb), CAST(#{tagsJson} AS jsonb),
            #{createBy}, #{createTime}, #{delFlag}, CAST(#{extraJson} AS jsonb)
        )
        ON CONFLICT DO NOTHING
        """)
    int insertIfAbsent(AgentTask task);

    /**
     * 获取By数据源会话Id。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_key, project_id, title, objective, background,
               source_conversation_id, CAST(context_snapshot_json AS text) AS context_snapshot_json,
               visibility, category, orchestration_mode, lifecycle_level, risk_level, status,
               importance, urgency, queue_priority, owner_id, owner_principal_type, start_at,
               current_version_id, latest_run_id, acceptance_mode,
               acceptance_config_json::text AS acceptance_config_json,
               budget_json::text AS budget_json, external_refs_json::text AS external_refs_json,
               tags_json::text AS tags_json, create_by, create_time, update_by, update_time,
               del_flag, extra_json::text AS extra_json
        FROM agent_task
        WHERE source_conversation_id = #{conversationId}
          AND del_flag = '0'
        LIMIT 1
        """)
    AgentTask selectBySourceConversationId(@Param("conversationId") Long conversationId);

    /**
     * 获取By任务Key。
     *
     * @param taskKey 任务Key参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_key, project_id, title, objective, background,
               source_conversation_id, context_snapshot_json::text AS context_snapshot_json,
               visibility, category, orchestration_mode, lifecycle_level, risk_level, status,
               importance, urgency, queue_priority, owner_id, owner_principal_type, start_at,
               current_version_id, latest_run_id, acceptance_mode,
               acceptance_config_json::text AS acceptance_config_json,
               budget_json::text AS budget_json, external_refs_json::text AS external_refs_json,
               tags_json::text AS tags_json, create_by, create_time, update_by, update_time,
               del_flag, extra_json::text AS extra_json
        FROM agent_task
        WHERE task_key = #{taskKey} AND del_flag = '0'
        """)
    AgentTask selectByTaskKey(@Param("taskKey") String taskKey);

    /**
     * 获取平台任务ById。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_key, project_id, title, objective, background,
               source_conversation_id, CAST(context_snapshot_json AS text) AS context_snapshot_json,
               visibility, category, orchestration_mode, lifecycle_level, risk_level, status,
               importance, urgency, queue_priority, owner_id, owner_principal_type, start_at,
               current_version_id, latest_run_id, acceptance_mode,
               acceptance_config_json::text AS acceptance_config_json,
               budget_json::text AS budget_json, external_refs_json::text AS external_refs_json,
               tags_json::text AS tags_json, create_by, create_time, update_by, update_time,
               del_flag, extra_json::text AS extra_json
        FROM agent_task
        WHERE id = #{taskId}
          AND del_flag = '0'
        """)
    AgentTask selectPlatformTaskById(@Param("taskId") Long taskId);

    /**
     * 获取{@code VisibleTasks}。
     *
     * @param principalId 资源标识
     * @param principalType 业务类型
     * @param human {@code human}参数
     * @param memberRole member角色参数
     * @param approvalRole 审批角色参数
     * @param platformAdmin 平台Admin参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT t.id, t.task_key, t.project_id, t.title, t.objective, t.background,
               t.source_conversation_id, CAST(t.context_snapshot_json AS text) AS context_snapshot_json,
               t.visibility, t.category, t.orchestration_mode, t.lifecycle_level, t.risk_level, t.status,
               t.importance, t.urgency, t.queue_priority, t.owner_id, t.owner_principal_type, t.start_at,
               t.current_version_id, t.latest_run_id, t.acceptance_mode,
               t.acceptance_config_json::text AS acceptance_config_json,
               t.budget_json::text AS budget_json, t.external_refs_json::text AS external_refs_json,
               t.tags_json::text AS tags_json, t.create_by, t.create_time,
               t.update_by, t.update_time, t.del_flag, t.extra_json::text AS extra_json
        FROM agent_task t
        WHERE t.del_flag = '0'
          AND (
              (
                  (#{human} AND t.visibility = 'enterprise_shared')
                  OR #{platformAdmin}
                  OR EXISTS (
                      SELECT 1
                      FROM task_access_rule r
                      WHERE r.task_id = t.id
                        AND r.artifact_id IS NULL
                        AND r.revoked_at IS NULL
                        AND (r.expires_at IS NULL OR r.expires_at > CURRENT_TIMESTAMP)
                        AND r.effect = 'allow'
                        AND r.action IN ('view', 'admin')
                        AND (
                            (r.subject_type = #{principalType} AND r.subject_id = #{principalId})
                            OR (r.subject_type = 'platform_role' AND (
                                (#{memberRole} AND r.subject_key = 'member')
                                OR (#{approvalRole} AND r.subject_key = 'approval_user')
                                OR (#{platformAdmin} AND r.subject_key = 'platform_admin')
                            ))
                        )
                  )
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM task_access_rule d
                  WHERE d.task_id = t.id
                    AND d.artifact_id IS NULL
                    AND d.revoked_at IS NULL
                    AND (d.expires_at IS NULL OR d.expires_at > CURRENT_TIMESTAMP)
                    AND d.effect = 'deny'
                    AND d.action IN ('view', 'admin')
                    AND (
                        (d.subject_type = #{principalType} AND d.subject_id = #{principalId})
                        OR (d.subject_type = 'platform_role' AND (
                            (#{memberRole} AND d.subject_key = 'member')
                            OR (#{approvalRole} AND d.subject_key = 'approval_user')
                            OR (#{platformAdmin} AND d.subject_key = 'platform_admin')
                        ))
                    )
              )
          )
        ORDER BY t.create_time DESC, t.id DESC
        LIMIT #{limit}
        """)
    List<AgentTask> selectVisibleTasks(
        @Param("principalId") Long principalId,
        @Param("principalType") String principalType,
        @Param("human") boolean human,
        @Param("memberRole") boolean memberRole,
        @Param("approvalRole") boolean approvalRole,
        @Param("platformAdmin") boolean platformAdmin,
        @Param("limit") int limit
    );

    /**
     * 处理bindInitial版本并返回对应结果。
     *
     * @param taskId 资源标识
     * @param versionId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task
        SET current_version_id = #{versionId},
            update_by = #{userId},
            update_time = CURRENT_TIMESTAMP
        WHERE id = #{taskId}
          AND current_version_id IS NULL
          AND del_flag = '0'
        """)
    int bindInitialVersion(
        @Param("taskId") Long taskId,
        @Param("versionId") Long versionId,
        @Param("userId") Long userId
    );

    /**
     * 更新定义And版本。
     *
     * @param task 任务参数
     * @param expectedVersionId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task
        SET project_id = #{task.projectId}, title = #{task.title}, objective = #{task.objective},
            background = #{task.background},
            context_snapshot_json = CAST(#{task.contextSnapshotJson} AS jsonb),
            visibility = #{task.visibility}, category = #{task.category},
            orchestration_mode = #{task.orchestrationMode}, lifecycle_level = #{task.lifecycleLevel},
            risk_level = #{task.riskLevel}, importance = #{task.importance}, urgency = #{task.urgency},
            queue_priority = #{task.queuePriority}, start_at = #{task.startAt},
            acceptance_mode = #{task.acceptanceMode},
            acceptance_config_json = CAST(#{task.acceptanceConfigJson} AS jsonb),
            budget_json = CAST(#{task.budgetJson} AS jsonb),
            external_refs_json = CAST(#{task.externalRefsJson} AS jsonb),
            tags_json = CAST(#{task.tagsJson} AS jsonb), current_version_id = #{task.currentVersionId},
            update_by = #{task.updateBy}, update_time = #{task.updateTime}
        WHERE id = #{task.id} AND current_version_id = #{expectedVersionId}
          AND status IN ('draft', 'ready', 'scheduled', 'rework', 'blocked', 'cancelled')
          AND del_flag = '0'
        """)
    int updateDefinitionAndVersion(
        @Param("task") AgentTask task,
        @Param("expectedVersionId") Long expectedVersionId
    );

    /**
     * 更新{@code Status}。
     *
     * @param taskId 资源标识
     * @param expectedStatus 目标状态
     * @param targetStatus 目标状态
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task
        SET status = #{targetStatus}, update_by = #{userId}, update_time = #{now}
        WHERE id = #{taskId} AND status = #{expectedStatus} AND del_flag = '0'
        """)
    int updateStatus(
        @Param("taskId") Long taskId,
        @Param("expectedStatus") String expectedStatus,
        @Param("targetStatus") String targetStatus,
        @Param("userId") Long userId,
        @Param("now") java.time.LocalDateTime now
    );
}
