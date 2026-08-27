package group.aitools.nhs.platform.portal.quota.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.portal.quota.domain.AgentQuotaPolicy;
import group.aitools.nhs.platform.portal.quota.persistence.row.QuotaRoleRow;
import group.aitools.nhs.platform.portal.quota.persistence.row.QuotaUserRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义智能体Quota策略相关的数据访问契约。
 * Persistence boundary for monthly quota policies and durable token usage. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentQuotaPolicyMapper {

    String POLICY_COLUMNS = "id, scope_type, scope_id, period, limit_tokens, enabled, "
        + "action_on_exceed, created_at, updated_at";

    /**
     * 获取策略。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @return 处理结果
     */
    @Select("""
        <script>
        SELECT id, scope_type, scope_id, period, limit_tokens, enabled,
               action_on_exceed, created_at, updated_at
        FROM agent_quota_policy
        WHERE scope_type = #{scopeType}
          AND period = 'monthly'
        <choose>
            <when test="scopeId == null">
                AND scope_id IS NULL
            </when>
            <otherwise>
                AND scope_id = #{scopeId}
            </otherwise>
        </choose>
        LIMIT 1
        </script>
        """)
    AgentQuotaPolicy selectPolicy(
        @Param("scopeType") String scopeType,
        @Param("scopeId") Long scopeId
    );

    /**
     * 获取{@code Policies}。
     *
     * @param scopeType 业务类型
     * @param scopeIds 资源标识集合
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT id, scope_type, scope_id, period, limit_tokens, enabled,
               action_on_exceed, created_at, updated_at
        FROM agent_quota_policy
        WHERE scope_type = #{scopeType}
          AND period = 'monthly'
          AND scope_id IN
          <foreach collection="scopeIds" item="scopeId" open="(" separator="," close=")">
            #{scopeId}
          </foreach>
        </script>
        """)
    List<AgentQuotaPolicy> selectPolicies(
        @Param("scopeType") String scopeType,
        @Param("scopeIds") List<Long> scopeIds
    );

    /**
     * 获取{@code MonthlyUsage}。
     *
     * @param userId 资源标识
     * @param from {@code from}参数
     * @param to {@code to}参数
     * @return 处理结果
     */
    @Select("""
        SELECT COALESCE(SUM(f.total_tokens), 0)
        FROM agent_dashboard_token_fact f
        WHERE f.user_id = #{userId}
          AND f.created_at >= #{from}
          AND f.created_at < #{to}
        """)
    Long selectMonthlyUsage(
        @Param("userId") Long userId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to
    );

    /**
     * 获取{@code Roles}。
     *
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT ur.role_id, r.role_name, r.role_key
        FROM sys_user_role ur
        JOIN sys_role r ON r.role_id = ur.role_id
        WHERE ur.user_id = #{userId}
          AND r.status = '0'
          AND r.del_flag = '0'
        ORDER BY r.role_id
        """)
    List<QuotaRoleRow> selectRoles(@Param("userId") Long userId);

    /**
     * 获取角色。
     *
     * @param roleId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT role_id, role_name, role_key
        FROM sys_role
        WHERE role_id = #{roleId}
          AND status = '0'
          AND del_flag = '0'
        """)
    QuotaRoleRow selectRole(@Param("roleId") Long roleId);

    /**
     * 获取用户。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT user_id, user_name
        FROM sys_user
        WHERE user_id = #{userId}
          AND del_flag = '0'
        """)
    QuotaUserRow selectUser(@Param("userId") Long userId);

    /**
     * 创建并保存策略。
     *
     * @param policy 策略参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_quota_policy (
            id, scope_type, scope_id, period, limit_tokens, enabled,
            action_on_exceed, created_at, updated_at
        ) VALUES (
            #{id}, #{scopeType}, #{scopeId}, 'monthly', #{limitTokens}, #{enabled},
            'block', #{createdAt}, #{updatedAt}
        )
        """)
    int insertPolicy(AgentQuotaPolicy policy);

    /**
     * 更新策略。
     *
     * @param policy 策略参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_quota_policy
        SET limit_tokens = #{limitTokens}, enabled = #{enabled},
            action_on_exceed = 'block', updated_at = #{updatedAt}
        WHERE id = #{id}
        """)
    int updatePolicy(AgentQuotaPolicy policy);

    /**
     * 删除策略。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Delete("DELETE FROM agent_quota_policy WHERE id = #{id}")
    int deletePolicy(@Param("id") Long id);
}
