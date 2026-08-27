package group.aitools.nhs.platform.iam.management.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.iam.management.domain.PermissionCopyRecord;
import group.aitools.nhs.platform.iam.management.domain.PermissionProfile;
import group.aitools.nhs.platform.iam.management.domain.PermissionProfileEntry;
import group.aitools.nhs.platform.iam.management.domain.TemporaryGrant;
import group.aitools.nhs.platform.iam.management.domain.UserPermissionBinding;
import group.aitools.nhs.platform.iam.management.domain.UserPermissionOverride;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 处理lock用户Permissions并返回对应结果。
 *
 * 定义权限Administration相关的数据访问契约。
 * Persistence operations for the IAM configuration control plane. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface PermissionAdministrationMapper {

    @Select("SELECT pg_advisory_xact_lock(#{userId})")
    String lockUserPermissions(@Param("userId") Long userId);

    /**
     * 处理{@code lockCopyIdempotency}并返回对应结果。
     *
     * @param idempotencyKey {@code idempotencyKey}参数
     * @return 处理结果
     */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{idempotencyKey}, 0))")
    String lockCopyIdempotency(@Param("idempotencyKey") String idempotencyKey);

    /**
     * 获取{@code Profiles}。
     *
     * @param status 目标状态
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT id, profile_key, name, description, profile_type, version_no, status,
               created_by, created_at, updated_at, del_flag
        FROM iam_permission_profile
        WHERE del_flag = '0'
        <if test="status != null and status != ''">AND status = #{status}</if>
        ORDER BY profile_key, version_no DESC
        LIMIT #{limit}
        </script>
        """)
    List<PermissionProfile> selectProfiles(@Param("status") String status, @Param("limit") int limit);

    /**
     * 获取配置档案。
     *
     * @param profileId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, profile_key, name, description, profile_type, version_no, status,
               created_by, created_at, updated_at, del_flag
        FROM iam_permission_profile
        WHERE id = #{profileId} AND del_flag = '0'
        """)
    PermissionProfile selectProfile(@Param("profileId") Long profileId);

    /**
     * 处理lock配置档案并返回对应结果。
     *
     * @param profileId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, profile_key, name, description, profile_type, version_no, status,
               created_by, created_at, updated_at, del_flag
        FROM iam_permission_profile
        WHERE id = #{profileId} AND del_flag = '0'
        FOR UPDATE
        """)
    PermissionProfile lockProfile(@Param("profileId") Long profileId);

    /**
     * 获取配置档案版本。
     *
     * @param profileKey 配置档案Key参数
     * @param versionNo 版本No参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, profile_key, name, description, profile_type, version_no, status,
               created_by, created_at, updated_at, del_flag
        FROM iam_permission_profile
        WHERE profile_key = #{profileKey} AND version_no = #{versionNo} AND del_flag = '0'
        """)
    PermissionProfile selectProfileVersion(
        @Param("profileKey") String profileKey,
        @Param("versionNo") Integer versionNo
    );

    /**
     * 获取Latest版本。
     *
     * @param profileKey 配置档案Key参数
     * @return 处理结果
     */
    @Select("""
        SELECT COALESCE(MAX(version_no), 0)
        FROM iam_permission_profile
        WHERE profile_key = #{profileKey} AND del_flag = '0'
        """)
    int selectLatestVersion(@Param("profileKey") String profileKey);

    /**
     * 创建并保存配置档案。
     *
     * @param profile 配置档案参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO iam_permission_profile
            (id, profile_key, name, description, profile_type, version_no, status,
             created_by, created_at, del_flag)
        VALUES
            (#{id}, #{profileKey}, #{name}, #{description}, #{profileType}, #{versionNo},
             #{status}, #{createdBy}, #{createdAt}, #{delFlag})
        ON CONFLICT DO NOTHING
        """)
    int insertProfile(PermissionProfile profile);

    /**
     * 更新配置档案Status。
     *
     * @param profileId 资源标识
     * @param expectedStatus 目标状态
     * @param targetStatus 目标状态
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE iam_permission_profile
        SET status = #{targetStatus}, updated_at = #{now}
        WHERE id = #{profileId} AND status = #{expectedStatus} AND del_flag = '0'
        """)
    int updateProfileStatus(
        @Param("profileId") Long profileId,
        @Param("expectedStatus") String expectedStatus,
        @Param("targetStatus") String targetStatus,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存配置档案Entry。
     *
     * @param entry {@code entry}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO iam_permission_profile_entry
            (id, profile_id, resource_type, resource_id, resource_key, action, effect,
             policy_json, created_at)
        VALUES
            (#{id}, #{profileId}, #{resourceType}, #{resourceId}, #{resourceKey}, #{action},
             #{effect}, CAST(#{policyJson} AS jsonb), #{createdAt})
        ON CONFLICT DO NOTHING
        """)
    int insertProfileEntry(PermissionProfileEntry entry);

    /**
     * 获取配置档案Entries。
     *
     * @param profileId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, profile_id, resource_type, resource_id, resource_key, action, effect,
               policy_json::text AS policy_json, created_at
        FROM iam_permission_profile_entry
        WHERE profile_id = #{profileId}
        ORDER BY resource_type, COALESCE(resource_id, 0), COALESCE(resource_key, ''), action
        """)
    List<PermissionProfileEntry> selectProfileEntries(@Param("profileId") Long profileId);

    /**
     * 处理countPrivate知识库Base并返回对应结果。
     *
     * @param resourceId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT COUNT(*)
        FROM agent_knowledge_base
        WHERE id = #{resourceId} AND visibility = 'private' AND del_flag = '0'
        """)
    int countPrivateKnowledgeBase(@Param("resourceId") Long resourceId);

    /**
     * 处理countPrivate用户技能并返回对应结果。
     *
     * @param resourceId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT COUNT(*)
        FROM agent_skill
        WHERE id = #{resourceId} AND scope_type = 'user' AND del_flag = '0'
        """)
    int countPrivateUserSkill(@Param("resourceId") Long resourceId);

    /**
     * 获取资源State。
     *
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @return 处理结果
     */
    @Select("""
        <script>
        <choose>
          <when test="resourceType == 'agent'">
            SELECT COALESCE((SELECT CASE WHEN del_flag = '0' AND status = 'active' THEN 'active'
              WHEN del_flag &lt;&gt; '0' THEN 'missing' ELSE 'inactive' END
              FROM agent_definition WHERE id = #{resourceId}), 'missing')
          </when>
          <when test="resourceType == 'agent_version'">
            SELECT COALESCE((SELECT CASE WHEN status = 'published' THEN 'active' ELSE 'inactive' END
              FROM agent_definition_version WHERE id = #{resourceId}), 'missing')
          </when>
          <when test="resourceType == 'model'">
            SELECT COALESCE((SELECT CASE WHEN del_flag = '0' AND status = 'active' THEN 'active'
              WHEN del_flag &lt;&gt; '0' THEN 'missing' ELSE 'inactive' END
              FROM agent_model WHERE id = #{resourceId}), 'missing')
          </when>
          <when test="resourceType == 'tool'">
            SELECT COALESCE((SELECT CASE WHEN del_flag = '0' AND status = 'active' THEN 'active'
              WHEN del_flag &lt;&gt; '0' THEN 'missing' ELSE 'inactive' END
              FROM agent_tool WHERE id = #{resourceId}), 'missing')
          </when>
          <when test="resourceType == 'skill'">
            SELECT COALESCE((SELECT CASE WHEN del_flag = '0' AND status = 'active' THEN 'active'
              WHEN del_flag &lt;&gt; '0' THEN 'missing' ELSE 'inactive' END
              FROM agent_skill WHERE id = #{resourceId}), 'missing')
          </when>
          <when test="resourceType == 'knowledge_base'">
            SELECT COALESCE((SELECT CASE WHEN del_flag = '0' AND status = 'active' THEN 'active'
              WHEN del_flag &lt;&gt; '0' THEN 'missing' ELSE 'inactive' END
              FROM agent_knowledge_base WHERE id = #{resourceId}), 'missing')
          </when>
          <when test="resourceType == 'data_source'">
            SELECT COALESCE((SELECT CASE WHEN del_flag = '0' AND status = 'active' THEN 'active'
              WHEN del_flag &lt;&gt; '0' THEN 'missing' ELSE 'inactive' END
              FROM agent_data_source WHERE id = #{resourceId}), 'missing')
          </when>
          <when test="resourceType == 'dataset'">
            SELECT COALESCE((SELECT CASE WHEN del_flag = '0' AND status = 'active' THEN 'active'
              WHEN del_flag &lt;&gt; '0' THEN 'missing' ELSE 'inactive' END
              FROM agent_data_dataset WHERE id = #{resourceId}), 'missing')
          </when>
          <when test="resourceType == 'workflow'">
            SELECT COALESCE((SELECT CASE WHEN del_flag = '0' AND status = 'active' THEN 'active'
              WHEN del_flag &lt;&gt; '0' THEN 'missing' ELSE 'inactive' END
              FROM agent_workflow_definition WHERE id = #{resourceId}), 'missing')
          </when>
          <when test="resourceType == 'connector'">
            SELECT COALESCE((SELECT CASE WHEN del_flag = '0' AND status = 'active' THEN 'active'
              WHEN del_flag &lt;&gt; '0' THEN 'missing' ELSE 'inactive' END
              FROM agent_connector WHERE id = #{resourceId}), 'missing')
          </when>
          <when test="resourceType == 'sandbox'">
            SELECT COALESCE((SELECT CASE WHEN status = 'active'
              AND heartbeat_expires_at &gt;= CURRENT_TIMESTAMP THEN 'active' ELSE 'inactive' END
              FROM agent_sandbox_runner WHERE id = #{resourceId}), 'missing')
          </when>
          <when test="resourceType == 'api_application'">
            SELECT COALESCE((SELECT CASE WHEN del_flag = '0' AND status = 'active'
              AND (expires_at IS NULL OR expires_at &gt; CURRENT_TIMESTAMP) THEN 'active'
              WHEN del_flag &lt;&gt; '0' THEN 'missing' ELSE 'inactive' END
              FROM agent_api_application WHERE id = #{resourceId}), 'missing')
          </when>
          <when test="resourceType == 'webhook'">
            SELECT COALESCE((SELECT CASE WHEN del_flag = '0' AND status = 'active'
              AND trigger_type = 'webhook' THEN 'active' WHEN del_flag &lt;&gt; '0' THEN 'missing'
              ELSE 'inactive' END FROM agent_automation_trigger WHERE id = #{resourceId}), 'missing')
          </when>
          <when test="resourceType == 'cron'">
            SELECT COALESCE((SELECT CASE WHEN del_flag = '0' AND status = 'active'
              AND trigger_type = 'cron' THEN 'active' WHEN del_flag &lt;&gt; '0' THEN 'missing'
              ELSE 'inactive' END FROM agent_automation_trigger WHERE id = #{resourceId}), 'missing')
          </when>
          <otherwise>SELECT 'unresolved'</otherwise>
        </choose>
        </script>
        """)
    String selectResourceState(
        @Param("resourceType") String resourceType,
        @Param("resourceId") Long resourceId
    );

    /**
     * 获取{@code ActiveBinding}。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, user_id, profile_id, profile_version, binding_type,
               snapshot_json::text AS snapshot_json, source_user_id, status,
               created_by, created_at, updated_at
        FROM iam_user_permission_binding
        WHERE user_id = #{userId} AND status = 'active'
        LIMIT 1
        """)
    UserPermissionBinding selectActiveBinding(@Param("userId") Long userId);

    /**
     * 处理{@code lockActiveBinding}并返回对应结果。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, user_id, profile_id, profile_version, binding_type,
               snapshot_json::text AS snapshot_json, source_user_id, status,
               created_by, created_at, updated_at
        FROM iam_user_permission_binding
        WHERE user_id = #{userId} AND status = 'active'
        LIMIT 1 FOR UPDATE
        """)
    UserPermissionBinding lockActiveBinding(@Param("userId") Long userId);

    /**
     * 处理{@code replaceBinding}并返回对应结果。
     *
     * @param bindingId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE iam_user_permission_binding
        SET status = 'replaced', updated_at = #{now}
        WHERE id = #{bindingId} AND status = 'active'
        """)
    int replaceBinding(@Param("bindingId") Long bindingId, @Param("now") LocalDateTime now);

    /**
     * 创建并保存{@code Binding}。
     *
     * @param binding {@code binding}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO iam_user_permission_binding
            (id, user_id, profile_id, profile_version, binding_type, snapshot_json,
             source_user_id, status, created_by, created_at)
        VALUES
            (#{id}, #{userId}, #{profileId}, #{profileVersion}, #{bindingType},
             CAST(#{snapshotJson} AS jsonb), #{sourceUserId}, #{status},
             #{createdBy}, #{createdAt})
        ON CONFLICT DO NOTHING
        """)
    int insertBinding(UserPermissionBinding binding);

    /**
     * 获取{@code ActiveOverrides}。
     *
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, user_id, resource_type, resource_id, resource_key, action, effect,
               policy_json::text AS policy_json, reason, status, expires_at,
               created_by, created_at
        FROM iam_user_permission_override
        WHERE user_id = #{userId} AND status = 'active'
        ORDER BY resource_type, COALESCE(resource_id, 0), COALESCE(resource_key, ''), action
        """)
    List<UserPermissionOverride> selectActiveOverrides(@Param("userId") Long userId);

    /**
     * 获取{@code ActiveOverride}。
     *
     * @param userId 资源标识
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param resourceKey 资源Key参数
     * @param action {@code action}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, user_id, resource_type, resource_id, resource_key, action, effect,
               policy_json::text AS policy_json, reason, status, expires_at,
               created_by, created_at
        FROM iam_user_permission_override
        WHERE user_id = #{userId} AND resource_type = #{resourceType}
          AND COALESCE(resource_id, 0) = COALESCE(#{resourceId,jdbcType=BIGINT}, 0)
          AND COALESCE(resource_key, '') = COALESCE(#{resourceKey,jdbcType=VARCHAR}, '')
          AND action = #{action} AND status = 'active'
        FOR UPDATE
        """)
    UserPermissionOverride selectActiveOverride(
        @Param("userId") Long userId,
        @Param("resourceType") String resourceType,
        @Param("resourceId") Long resourceId,
        @Param("resourceKey") String resourceKey,
        @Param("action") String action
    );

    /**
     * 处理{@code revokeOverride}并返回对应结果。
     *
     * @param overrideId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE iam_user_permission_override
        SET status = 'revoked'
        WHERE id = #{overrideId} AND status = 'active'
        """)
    int revokeOverride(@Param("overrideId") Long overrideId);

    /**
     * 创建并保存{@code Override}。
     *
     * @param override {@code override}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO iam_user_permission_override
            (id, user_id, resource_type, resource_id, resource_key, action, effect,
             policy_json, reason, status, expires_at, created_by, created_at)
        VALUES
            (#{id}, #{userId}, #{resourceType}, #{resourceId}, #{resourceKey}, #{action},
             #{effect}, CAST(#{policyJson} AS jsonb), #{reason}, #{status}, #{expiresAt},
             #{createdBy}, #{createdAt})
        ON CONFLICT DO NOTHING
        """)
    int insertOverride(UserPermissionOverride override);

    /**
     * 获取{@code EffectiveTemporaryGrants}。
     *
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, user_id, resource_type, resource_id, resource_key, action, effect,
               policy_json::text AS policy_json, reason, approval_id, expires_at,
               revoked_at, created_by, created_at
        FROM iam_temporary_grant
        WHERE user_id = #{userId} AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP
        ORDER BY expires_at, id
        """)
    List<TemporaryGrant> selectEffectiveTemporaryGrants(@Param("userId") Long userId);

    /**
     * 创建并保存{@code TemporaryGrant}。
     *
     * @param grant {@code grant}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO iam_temporary_grant
            (id, user_id, resource_type, resource_id, resource_key, action, effect,
             policy_json, reason, approval_id, expires_at, created_by, created_at)
        VALUES
            (#{id}, #{userId}, #{resourceType}, #{resourceId}, #{resourceKey}, #{action},
             #{effect}, CAST(#{policyJson} AS jsonb), #{reason}, #{approvalId}, #{expiresAt},
             #{createdBy}, #{createdAt})
        """)
    int insertTemporaryGrant(TemporaryGrant grant);

    /**
     * 处理{@code revokeTemporaryGrant}并返回对应结果。
     *
     * @param userId 资源标识
     * @param grantId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE iam_temporary_grant
        SET revoked_at = #{now}
        WHERE id = #{grantId} AND user_id = #{userId} AND revoked_at IS NULL
          AND expires_at > #{now}
        """)
    int revokeTemporaryGrant(
        @Param("userId") Long userId,
        @Param("grantId") Long grantId,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取{@code CopyRecordByIdempotencyKey}。
     *
     * @param idempotencyKey {@code idempotencyKey}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, source_user_id, target_user_id, source_profile_id, source_profile_version,
               copy_mode, before_binding_id, after_binding_id,
               diff_json::text AS diff_json, excluded_json::text AS excluded_json,
               idempotency_key, created_by, created_at
        FROM iam_permission_copy_record
        WHERE idempotency_key = #{idempotencyKey}
        """)
    PermissionCopyRecord selectCopyRecordByIdempotencyKey(
        @Param("idempotencyKey") String idempotencyKey
    );

    /**
     * 创建并保存{@code CopyRecord}。
     *
     * @param record {@code record}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO iam_permission_copy_record
            (id, source_user_id, target_user_id, source_profile_id, source_profile_version,
             copy_mode, before_binding_id, after_binding_id, diff_json, excluded_json,
             idempotency_key, created_by, created_at)
        VALUES
            (#{id}, #{sourceUserId}, #{targetUserId}, #{sourceProfileId}, #{sourceProfileVersion},
             #{copyMode}, #{beforeBindingId}, #{afterBindingId}, CAST(#{diffJson} AS jsonb),
             CAST(#{excludedJson} AS jsonb), #{idempotencyKey}, #{createdBy}, #{createdAt})
        ON CONFLICT DO NOTHING
        """)
    int insertCopyRecord(PermissionCopyRecord record);

    /**
     * 获取{@code CopyRecords}。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, source_user_id, target_user_id, source_profile_id, source_profile_version,
               copy_mode, before_binding_id, after_binding_id,
               diff_json::text AS diff_json, excluded_json::text AS excluded_json,
               idempotency_key, created_by, created_at
        FROM iam_permission_copy_record
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<PermissionCopyRecord> selectCopyRecords(@Param("limit") int limit);
}
