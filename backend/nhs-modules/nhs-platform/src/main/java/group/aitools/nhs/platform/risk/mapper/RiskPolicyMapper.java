package group.aitools.nhs.platform.risk.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.risk.domain.AgentRiskPolicy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取{@code Policies}。
 *
 * 定义风险策略相关的数据访问契约。
 * Explicit risk-policy persistence without tenant or department interceptors. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface RiskPolicyMapper {

    @Select("""
        <script>
        SELECT id, policy_key, name, resource_type, action, risk_level, disposition,
               approval_role, notify_enabled, priority, description, status,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_risk_policy
        WHERE del_flag = '0'
        <if test="resourceType != null and resourceType != ''">
          AND resource_type = #{resourceType}
        </if>
        <if test="riskLevel != null and riskLevel != ''">
          AND risk_level = #{riskLevel}
        </if>
        <if test="status != null and status != ''">
          AND status = #{status}
        </if>
        <if test="search != null and search != ''">
          AND (
            position(lower(#{search}) in lower(name)) &gt; 0
            OR position(lower(#{search}) in lower(policy_key)) &gt; 0
            OR position(lower(#{search}) in lower(description)) &gt; 0
          )
        </if>
        ORDER BY priority DESC, update_time DESC NULLS LAST, create_time DESC, id DESC
        LIMIT #{limit}
        </script>
        """)
    List<AgentRiskPolicy> selectPolicies(
        @Param("resourceType") String resourceType,
        @Param("riskLevel") String riskLevel,
        @Param("status") String status,
        @Param("search") String search,
        @Param("limit") int limit
    );

    /**
     * 获取策略ById。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, policy_key, name, resource_type, action, risk_level, disposition,
               approval_role, notify_enabled, priority, description, status,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_risk_policy
        WHERE id = #{id} AND del_flag = '0'
        """)
    AgentRiskPolicy selectPolicyById(@Param("id") Long id);

    /**
     * 创建并保存策略。
     *
     * @param policy 策略参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_risk_policy (
            id, policy_key, name, resource_type, action, risk_level, disposition,
            approval_role, notify_enabled, priority, description, status,
            create_by, create_time, del_flag
        ) VALUES (
            #{id}, #{policyKey}, #{name}, #{resourceType}, #{action}, #{riskLevel}, #{disposition},
            #{approvalRole}, #{notifyEnabled}, #{priority}, #{description}, #{status},
            #{createBy}, #{createTime}, '0'
        )
        """)
    int insertPolicy(AgentRiskPolicy policy);

    /**
     * 更新策略。
     *
     * @param policy 策略参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_risk_policy
        SET policy_key = #{policyKey},
            name = #{name},
            resource_type = #{resourceType},
            action = #{action},
            risk_level = #{riskLevel},
            disposition = #{disposition},
            approval_role = #{approvalRole},
            notify_enabled = #{notifyEnabled},
            priority = #{priority},
            description = #{description},
            status = #{status},
            update_by = #{updateBy},
            update_time = #{updateTime}
        WHERE id = #{id} AND del_flag = '0'
        """)
    int updatePolicy(AgentRiskPolicy policy);

    /**
     * 更新{@code Status}。
     *
     * @param id 资源标识
     * @param status 目标状态
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_risk_policy
        SET status = #{status}, update_by = #{userId}, update_time = #{now}
        WHERE id = #{id} AND del_flag = '0'
        """)
    int updateStatus(
        @Param("id") Long id,
        @Param("status") String status,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code softDelete}并返回对应结果。
     *
     * @param id 资源标识
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_risk_policy
        SET status = 'disabled', del_flag = '1', update_by = #{userId}, update_time = #{now}
        WHERE id = #{id} AND del_flag = '0'
        """)
    int softDelete(
        @Param("id") Long id,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );
}
