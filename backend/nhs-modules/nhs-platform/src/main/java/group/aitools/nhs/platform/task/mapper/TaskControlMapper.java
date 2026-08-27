package group.aitools.nhs.platform.task.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.task.domain.AgentTaskAccessRule;
import group.aitools.nhs.platform.task.domain.AgentTaskParticipant;
import group.aitools.nhs.platform.task.domain.AgentTaskResource;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 处理lock任务并返回对应结果。
 *
 * 定义任务Control相关的数据访问契约。
 * Task-level relations, current resources and explicit ACL persistence. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface TaskControlMapper {

    @Select("SELECT id FROM agent_task WHERE id = #{taskId} AND del_flag = '0' FOR UPDATE")
    Long lockTask(@Param("taskId") Long taskId);

    /**
     * 获取{@code Relations}。
     *
     * @param taskId 资源标识
     * @param principalId 资源标识
     * @param principalType 业务类型
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT DISTINCT relation
        FROM (
            SELECT 'OWNER' AS relation
            FROM agent_task
            WHERE id = #{taskId} AND owner_id = #{principalId}
              AND owner_principal_type = #{principalType} AND del_flag = '0'
            UNION ALL
            SELECT CASE participant_type
                WHEN 'owner' THEN 'OWNER'
                WHEN 'assignee' THEN 'ASSIGNEE'
                WHEN 'collaborator' THEN 'COLLABORATOR'
                WHEN 'acceptor' THEN 'ACCEPTOR'
                WHEN 'watcher' THEN 'WATCHER'
            END
            FROM agent_task_participant
            WHERE #{principalType} = 'human' AND task_id = #{taskId}
              AND user_id = #{principalId} AND status = 'active'
            UNION ALL
            SELECT 'PROJECT_ADMIN'
            FROM agent_task t
            JOIN agent_project p ON p.id = t.project_id AND p.del_flag = '0'
            LEFT JOIN agent_project_member pm
              ON pm.project_id = p.id AND pm.user_id = #{principalId} AND pm.status = 'active'
            WHERE #{principalType} = 'human' AND t.id = #{taskId} AND t.del_flag = '0'
              AND (p.owner_id = #{principalId} OR pm.member_role IN ('owner', 'manager'))
        ) relations
        WHERE relation IS NOT NULL
        """)
    List<String> selectRelations(
        @Param("taskId") Long taskId,
        @Param("principalId") Long principalId,
        @Param("principalType") String principalType
    );

    /**
     * 获取{@code Participants}。
     *
     * @param taskId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, task_id, user_id, participant_type, source, status, created_at
        FROM agent_task_participant
        WHERE task_id = #{taskId} AND status = 'active'
        ORDER BY CASE participant_type
            WHEN 'owner' THEN 0 WHEN 'assignee' THEN 1 WHEN 'collaborator' THEN 2
            WHEN 'acceptor' THEN 3 ELSE 4 END, created_at, id
        LIMIT #{limit}
        """)
    List<AgentTaskParticipant> selectParticipants(
        @Param("taskId") Long taskId,
        @Param("limit") int limit
    );

    /**
     * 获取{@code Participant}。
     *
     * @param taskId 资源标识
     * @param userId 资源标识
     * @param participantType 业务类型
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_id, user_id, participant_type, source, status, created_at
        FROM agent_task_participant
        WHERE task_id = #{taskId} AND user_id = #{userId}
          AND participant_type = #{participantType} AND status = 'active'
        """)
    AgentTaskParticipant selectParticipant(
        @Param("taskId") Long taskId,
        @Param("userId") Long userId,
        @Param("participantType") String participantType
    );

    /**
     * 创建并保存{@code Participant}。
     *
     * @param participant {@code participant}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_task_participant
            (id, task_id, user_id, participant_type, source, status, created_at)
        VALUES
            (#{id}, #{taskId}, #{userId}, #{participantType}, #{source}, #{status}, #{createdAt})
        ON CONFLICT DO NOTHING
        """)
    int insertParticipant(AgentTaskParticipant participant);

    /**
     * 删除{@code Participant}。
     *
     * @param taskId 资源标识
     * @param userId 资源标识
     * @param participantType 业务类型
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_task_participant
        SET status = 'removed'
        WHERE task_id = #{taskId} AND user_id = #{userId}
          AND participant_type = #{participantType} AND status = 'active'
          AND participant_type <> 'owner'
        """)
    int removeParticipant(
        @Param("taskId") Long taskId,
        @Param("userId") Long userId,
        @Param("participantType") String participantType
    );

    /**
     * 获取{@code Resources}。
     *
     * @param taskId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, task_id, resource_type, resource_id, permission, required, grant_source,
               grant_snapshot_json::text AS grant_snapshot_json, created_by, created_at
        FROM agent_task_resource
        WHERE task_id = #{taskId}
        ORDER BY resource_type, resource_id, permission
        """)
    List<AgentTaskResource> selectResources(@Param("taskId") Long taskId);

    /**
     * 获取Sql工具数据集Id。
     *
     * @param toolId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT execution_policy_json->>'datasetId'
        FROM agent_tool
        WHERE id = #{toolId} AND tool_type = 'sql' AND del_flag = '0'
        """)
    String selectSqlToolDatasetId(@Param("toolId") Long toolId);

    /**
     * 删除{@code Resources}。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Delete("DELETE FROM agent_task_resource WHERE task_id = #{taskId}")
    int deleteResources(@Param("taskId") Long taskId);

    /**
     * 创建并保存资源。
     *
     * @param resource 资源参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_task_resource
            (id, task_id, resource_type, resource_id, permission, required, grant_source,
             grant_snapshot_json, created_by, created_at)
        VALUES
            (#{id}, #{taskId}, #{resourceType}, #{resourceId}, #{permission}, #{required},
             #{grantSource}, CAST(#{grantSnapshotJson} AS jsonb), #{createdBy}, #{createdAt})
        """)
    int insertResource(AgentTaskResource resource);

    /**
     * 获取{@code AccessRules}。
     *
     * @param taskId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, task_id, artifact_id, subject_type, subject_id, subject_key,
               action, effect, expires_at, revoked_at, created_by, created_at
        FROM task_access_rule
        WHERE task_id = #{taskId} AND artifact_id IS NULL AND revoked_at IS NULL
        ORDER BY created_at, id
        LIMIT #{limit}
        """)
    List<AgentTaskAccessRule> selectAccessRules(
        @Param("taskId") Long taskId,
        @Param("limit") int limit
    );

    /**
     * 获取{@code AccessRule}。
     *
     * @param taskId 资源标识
     * @param ruleId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_id, artifact_id, subject_type, subject_id, subject_key,
               action, effect, expires_at, revoked_at, created_by, created_at
        FROM task_access_rule
        WHERE id = #{ruleId} AND task_id = #{taskId} AND artifact_id IS NULL
          AND revoked_at IS NULL
        """)
    AgentTaskAccessRule selectAccessRule(
        @Param("taskId") Long taskId,
        @Param("ruleId") Long ruleId
    );

    /**
     * 获取{@code ActiveAccessRule}。
     *
     * @param taskId 资源标识
     * @param subjectType 业务类型
     * @param subjectId 资源标识
     * @param subjectKey {@code subjectKey}参数
     * @param action {@code action}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_id, artifact_id, subject_type, subject_id, subject_key,
               action, effect, expires_at, revoked_at, created_by, created_at
        FROM task_access_rule
        WHERE task_id = #{taskId} AND artifact_id IS NULL AND revoked_at IS NULL
          AND subject_type = #{subjectType}
          AND COALESCE(subject_id, 0) = COALESCE(#{subjectId,jdbcType=BIGINT}, 0)
          AND COALESCE(subject_key, '') = COALESCE(#{subjectKey,jdbcType=VARCHAR}, '')
          AND action = #{action}
        """)
    AgentTaskAccessRule selectActiveAccessRule(
        @Param("taskId") Long taskId,
        @Param("subjectType") String subjectType,
        @Param("subjectId") Long subjectId,
        @Param("subjectKey") String subjectKey,
        @Param("action") String action
    );

    /**
     * 创建并保存{@code AccessRule}。
     *
     * @param rule {@code rule}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO task_access_rule
            (id, task_id, artifact_id, subject_type, subject_id, subject_key,
             action, effect, expires_at, created_by, created_at)
        VALUES
            (#{id}, #{taskId}, NULL, #{subjectType}, #{subjectId}, #{subjectKey},
             #{action}, #{effect}, #{expiresAt}, #{createdBy}, #{createdAt})
        ON CONFLICT DO NOTHING
        """)
    int insertAccessRule(AgentTaskAccessRule rule);

    /**
     * 处理{@code revokeAccessRule}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param ruleId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE task_access_rule
        SET revoked_at = #{now}
        WHERE id = #{ruleId} AND task_id = #{taskId} AND revoked_at IS NULL
        """)
    int revokeAccessRule(
        @Param("taskId") Long taskId,
        @Param("ruleId") Long ruleId,
        @Param("now") LocalDateTime now
    );
}
