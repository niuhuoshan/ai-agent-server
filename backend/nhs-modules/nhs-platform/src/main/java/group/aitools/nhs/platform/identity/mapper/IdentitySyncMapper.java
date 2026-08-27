package group.aitools.nhs.platform.identity.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.identity.domain.AgentIdentitySyncConfig;
import group.aitools.nhs.platform.identity.domain.AgentIdentitySyncRun;
import group.aitools.nhs.platform.identity.domain.IdentitySyncLocalUser;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取{@code Config}。
 *
 * 定义身份Sync相关的数据访问契约。
 * Persistence boundary for provider configuration, run facts and local users. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface IdentitySyncMapper {

    @Select("""
        SELECT id, enabled, provider_type, data_source_id, endpoint_url, credential_ref,
               auth_type, credential_header, request_method,
               request_headers_json::text AS request_headers_json,
               request_body_json::text AS request_body_json, response_items_path, table_name,
               username_column, display_name_column, email_column, phone_column, remark_column,
               status_column, extra_mappings_json::text AS extra_mappings_json, default_role_key,
               schedule, revision_no, last_preview_at, last_run_at, last_run_status, last_error,
               update_by, update_time
        FROM agent_identity_sync_config
        WHERE id = 1
        """)
    AgentIdentitySyncConfig selectConfig();

    /**
     * 更新{@code Config}。
     *
     * @param config {@code config}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_identity_sync_config
        SET enabled = #{enabled}, provider_type = #{providerType}, data_source_id = #{dataSourceId},
            endpoint_url = #{endpointUrl}, credential_ref = #{credentialRef}, auth_type = #{authType},
            credential_header = #{credentialHeader},
            request_method = #{requestMethod}, request_headers_json = CAST(#{requestHeadersJson} AS jsonb),
            request_body_json = CAST(#{requestBodyJson} AS jsonb), response_items_path = #{responseItemsPath},
            table_name = #{tableName}, username_column = #{usernameColumn},
            display_name_column = #{displayNameColumn}, email_column = #{emailColumn},
            phone_column = #{phoneColumn}, remark_column = #{remarkColumn}, status_column = #{statusColumn},
            extra_mappings_json = CAST(#{extraMappingsJson} AS jsonb), default_role_key = #{defaultRoleKey},
            schedule = #{schedule}, revision_no = revision_no + 1, last_error = NULL,
            update_by = #{updateBy}, update_time = #{updateTime}
        WHERE id = 1 AND revision_no = #{revisionNo}
        """)
    int updateConfig(AgentIdentitySyncConfig config);

    /**
     * 处理{@code recordPreview}并返回对应结果。
     *
     * @param at {@code at}参数
     * @param error {@code error}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_identity_sync_config
        SET last_preview_at = #{at}, last_error = #{error}, update_time = #{at}
        WHERE id = 1
        """)
    int recordPreview(
        @Param("at") LocalDateTime at,
        @Param("error") String error
    );

    /**
     * 处理{@code recordRun}并返回对应结果。
     *
     * @param at {@code at}参数
     * @param status 目标状态
     * @param error {@code error}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_identity_sync_config
        SET last_run_at = #{at}, last_run_status = #{status}, last_error = #{error}, update_time = #{at}
        WHERE id = 1
        """)
    int recordRun(
        @Param("at") LocalDateTime at,
        @Param("status") String status,
        @Param("error") String error
    );

    /**
     * 创建并保存{@code Run}。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_identity_sync_run (
            id, retry_of_run_id, provider_type, config_revision, status,
            requested_names_json, items_json, discovered_count, selected_count,
            created_count, updated_count, skipped_count, failed_count,
            error_summary, requested_by, started_at, finished_at
        ) VALUES (
            #{id}, #{retryOfRunId}, #{providerType}, #{configRevision}, #{status},
            CAST(#{requestedNamesJson} AS jsonb), CAST(#{itemsJson} AS jsonb),
            #{discoveredCount}, #{selectedCount}, #{createdCount}, #{updatedCount},
            #{skippedCount}, #{failedCount}, #{errorSummary}, #{requestedBy},
            #{startedAt}, #{finishedAt}
        )
        """)
    int insertRun(AgentIdentitySyncRun run);

    /**
     * 处理{@code finishRun}并返回对应结果。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_identity_sync_run
        SET status = #{status}, items_json = CAST(#{itemsJson} AS jsonb),
            discovered_count = #{discoveredCount}, selected_count = #{selectedCount},
            created_count = #{createdCount}, updated_count = #{updatedCount},
            skipped_count = #{skippedCount}, failed_count = #{failedCount},
            error_summary = #{errorSummary}, finished_at = #{finishedAt}
        WHERE id = #{id}
        """)
    int finishRun(AgentIdentitySyncRun run);

    /**
     * 获取{@code Run}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, retry_of_run_id, provider_type, config_revision, status,
               requested_names_json::text AS requested_names_json,
               items_json::text AS items_json, discovered_count, selected_count,
               created_count, updated_count, skipped_count, failed_count,
               error_summary, requested_by, started_at, finished_at
        FROM agent_identity_sync_run
        WHERE id = #{id}
        """)
    AgentIdentitySyncRun selectRun(@Param("id") Long id);

    /**
     * 获取{@code Runs}。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, retry_of_run_id, provider_type, config_revision, status,
               requested_names_json::text AS requested_names_json,
               items_json::text AS items_json, discovered_count, selected_count,
               created_count, updated_count, skipped_count, failed_count,
               error_summary, requested_by, started_at, finished_at
        FROM agent_identity_sync_run
        ORDER BY started_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentIdentitySyncRun> selectRuns(@Param("limit") int limit);

    /**
     * 处理{@code countActiveRuns}并返回对应结果。
     *
     * @return 处理结果
     */
    @Select("""
        SELECT COUNT(*)
        FROM agent_identity_sync_run
        WHERE status = 'running'
          AND started_at >= CURRENT_TIMESTAMP - INTERVAL '2 hours'
        """)
    int countActiveRuns();

    /**
     * 获取Local用户。
     *
     * @param userName 名称
     * @return 处理结果
     */
    @Select("""
        SELECT user_id, user_name, nick_name, email, phone_number, status
        FROM sys_user
        WHERE del_flag = '0' AND user_name = #{userName}
        """)
    IdentitySyncLocalUser selectLocalUser(@Param("userName") String userName);

    /**
     * 获取{@code LocalUsers}。
     *
     * @param names 名称
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT user_id, user_name, nick_name, email, phone_number, status
        FROM sys_user
        WHERE del_flag = '0'
          AND user_name IN
          <foreach collection="names" item="name" open="(" separator="," close=")">
            #{name}
          </foreach>
        </script>
        """)
    List<IdentitySyncLocalUser> selectLocalUsers(@Param("names") List<String> names);

    /**
     * 创建并保存Local用户。
     *
     * @param userId 资源标识
     * @param userName 名称
     * @param nickName 名称
     * @param email {@code email}参数
     * @param phoneNumber {@code phoneNumber}参数
     * @param password {@code password}参数
     * @param status 目标状态
     * @param remark {@code remark}参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO sys_user (
            user_id, dept_id, user_name, nick_name, user_type, email, phone_number,
            gender, password, status, del_flag, create_by, create_time, update_by,
            update_time, remark
        ) VALUES (
            #{userId}, NULL, #{userName}, #{nickName}, 'sys_user', #{email}, #{phoneNumber},
            '2', #{password}, #{status}, '0', #{actorId}, #{now}, #{actorId}, #{now}, #{remark}
        )
        """)
    int insertLocalUser(
        @Param("userId") Long userId,
        @Param("userName") String userName,
        @Param("nickName") String nickName,
        @Param("email") String email,
        @Param("phoneNumber") String phoneNumber,
        @Param("password") String password,
        @Param("status") String status,
        @Param("remark") String remark,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 更新Local用户。
     *
     * @param userId 资源标识
     * @param nickName 名称
     * @param email {@code email}参数
     * @param phoneNumber {@code phoneNumber}参数
     * @param status 目标状态
     * @param remark {@code remark}参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE sys_user
        SET nick_name = #{nickName}, email = #{email}, phone_number = #{phoneNumber},
            status = #{status}, remark = #{remark}, update_by = #{actorId}, update_time = #{now}
        WHERE user_id = #{userId} AND del_flag = '0'
        """)
    int updateLocalUser(
        @Param("userId") Long userId,
        @Param("nickName") String nickName,
        @Param("email") String email,
        @Param("phoneNumber") String phoneNumber,
        @Param("status") String status,
        @Param("remark") String remark,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取角色IdByKey。
     *
     * @param roleKey 角色Key参数
     * @return 处理结果
     */
    @Select("""
        SELECT role_id
        FROM sys_role
        WHERE role_key = #{roleKey} AND status = '0' AND del_flag = '0'
        ORDER BY role_id
        LIMIT 1
        """)
    Long selectRoleIdByKey(@Param("roleKey") String roleKey);

    /**
     * 创建并保存用户角色。
     *
     * @param userId 资源标识
     * @param roleId 资源标识
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO sys_user_role (user_id, role_id)
        VALUES (#{userId}, #{roleId})
        ON CONFLICT (user_id, role_id) DO NOTHING
        """)
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
