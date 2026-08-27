package group.aitools.nhs.platform.identity.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.identity.domain.ApiApplication;
import group.aitools.nhs.platform.identity.domain.ApiCredential;
import group.aitools.nhs.platform.identity.domain.ApiCredentialAuthenticationRow;
import group.aitools.nhs.platform.identity.domain.ServiceAccount;
import group.aitools.nhs.platform.identity.domain.ServiceAccountGrant;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取{@code ServiceAccounts}。
 *
 * 定义Machine身份相关的数据访问契约。
 * Persistence for service accounts, API applications and hashed credentials. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface MachineIdentityMapper {

    @Select("""
        <script>
        SELECT id, account_key, name, description, owner_id, status, last_used_at,
               expires_at, metadata_json::text AS metadata_json, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_service_account
        WHERE del_flag = '0'
        <if test="status != null and status != ''">AND status = #{status}</if>
        ORDER BY create_time DESC, id DESC
        LIMIT #{limit}
        </script>
        """)
    List<ServiceAccount> selectServiceAccounts(
        @Param("status") String status,
        @Param("limit") int limit
    );

    /**
     * 获取Service账户。
     *
     * @param accountId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, account_key, name, description, owner_id, status, last_used_at,
               expires_at, metadata_json::text AS metadata_json, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_service_account
        WHERE id = #{accountId} AND del_flag = '0'
        """)
    ServiceAccount selectServiceAccount(@Param("accountId") Long accountId);

    /**
     * 获取Active自动化AccountsByOwner。
     *
     * @param ownerId 资源标识
     * @param now {@code now}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, account_key, name, description, owner_id, status, last_used_at,
               expires_at, metadata_json::text AS metadata_json, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_service_account
        WHERE owner_id = #{ownerId} AND status = 'active' AND del_flag = '0'
          AND (expires_at IS NULL OR expires_at > #{now})
        ORDER BY CASE
            WHEN COALESCE(metadata_json->>'defaultAutomation', 'false') = 'true' THEN 0
            ELSE 1
        END, create_time, id
        LIMIT #{limit}
        """)
    List<ServiceAccount> selectActiveAutomationAccountsByOwner(
        @Param("ownerId") Long ownerId,
        @Param("now") LocalDateTime now,
        @Param("limit") int limit
    );

    /**
     * 处理lockService账户并返回对应结果。
     *
     * @param accountId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, account_key, name, description, owner_id, status, last_used_at,
               expires_at, metadata_json::text AS metadata_json, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_service_account
        WHERE id = #{accountId} AND del_flag = '0'
        FOR UPDATE
        """)
    ServiceAccount lockServiceAccount(@Param("accountId") Long accountId);

    /**
     * 创建并保存Service账户。
     *
     * @param account 账户参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_service_account
            (id, account_key, name, description, owner_id, status, expires_at,
             metadata_json, create_by, create_time, del_flag, extra_json)
        VALUES
            (#{id}, #{accountKey}, #{name}, #{description}, #{ownerId}, #{status}, #{expiresAt},
             CAST(#{metadataJson} AS jsonb), #{createBy}, #{createTime}, #{delFlag},
             CAST(#{extraJson} AS jsonb))
        ON CONFLICT DO NOTHING
        """)
    int insertServiceAccount(ServiceAccount account);

    /**
     * 更新Service账户。
     *
     * @param account 账户参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_service_account
        SET name = #{name}, description = #{description}, owner_id = #{ownerId},
            expires_at = #{expiresAt}, metadata_json = CAST(#{metadataJson} AS jsonb),
            update_by = #{updateBy}, update_time = #{updateTime}
        WHERE id = #{id} AND status <> 'revoked' AND del_flag = '0'
        """)
    int updateServiceAccount(ServiceAccount account);

    /**
     * 更新Service账户Status。
     *
     * @param accountId 资源标识
     * @param expectedStatus 目标状态
     * @param targetStatus 目标状态
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_service_account
        SET status = #{targetStatus}, update_by = #{userId}, update_time = #{now}
        WHERE id = #{accountId} AND status = #{expectedStatus} AND del_flag = '0'
        """)
    int updateServiceAccountStatus(
        @Param("accountId") Long accountId,
        @Param("expectedStatus") String expectedStatus,
        @Param("targetStatus") String targetStatus,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取接口Applications。
     *
     * @param status 目标状态
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT id, app_key, name, app_type, status, owner_id, callback_url,
               scope_json::text AS scope_json, expires_at, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_api_application
        WHERE del_flag = '0'
        <if test="status != null and status != ''">AND status = #{status}</if>
        ORDER BY create_time DESC, id DESC
        LIMIT #{limit}
        </script>
        """)
    List<ApiApplication> selectApiApplications(
        @Param("status") String status,
        @Param("limit") int limit
    );

    /**
     * 获取接口应用。
     *
     * @param applicationId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, app_key, name, app_type, status, owner_id, callback_url,
               scope_json::text AS scope_json, expires_at, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_api_application
        WHERE id = #{applicationId} AND del_flag = '0'
        """)
    ApiApplication selectApiApplication(@Param("applicationId") Long applicationId);

    /**
     * 处理lock接口应用并返回对应结果。
     *
     * @param applicationId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, app_key, name, app_type, status, owner_id, callback_url,
               scope_json::text AS scope_json, expires_at, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_api_application
        WHERE id = #{applicationId} AND del_flag = '0'
        FOR UPDATE
        """)
    ApiApplication lockApiApplication(@Param("applicationId") Long applicationId);

    /**
     * 创建并保存接口应用。
     *
     * @param application 应用参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_api_application
            (id, app_key, name, app_type, status, owner_id, callback_url, scope_json,
             expires_at, create_by, create_time, del_flag, extra_json)
        VALUES
            (#{id}, #{appKey}, #{name}, #{appType}, #{status}, #{ownerId}, #{callbackUrl},
             CAST(#{scopeJson} AS jsonb), #{expiresAt}, #{createBy}, #{createTime},
             #{delFlag}, CAST(#{extraJson} AS jsonb))
        ON CONFLICT DO NOTHING
        """)
    int insertApiApplication(ApiApplication application);

    /**
     * 更新接口应用。
     *
     * @param application 应用参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_api_application
        SET name = #{name}, owner_id = #{ownerId}, callback_url = #{callbackUrl},
            scope_json = CAST(#{scopeJson} AS jsonb), expires_at = #{expiresAt},
            extra_json = CAST(#{extraJson} AS jsonb),
            update_by = #{updateBy}, update_time = #{updateTime}
        WHERE id = #{id} AND status <> 'revoked' AND del_flag = '0'
        """)
    int updateApiApplication(ApiApplication application);

    /**
     * 更新接口应用Status。
     *
     * @param applicationId 资源标识
     * @param expectedStatus 目标状态
     * @param targetStatus 目标状态
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_api_application
        SET status = #{targetStatus}, update_by = #{userId}, update_time = #{now}
        WHERE id = #{applicationId} AND status = #{expectedStatus} AND del_flag = '0'
        """)
    int updateApiApplicationStatus(
        @Param("applicationId") Long applicationId,
        @Param("expectedStatus") String expectedStatus,
        @Param("targetStatus") String targetStatus,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存接口凭据。
     *
     * @param credential 凭据参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_api_credential
            (id, application_id, service_account_id, key_prefix, secret_hash,
             secret_ciphertext, scope_json, expires_at, created_by, created_at)
        VALUES
            (#{id}, #{applicationId}, #{serviceAccountId}, #{keyPrefix}, #{secretHash},
             NULL, CAST(#{scopeJson} AS jsonb), #{expiresAt}, #{createdBy}, #{createdAt})
        ON CONFLICT DO NOTHING
        """)
    int insertApiCredential(ApiCredential credential);

    /**
     * 获取接口Credentials。
     *
     * @param applicationId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, application_id, service_account_id, key_prefix, NULL AS secret_hash,
               scope_json::text AS scope_json, last_used_at, expires_at, revoked_at,
               created_by, created_at
        FROM agent_api_credential
        WHERE application_id = #{applicationId}
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<ApiCredential> selectApiCredentials(
        @Param("applicationId") Long applicationId,
        @Param("limit") int limit
    );

    /**
     * 处理revoke接口凭据并返回对应结果。
     *
     * @param applicationId 资源标识
     * @param credentialId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_api_credential
        SET revoked_at = #{now}
        WHERE id = #{credentialId} AND application_id = #{applicationId}
          AND revoked_at IS NULL
        """)
    int revokeApiCredential(
        @Param("applicationId") Long applicationId,
        @Param("credentialId") Long credentialId,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取凭据Authentication。
     *
     * @param secretHash {@code secretHash}参数
     * @return 处理结果
     */
    @Select("""
        SELECT c.id AS credential_id, a.id AS application_id, a.app_key,
               a.app_type AS application_type,
               a.status AS application_status, a.scope_json::text AS application_scope_json,
               a.expires_at AS application_expires_at,
               s.id AS service_account_id, s.account_key, s.name AS account_name,
               s.status AS service_account_status, s.expires_at AS service_account_expires_at,
               c.scope_json::text AS credential_scope_json,
               c.expires_at AS credential_expires_at, c.revoked_at
        FROM agent_api_credential c
        JOIN agent_api_application a
          ON a.id = c.application_id AND a.del_flag = '0'
        JOIN agent_service_account s
          ON s.id = c.service_account_id AND s.del_flag = '0'
        WHERE c.secret_hash = #{secretHash}
        LIMIT 1
        FOR SHARE OF c, a, s
        """)
    ApiCredentialAuthenticationRow selectCredentialAuthentication(
        @Param("secretHash") String secretHash
    );

    /**
     * 获取凭据AuthenticationById。
     *
     * @param credentialId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT c.id AS credential_id, a.id AS application_id, a.app_key,
               a.app_type AS application_type,
               a.status AS application_status, a.scope_json::text AS application_scope_json,
               a.expires_at AS application_expires_at,
               s.id AS service_account_id, s.account_key, s.name AS account_name,
               s.status AS service_account_status, s.expires_at AS service_account_expires_at,
               c.scope_json::text AS credential_scope_json,
               c.expires_at AS credential_expires_at, c.revoked_at
        FROM agent_api_credential c
        JOIN agent_api_application a
          ON a.id = c.application_id AND a.del_flag = '0'
        JOIN agent_service_account s
          ON s.id = c.service_account_id AND s.del_flag = '0'
        WHERE c.id = #{credentialId}
        LIMIT 1
        FOR SHARE OF c, a, s
        """)
    ApiCredentialAuthenticationRow selectCredentialAuthenticationById(
        @Param("credentialId") Long credentialId
    );

    /**
     * 将输入数据转换为uch凭据。
     *
     * @param credentialId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_api_credential
        SET last_used_at = #{now}
        WHERE id = #{credentialId} AND revoked_at IS NULL
        """)
    int touchCredential(@Param("credentialId") Long credentialId, @Param("now") LocalDateTime now);

    /**
     * 将输入数据转换为uchService账户。
     *
     * @param accountId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_service_account
        SET last_used_at = #{now}
        WHERE id = #{accountId} AND status = 'active' AND del_flag = '0'
        """)
    int touchServiceAccount(@Param("accountId") Long accountId, @Param("now") LocalDateTime now);

    /**
     * 获取Service账户Grants。
     *
     * @param accountId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, service_account_id, resource_type, resource_id, resource_key,
               action, effect, reason, expires_at, revoked_at, created_by, created_at
        FROM iam_service_account_grant
        WHERE service_account_id = #{accountId}
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<ServiceAccountGrant> selectServiceAccountGrants(
        @Param("accountId") Long accountId,
        @Param("limit") int limit
    );

    /**
     * 获取Service账户Grant。
     *
     * @param accountId 资源标识
     * @param grantId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, service_account_id, resource_type, resource_id, resource_key,
               action, effect, reason, expires_at, revoked_at, created_by, created_at
        FROM iam_service_account_grant
        WHERE id = #{grantId} AND service_account_id = #{accountId}
        """)
    ServiceAccountGrant selectServiceAccountGrant(
        @Param("accountId") Long accountId,
        @Param("grantId") Long grantId
    );

    /**
     * 创建并保存Service账户Grant。
     *
     * @param grant {@code grant}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO iam_service_account_grant (
            id, service_account_id, resource_type, resource_id, resource_key,
            action, effect, reason, expires_at, created_by, created_at
        ) VALUES (
            #{id}, #{serviceAccountId}, #{resourceType}, #{resourceId}, #{resourceKey},
            #{action}, #{effect}, #{reason}, #{expiresAt}, #{createdBy}, #{createdAt}
        )
        ON CONFLICT DO NOTHING
        """)
    int insertServiceAccountGrant(ServiceAccountGrant grant);

    /**
     * 处理revokeService账户Grant并返回对应结果。
     *
     * @param accountId 资源标识
     * @param grantId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE iam_service_account_grant
        SET revoked_at = #{now}
        WHERE id = #{grantId} AND service_account_id = #{accountId} AND revoked_at IS NULL
        """)
    int revokeServiceAccountGrant(
        @Param("accountId") Long accountId,
        @Param("grantId") Long grantId,
        @Param("now") LocalDateTime now
    );
}
