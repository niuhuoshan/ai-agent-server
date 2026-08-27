package group.aitools.nhs.platform.project.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.project.domain.AgentProject;
import group.aitools.nhs.platform.project.domain.AgentProjectMember;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 处理lock项目并返回对应结果。
 *
 * 定义智能体项目相关的数据访问契约。
 * Project and membership persistence without organization-tree inheritance. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentProjectMapper {

    @Select("SELECT id FROM agent_project WHERE id = #{projectId} AND del_flag = '0' FOR UPDATE")
    Long lockProject(@Param("projectId") Long projectId);

    /**
     * 获取项目。
     *
     * @param projectId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, project_key, name, description, status, owner_id, default_agent_version_id,
               workspace_policy_json::text AS workspace_policy_json,
               notification_policy_json::text AS notification_policy_json,
               tags_json::text AS tags_json, archived_at, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_project
        WHERE id = #{projectId} AND del_flag = '0'
        """)
    AgentProject selectProject(@Param("projectId") Long projectId);

    /**
     * 获取{@code ByKey}。
     *
     * @param projectKey 项目Key参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, project_key, name, description, status, owner_id, default_agent_version_id,
               workspace_policy_json::text AS workspace_policy_json,
               notification_policy_json::text AS notification_policy_json,
               tags_json::text AS tags_json, archived_at, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_project
        WHERE project_key = #{projectKey} AND del_flag = '0'
        """)
    AgentProject selectByKey(@Param("projectKey") String projectKey);

    /**
     * 获取{@code VisibleProjects}。
     *
     * @param principalId 资源标识
     * @param platformAdmin 平台Admin参数
     * @param status 目标状态
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT p.id, p.project_key, p.name, p.description, p.status, p.owner_id,
               p.default_agent_version_id, p.workspace_policy_json::text AS workspace_policy_json,
               p.notification_policy_json::text AS notification_policy_json,
               p.tags_json::text AS tags_json, p.archived_at, p.create_by, p.create_time,
               p.update_by, p.update_time, p.del_flag, p.extra_json::text AS extra_json
        FROM agent_project p
        WHERE p.del_flag = '0'
          <if test="status != null">
            AND p.status = #{status}
          </if>
          AND (
              #{platformAdmin}
              OR p.owner_id = #{principalId}
              OR EXISTS (
                  SELECT 1 FROM agent_project_member pm
                  WHERE pm.project_id = p.id AND pm.user_id = #{principalId} AND pm.status = 'active'
              )
          )
        ORDER BY p.create_time DESC, p.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<AgentProject> selectVisibleProjects(
        @Param("principalId") Long principalId,
        @Param("platformAdmin") boolean platformAdmin,
        @Param("status") String status,
        @Param("limit") int limit
    );

    /**
     * 创建并保存项目。
     *
     * @param project 项目参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_project (
            id, project_key, name, description, status, owner_id, default_agent_version_id,
            workspace_policy_json, notification_policy_json, tags_json,
            create_by, create_time, del_flag, extra_json
        ) VALUES (
            #{id}, #{projectKey}, #{name}, #{description}, #{status}, #{ownerId},
            #{defaultAgentVersionId}, CAST(#{workspacePolicyJson} AS jsonb),
            CAST(#{notificationPolicyJson} AS jsonb), CAST(#{tagsJson} AS jsonb),
            #{createBy}, #{createTime}, #{delFlag}, CAST(#{extraJson} AS jsonb)
        )
        ON CONFLICT DO NOTHING
        """)
    int insertProject(AgentProject project);

    /**
     * 更新项目。
     *
     * @param project 项目参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_project
        SET name = #{name}, description = #{description},
            default_agent_version_id = #{defaultAgentVersionId},
            workspace_policy_json = CAST(#{workspacePolicyJson} AS jsonb),
            notification_policy_json = CAST(#{notificationPolicyJson} AS jsonb),
            tags_json = CAST(#{tagsJson} AS jsonb), update_by = #{updateBy}, update_time = #{updateTime}
        WHERE id = #{id} AND status <> 'archived' AND del_flag = '0'
        """)
    int updateProject(AgentProject project);

    /**
     * 更新{@code Status}。
     *
     * @param projectId 资源标识
     * @param expectedStatus 目标状态
     * @param targetStatus 目标状态
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_project
        SET status = #{targetStatus},
            archived_at = CASE WHEN #{targetStatus} = 'archived' THEN #{now} ELSE NULL END,
            update_by = #{userId}, update_time = #{now}
        WHERE id = #{projectId} AND status = #{expectedStatus} AND del_flag = '0'
        """)
    int updateStatus(
        @Param("projectId") Long projectId,
        @Param("expectedStatus") String expectedStatus,
        @Param("targetStatus") String targetStatus,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取{@code ActiveMember}。
     *
     * @param projectId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, project_id, user_id, member_role, permission_json::text AS permission_json,
               status, joined_at, created_by, created_at
        FROM agent_project_member
        WHERE project_id = #{projectId} AND user_id = #{userId} AND status = 'active'
        LIMIT 1
        """)
    AgentProjectMember selectActiveMember(
        @Param("projectId") Long projectId,
        @Param("userId") Long userId
    );

    /**
     * 获取{@code ActiveMembers}。
     *
     * @param projectId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, project_id, user_id, member_role, permission_json::text AS permission_json,
               status, joined_at, created_by, created_at
        FROM agent_project_member
        WHERE project_id = #{projectId} AND status = 'active'
        ORDER BY CASE member_role WHEN 'owner' THEN 0 WHEN 'manager' THEN 1 WHEN 'member' THEN 2 ELSE 3 END,
                 joined_at, id
        LIMIT #{limit}
        """)
    List<AgentProjectMember> selectActiveMembers(
        @Param("projectId") Long projectId,
        @Param("limit") int limit
    );

    /**
     * 创建并保存{@code Member}。
     *
     * @param member {@code member}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_project_member (
            id, project_id, user_id, member_role, permission_json, status,
            joined_at, created_by, created_at
        ) VALUES (
            #{id}, #{projectId}, #{userId}, #{memberRole}, CAST(#{permissionJson} AS jsonb),
            #{status}, #{joinedAt}, #{createdBy}, #{createdAt}
        )
        ON CONFLICT DO NOTHING
        """)
    int insertMember(AgentProjectMember member);

    /**
     * 更新Member角色。
     *
     * @param projectId 资源标识
     * @param userId 资源标识
     * @param memberRole member角色参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_project_member
        SET member_role = #{memberRole}
        WHERE project_id = #{projectId} AND user_id = #{userId}
          AND status = 'active' AND member_role <> 'owner'
        """)
    int updateMemberRole(
        @Param("projectId") Long projectId,
        @Param("userId") Long userId,
        @Param("memberRole") String memberRole
    );

    /**
     * 删除{@code Member}。
     *
     * @param projectId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_project_member
        SET status = 'removed'
        WHERE project_id = #{projectId} AND user_id = #{userId}
          AND status = 'active' AND member_role <> 'owner'
        """)
    int removeMember(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
