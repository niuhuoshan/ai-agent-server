package group.aitools.nhs.platform.iam.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.iam.persistence.row.PermissionBindingRow;
import group.aitools.nhs.platform.iam.persistence.row.PermissionRuleRow;
import group.aitools.nhs.platform.iam.persistence.row.TaskAccessRuleRow;

import java.util.List;

/**
 * 获取{@code ActiveBinding}。
 *
 * 定义权限Rule查询相关的数据访问契约。
 * Read model for the complete user permission resolution path. */
@Mapper
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface PermissionRuleQueryMapper {

    @Select("""
        SELECT id,
               binding_type,
               profile_id,
               profile_version,
               CAST(snapshot_json AS text) AS snapshot_json
        FROM iam_user_permission_binding
        WHERE user_id = #{userId}
          AND status = 'active'
        LIMIT 1
        """)
    PermissionBindingRow selectActiveBinding(@Param("userId") Long userId);

    /**
     * 获取{@code EffectiveRelationalRules}。
     *
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT e.resource_type,
               e.resource_id,
               e.resource_key,
               e.action,
               e.effect,
               'PROFILE' AS source,
               'profile-entry:' || e.id AS source_reference,
               'published permission profile' AS reason
        FROM iam_user_permission_binding b
        JOIN iam_permission_profile p
          ON p.id = b.profile_id
         AND p.version_no = b.profile_version
         AND p.status = 'published'
         AND p.del_flag = '0'
        JOIN iam_permission_profile_entry e ON e.profile_id = p.id
        WHERE b.user_id = #{userId}
          AND b.binding_type = 'profile'
          AND b.status = 'active'

        UNION ALL

        SELECT o.resource_type,
               o.resource_id,
               o.resource_key,
               o.action,
               o.effect,
               'USER_OVERRIDE' AS source,
               'user-override:' || o.id AS source_reference,
               COALESCE(o.reason, 'user-specific permission override') AS reason
        FROM iam_user_permission_override o
        WHERE o.user_id = #{userId}
          AND o.status = 'active'
          AND (o.expires_at IS NULL OR o.expires_at > CURRENT_TIMESTAMP)

        UNION ALL

        SELECT g.resource_type,
               g.resource_id,
               g.resource_key,
               g.action,
               g.effect,
               'TEMPORARY_GRANT' AS source,
               'temporary-grant:' || g.id AS source_reference,
               g.reason
        FROM iam_temporary_grant g
        WHERE g.user_id = #{userId}
          AND g.revoked_at IS NULL
          AND g.expires_at > CURRENT_TIMESTAMP
        """)
    List<PermissionRuleRow> selectEffectiveRelationalRules(@Param("userId") Long userId);

    /**
     * 获取EffectiveService账户Rules。
     *
     * @param serviceAccountId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT g.resource_type,
               g.resource_id,
               g.resource_key,
               g.action,
               g.effect,
               'SERVICE_ACCOUNT_GRANT' AS source,
               'service-account-grant:' || g.id AS source_reference,
               g.reason
        FROM iam_service_account_grant g
        WHERE g.service_account_id = #{serviceAccountId}
          AND g.revoked_at IS NULL
          AND (g.expires_at IS NULL OR g.expires_at > CURRENT_TIMESTAMP)
        """)
    List<PermissionRuleRow> selectEffectiveServiceAccountRules(
        @Param("serviceAccountId") Long serviceAccountId
    );

    /**
     * 获取Active任务AccessRules。
     *
     * @param taskId 资源标识
     * @param artifactId 资源标识
     * @param action {@code action}参数
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id,
               task_id,
               artifact_id,
               subject_type,
               subject_id,
               subject_key,
               action,
               effect
        FROM task_access_rule
        WHERE task_id = #{taskId}
          AND revoked_at IS NULL
          AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
          AND (
              (CAST(#{artifactId} AS BIGINT) IS NULL AND artifact_id IS NULL)
              OR
              (CAST(#{artifactId} AS BIGINT) IS NOT NULL
                  AND (artifact_id IS NULL OR artifact_id = CAST(#{artifactId} AS BIGINT)))
          )
          AND (action = #{action} OR action = 'admin')
        """)
    List<TaskAccessRuleRow> selectActiveTaskAccessRules(
        @Param("taskId") Long taskId,
        @Param("artifactId") Long artifactId,
        @Param("action") String action
    );
}
