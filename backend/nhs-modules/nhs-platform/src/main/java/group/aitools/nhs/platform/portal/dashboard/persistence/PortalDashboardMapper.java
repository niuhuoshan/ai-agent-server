package group.aitools.nhs.platform.portal.dashboard.persistence;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardAgentPerformanceRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardAgentHealthRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardApiCallRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardApiHourRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardApiSummaryRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardApiTrendRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardHourRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardOnlineUserRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardRecentErrorRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardRecentRunRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardRecentUserRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardSummaryRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardTokenAgentRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardTokenRecordRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardTokenTotalsRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardTokenTrendRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardTokenUserRow;
import group.aitools.nhs.platform.portal.dashboard.persistence.row.DashboardToolUsageRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取接口Summary。
 *
 * 定义门户Dashboard相关的数据访问契约。
 *
 * Read-only facts for the Nhs Portal dashboard adapter.
 *
 * <p>The old Nhs dashboard used HTTP access logs and legacy execution
 * history tables which are not part of this platform.  Queries here therefore
 * deliberately use the durable task-run, run-step and conversation-message
 * tables.  No request payload, model prompt, or secret-bearing snapshot is
 * selected.</p>
 */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
@Mapper
public interface PortalDashboardMapper {

    @Select("""
        <script>
        SELECT
            COUNT(*) AS total_calls,
            COALESCE(SUM(CASE
                WHEN c.outcome = 'succeeded' AND c.status_code BETWEEN 200 AND 299
                THEN 1 ELSE 0 END), 0) AS succeeded_calls,
            COALESCE(SUM(CASE
                WHEN c.outcome IN ('failed', 'rate_limited') OR c.status_code &gt;= 400
                THEN 1 ELSE 0 END), 0) AS error_calls,
            COALESCE(AVG(c.duration_ms), 0) AS average_duration_ms,
            MAX(c.created_at) AS last_call_at
        FROM agent_api_call c
        LEFT JOIN agent_service_account sa ON sa.id = c.service_account_id
        LEFT JOIN agent_api_application app ON app.id = c.application_id
        LEFT JOIN agent_api_credential cr ON cr.id = c.credential_id
        WHERE c.created_at &gt;= #{fromTime} AND c.created_at &lt; #{toTime}
        <if test="userId != null">
            AND COALESCE(sa.owner_id, app.owner_id, cr.created_by, sa.create_by, app.create_by)
                = #{userId}
        </if>
        </script>
        """)
    DashboardApiSummaryRow selectApiSummary(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId
    );

    /**
     * 获取接口Trends。
     *
     * @param fromTime {@code fromTime}参数
     * @param toTime {@code toTime}参数
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT date_trunc('day', c.created_at) AS day_bucket,
               COUNT(*) AS total_calls,
               COALESCE(SUM(CASE
                   WHEN c.outcome = 'succeeded' AND c.status_code BETWEEN 200 AND 299
                   THEN 1 ELSE 0 END), 0) AS succeeded_calls,
               COALESCE(SUM(CASE
                   WHEN c.outcome IN ('failed', 'rate_limited') OR c.status_code &gt;= 400
                   THEN 1 ELSE 0 END), 0) AS error_calls,
               COALESCE(AVG(c.duration_ms), 0) AS average_duration_ms
        FROM agent_api_call c
        LEFT JOIN agent_service_account sa ON sa.id = c.service_account_id
        LEFT JOIN agent_api_application app ON app.id = c.application_id
        LEFT JOIN agent_api_credential cr ON cr.id = c.credential_id
        WHERE c.created_at &gt;= #{fromTime} AND c.created_at &lt; #{toTime}
        <if test="userId != null">
            AND COALESCE(sa.owner_id, app.owner_id, cr.created_by, sa.create_by, app.create_by)
                = #{userId}
        </if>
        GROUP BY date_trunc('day', c.created_at)
        ORDER BY day_bucket
        </script>
        """)
    List<DashboardApiTrendRow> selectApiTrends(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId
    );

    /**
     * 获取接口Trends24h。
     *
     * @param fromTime {@code fromTime}参数
     * @param toTime {@code toTime}参数
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT date_trunc('hour', c.created_at) AS hour_bucket,
               COUNT(*) AS total_calls,
               COALESCE(SUM(CASE
                   WHEN c.outcome = 'succeeded' AND c.status_code BETWEEN 200 AND 299
                   THEN 1 ELSE 0 END), 0) AS succeeded_calls,
               COALESCE(SUM(CASE
                   WHEN c.outcome IN ('failed', 'rate_limited') OR c.status_code &gt;= 400
                   THEN 1 ELSE 0 END), 0) AS error_calls,
               COALESCE(AVG(c.duration_ms), 0) AS average_duration_ms
        FROM agent_api_call c
        LEFT JOIN agent_service_account sa ON sa.id = c.service_account_id
        LEFT JOIN agent_api_application app ON app.id = c.application_id
        LEFT JOIN agent_api_credential cr ON cr.id = c.credential_id
        WHERE c.created_at &gt;= #{fromTime} AND c.created_at &lt; #{toTime}
        <if test="userId != null">
            AND COALESCE(sa.owner_id, app.owner_id, cr.created_by, sa.create_by, app.create_by)
                = #{userId}
        </if>
        GROUP BY date_trunc('hour', c.created_at)
        ORDER BY hour_bucket
        </script>
        """)
    List<DashboardApiHourRow> selectApiTrends24h(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId
    );

    /**
     * 获取Recent接口Calls。
     *
     * @param userId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT c.id, c.endpoint_key, c.http_method, c.status_code, c.duration_ms,
               c.outcome, c.error_code,
               u.user_name AS username, c.created_at
        FROM agent_api_call c
        LEFT JOIN agent_service_account sa ON sa.id = c.service_account_id
        LEFT JOIN agent_api_application app ON app.id = c.application_id
        LEFT JOIN agent_api_credential cr ON cr.id = c.credential_id
        LEFT JOIN sys_user u ON u.user_id = COALESCE(
            sa.owner_id, app.owner_id, cr.created_by, sa.create_by, app.create_by
        )
        <where>
            <if test="userId != null">
                COALESCE(sa.owner_id, app.owner_id, cr.created_by, sa.create_by, app.create_by)
                    = #{userId}
            </if>
        </where>
        ORDER BY c.created_at DESC, c.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<DashboardApiCallRow> selectRecentApiCalls(
        @Param("userId") Long userId,
        @Param("limit") int limit
    );

    /**
     * 获取Recent接口Errors。
     *
     * @param userId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT c.id, c.endpoint_key, c.http_method, c.status_code, c.duration_ms,
               c.outcome, c.error_code,
               u.user_name AS username, c.created_at
        FROM agent_api_call c
        LEFT JOIN agent_service_account sa ON sa.id = c.service_account_id
        LEFT JOIN agent_api_application app ON app.id = c.application_id
        LEFT JOIN agent_api_credential cr ON cr.id = c.credential_id
        LEFT JOIN sys_user u ON u.user_id = COALESCE(
            sa.owner_id, app.owner_id, cr.created_by, sa.create_by, app.create_by
        )
        WHERE (c.outcome IN ('failed', 'rate_limited') OR c.status_code &gt;= 400)
        <if test="userId != null">
            AND COALESCE(sa.owner_id, app.owner_id, cr.created_by, sa.create_by, app.create_by)
                = #{userId}
        </if>
        ORDER BY c.created_at DESC, c.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<DashboardApiCallRow> selectRecentApiErrors(
        @Param("userId") Long userId,
        @Param("limit") int limit
    );

    /**
     * 获取{@code Summary}。
     *
     * @param fromTime {@code fromTime}参数
     * @param toTime {@code toTime}参数
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        <script>
        SELECT
            COUNT(*) AS total_runs,
            COALESCE(SUM(CASE WHEN status = 'succeeded' THEN 1 ELSE 0 END), 0) AS succeeded_runs,
            COALESCE(SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END), 0) AS failed_runs,
            COALESCE(SUM(CASE WHEN status = 'cancelled' THEN 1 ELSE 0 END), 0) AS cancelled_runs,
            COALESCE(AVG(
                CASE WHEN started_at IS NOT NULL AND finished_at IS NOT NULL
                     THEN EXTRACT(EPOCH FROM (finished_at - started_at)) * 1000
                     ELSE NULL END
            ), 0) AS average_latency_ms
        FROM agent_task_run
        WHERE created_at &gt;= #{fromTime} AND created_at &lt; #{toTime}
        <if test="userId != null">AND created_by = #{userId}</if>
        </script>
        """)
    DashboardSummaryRow selectSummary(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId
    );

    /**
     * 获取令牌Totals。
     *
     * @param fromTime {@code fromTime}参数
     * @param toTime {@code toTime}参数
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        <script>
        SELECT
            COUNT(*) AS message_count,
            COALESCE(SUM(f.prompt_tokens), 0) AS prompt_tokens,
            COALESCE(SUM(f.completion_tokens), 0) AS completion_tokens,
            COALESCE(SUM(f.total_tokens), 0) AS total_tokens
        FROM agent_dashboard_token_fact f
        WHERE f.created_at &gt;= #{fromTime} AND f.created_at &lt; #{toTime}
        <if test="userId != null">AND f.user_id = #{userId}</if>
        </script>
        """)
    DashboardTokenTotalsRow selectTokenTotals(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId
    );

    /**
     * 获取接口KeyStatus。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT CASE
            WHEN EXISTS (
                SELECT 1
                FROM agent_api_credential c
                JOIN agent_api_application a
                  ON a.id = c.application_id
                 AND a.del_flag = '0'
                JOIN agent_service_account s
                  ON s.id = c.service_account_id
                 AND s.del_flag = '0'
                WHERE (a.owner_id = #{userId} OR a.create_by = #{userId})
                  AND a.status = 'active'
                  AND (a.expires_at IS NULL OR a.expires_at > CURRENT_TIMESTAMP)
                  AND s.status = 'active'
                  AND (s.expires_at IS NULL OR s.expires_at > CURRENT_TIMESTAMP)
                  AND c.revoked_at IS NULL
                  AND (c.expires_at IS NULL OR c.expires_at > CURRENT_TIMESTAMP)
            ) THEN 'active'
            WHEN EXISTS (
                SELECT 1
                FROM agent_api_credential c
                JOIN agent_api_application a ON a.id = c.application_id
                WHERE (a.owner_id = #{userId} OR a.create_by = #{userId})
            ) THEN 'inactive'
            ELSE 'unavailable'
        END
        """)
    String selectApiKeyStatus(@Param("userId") Long userId);

    /**
     * 处理{@code countActiveUsers}并返回对应结果。
     *
     * @return 处理结果
     */
    @Select("""
        SELECT COUNT(*)
        FROM sys_user
        WHERE del_flag = '0' AND status = '0'
        """)
    Long countActiveUsers();

    /**
     * 处理{@code countActiveUsersInRange}并返回对应结果。
     *
     * @param fromTime {@code fromTime}参数
     * @param toTime {@code toTime}参数
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        <script>
        SELECT COUNT(DISTINCT activity_user_id)
        FROM (
            SELECT created_by AS activity_user_id
            FROM agent_task_run
            WHERE created_by IS NOT NULL
              AND created_at &gt;= #{fromTime} AND created_at &lt; #{toTime}
            UNION
            SELECT c.user_id AS activity_user_id
            FROM agent_conversation_message m
            JOIN agent_conversation c ON c.id = m.conversation_id
            WHERE c.user_id IS NOT NULL
              AND m.created_at &gt;= #{fromTime} AND m.created_at &lt; #{toTime}
            UNION
            SELECT COALESCE(sa.owner_id, app.owner_id, cr.created_by, sa.create_by, app.create_by)
                   AS activity_user_id
            FROM agent_api_call api_call
            LEFT JOIN agent_service_account sa ON sa.id = api_call.service_account_id
            LEFT JOIN agent_api_application app ON app.id = api_call.application_id
            LEFT JOIN agent_api_credential cr ON cr.id = api_call.credential_id
            WHERE api_call.created_at &gt;= #{fromTime} AND api_call.created_at &lt; #{toTime}
              AND COALESCE(sa.owner_id, app.owner_id, cr.created_by, sa.create_by, app.create_by)
                  IS NOT NULL
        ) activity
        <if test="userId != null">WHERE activity_user_id = #{userId}</if>
        </script>
        """)
    Long countActiveUsersInRange(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId
    );

    /**
     * 获取{@code RecentUsers}。
     *
     * @param userId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT u.user_id, u.user_name AS username, u.nick_name AS display_name,
               MAX(activity.activity_at) AS last_active
        FROM (
            SELECT created_by AS activity_user_id, created_at AS activity_at
            FROM agent_task_run
            WHERE created_by IS NOT NULL
            UNION ALL
            SELECT c.user_id AS activity_user_id, m.created_at AS activity_at
            FROM agent_conversation_message m
            JOIN agent_conversation c ON c.id = m.conversation_id
            WHERE c.user_id IS NOT NULL
            UNION ALL
            SELECT COALESCE(sa.owner_id, app.owner_id, cr.created_by, sa.create_by, app.create_by)
                   AS activity_user_id,
                   api_call.created_at AS activity_at
            FROM agent_api_call api_call
            LEFT JOIN agent_service_account sa ON sa.id = api_call.service_account_id
            LEFT JOIN agent_api_application app ON app.id = api_call.application_id
            LEFT JOIN agent_api_credential cr ON cr.id = api_call.credential_id
            WHERE COALESCE(sa.owner_id, app.owner_id, cr.created_by, sa.create_by, app.create_by)
                  IS NOT NULL
        ) activity
        JOIN sys_user u ON u.user_id = activity.activity_user_id
                       AND u.del_flag = '0'
        <where>
            <if test="userId != null">u.user_id = #{userId}</if>
        </where>
        GROUP BY u.user_id, u.user_name, u.nick_name
        ORDER BY MAX(activity.activity_at) DESC, u.user_id DESC
        LIMIT #{limit}
        </script>
        """)
    List<DashboardRecentUserRow> selectRecentUsers(
        @Param("userId") Long userId,
        @Param("limit") int limit
    );

    /**
     * 获取Online用户Labels。
     *
     * @param usernames 名称
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT u.user_id, u.user_name AS username, u.nick_name AS display_name,
               COALESCE(string_agg(DISTINCT r.role_key, ',' ORDER BY r.role_key), '') AS role_keys
        FROM sys_user u
        LEFT JOIN sys_user_role ur ON ur.user_id = u.user_id
        LEFT JOIN sys_role r ON r.role_id = ur.role_id
                            AND r.status = '0' AND r.del_flag = '0'
        WHERE u.del_flag = '0'
          AND u.user_name IN
          <foreach collection="usernames" item="username" open="(" separator="," close=")">
            #{username}
          </foreach>
        GROUP BY u.user_id, u.user_name, u.nick_name
        </script>
        """)
    List<DashboardOnlineUserRow> selectOnlineUserLabels(
        @Param("usernames") List<String> usernames
    );

    /**
     * 获取{@code RecentRuns}。
     *
     * @param userId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT r.id AS run_id, r.task_id, r.created_by, r.trace_id, r.status,
               t.title AS task_title, d.name AS agent_name,
               r.created_at, r.started_at, r.finished_at
        FROM agent_task_run r
        JOIN agent_task t ON t.id = r.task_id AND t.del_flag = '0'
        LEFT JOIN agent_task_version tv ON tv.id = r.task_version_id
        LEFT JOIN agent_definition_version av ON av.id = tv.agent_version_id
        LEFT JOIN agent_definition d ON d.id = av.agent_id
        <where>
            <if test="userId != null">r.created_by = #{userId}</if>
        </where>
        ORDER BY r.created_at DESC, r.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<DashboardRecentRunRow> selectRecentRuns(
        @Param("userId") Long userId,
        @Param("limit") int limit
    );

    /**
     * 获取{@code RecentErrors}。
     *
     * @param userId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT r.id AS run_id, r.task_id, r.trace_id,
               d.name AS agent_name, s.step_key, s.error_summary, s.created_at
        FROM agent_run_step s
        JOIN agent_task_run r ON r.id = s.run_id
        LEFT JOIN agent_task_version tv ON tv.id = r.task_version_id
        LEFT JOIN agent_definition_version av ON av.id = tv.agent_version_id
        LEFT JOIN agent_definition d ON d.id = av.agent_id
        WHERE s.status = 'failed'
        <if test="userId != null">AND r.created_by = #{userId}</if>
        ORDER BY s.created_at DESC, s.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<DashboardRecentErrorRow> selectRecentErrors(
        @Param("userId") Long userId,
        @Param("limit") int limit
    );

    /**
     * 获取智能体健康状态。
     *
     * @param fromTime {@code fromTime}参数
     * @param toTime {@code toTime}参数
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        <script>
        SELECT
            COUNT(*) AS total_steps,
            COALESCE(SUM(CASE WHEN s.status = 'succeeded' THEN 1 ELSE 0 END), 0) AS succeeded_steps,
            COALESCE(SUM(CASE WHEN s.step_type = 'tool' THEN 1 ELSE 0 END), 0) AS tool_calls,
            COALESCE(AVG(
                CASE WHEN s.started_at IS NOT NULL AND s.finished_at IS NOT NULL
                     THEN EXTRACT(EPOCH FROM (s.finished_at - s.started_at)) * 1000
                     ELSE NULL END
            ), 0) AS average_latency_ms
        FROM agent_run_step s
        JOIN agent_task_run r ON r.id = s.run_id
        WHERE s.created_at &gt;= #{fromTime} AND s.created_at &lt; #{toTime}
          AND s.step_type IN ('agent', 'tool')
        <if test="userId != null">AND r.created_by = #{userId}</if>
        </script>
        """)
    DashboardAgentHealthRow selectAgentHealth(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId
    );

    /**
     * 获取工具Usage。
     *
     * @param fromTime {@code fromTime}参数
     * @param toTime {@code toTime}参数
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT s.tool_id,
               COALESCE(t.name, 'tool:' || CAST(s.tool_id AS text)) AS tool_name,
               COUNT(*) AS invocation_count
        FROM agent_run_step s
        JOIN agent_task_run r ON r.id = s.run_id
        LEFT JOIN agent_tool t ON t.id = s.tool_id
        WHERE s.created_at &gt;= #{fromTime} AND s.created_at &lt; #{toTime}
          AND s.step_type = 'tool' AND s.tool_id IS NOT NULL
        <if test="userId != null">AND r.created_by = #{userId}</if>
        GROUP BY s.tool_id, t.name
        ORDER BY COUNT(*) DESC, s.tool_id
        </script>
        """)
    List<DashboardToolUsageRow> selectToolUsage(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId
    );

    /**
     * 获取Hourly健康状态。
     *
     * @param fromTime {@code fromTime}参数
     * @param toTime {@code toTime}参数
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT date_trunc('hour', s.created_at) AS hour_bucket,
               COALESCE(AVG(
                   CASE WHEN s.started_at IS NOT NULL AND s.finished_at IS NOT NULL
                        THEN EXTRACT(EPOCH FROM (s.finished_at - s.started_at)) * 1000
                        ELSE NULL END
               ), 0) AS average_latency_ms,
               COUNT(*) AS total_steps
        FROM agent_run_step s
        JOIN agent_task_run r ON r.id = s.run_id
        WHERE s.created_at &gt;= #{fromTime} AND s.created_at &lt; #{toTime}
          AND s.step_type IN ('agent', 'tool')
        <if test="userId != null">AND r.created_by = #{userId}</if>
        GROUP BY date_trunc('hour', s.created_at)
        ORDER BY hour_bucket
        </script>
        """)
    List<DashboardHourRow> selectHourlyHealth(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId
    );

    /**
     * 获取智能体Performance。
     *
     * @param fromTime {@code fromTime}参数
     * @param toTime {@code toTime}参数
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT av.agent_id, d.name AS agent_name, av.version_no,
               COUNT(*) AS calls,
               COALESCE(SUM(CASE WHEN r.status = 'succeeded' THEN 1 ELSE 0 END), 0) AS succeeded_calls,
               COALESCE(AVG(
                   CASE WHEN r.started_at IS NOT NULL AND r.finished_at IS NOT NULL
                        THEN EXTRACT(EPOCH FROM (r.finished_at - r.started_at)) * 1000
                        ELSE NULL END
               ), 0) AS average_latency_ms
        FROM agent_task_run r
        JOIN agent_task_version tv ON tv.id = r.task_version_id
        JOIN agent_definition_version av ON av.id = tv.agent_version_id
        JOIN agent_definition d ON d.id = av.agent_id
        WHERE r.created_at &gt;= #{fromTime} AND r.created_at &lt; #{toTime}
        <if test="userId != null">AND r.created_by = #{userId}</if>
        GROUP BY av.agent_id, d.name, av.version_no
        ORDER BY COUNT(*) DESC, av.agent_id, av.version_no
        </script>
        """)
    List<DashboardAgentPerformanceRow> selectAgentPerformance(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId
    );

    /**
     * 获取令牌Trends。
     *
     * @param fromTime {@code fromTime}参数
     * @param toTime {@code toTime}参数
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT date_trunc('day', f.created_at) AS day_bucket,
               COUNT(*) AS calls,
               COALESCE(SUM(f.prompt_tokens), 0) AS prompt_tokens,
               COALESCE(SUM(f.completion_tokens), 0) AS completion_tokens,
               COALESCE(SUM(f.total_tokens), 0) AS total_tokens
        FROM agent_dashboard_token_fact f
        WHERE f.created_at &gt;= #{fromTime} AND f.created_at &lt; #{toTime}
        <if test="userId != null">AND f.user_id = #{userId}</if>
        GROUP BY date_trunc('day', f.created_at)
        ORDER BY day_bucket
        </script>
        """)
    List<DashboardTokenTrendRow> selectTokenTrends(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId
    );

    /**
     * 获取令牌Records。
     *
     * @param fromTime {@code fromTime}参数
     * @param toTime {@code toTime}参数
     * @param userId 资源标识
     * @param offset 起始位置或序号
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT f.source, f.id, f.user_id, u.user_name AS username, u.nick_name AS display_name,
               f.created_at, f.agent_id, f.agent_name, f.model_id,
               f.model_name, f.prompt_tokens, f.completion_tokens,
               f.total_tokens, f.status
        FROM agent_dashboard_token_fact f
        LEFT JOIN sys_user u ON u.user_id = f.user_id
        WHERE f.created_at &gt;= #{fromTime} AND f.created_at &lt; #{toTime}
        <if test="userId != null">AND f.user_id = #{userId}</if>
        ORDER BY f.created_at DESC, f.id DESC
        OFFSET #{offset} LIMIT #{limit}
        </script>
        """)
    List<DashboardTokenRecordRow> selectTokenRecords(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId,
        @Param("offset") int offset,
        @Param("limit") int limit
    );

    /**
     * 处理count令牌Records并返回对应结果。
     *
     * @param fromTime {@code fromTime}参数
     * @param toTime {@code toTime}参数
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM agent_dashboard_token_fact f
        WHERE f.created_at &gt;= #{fromTime} AND f.created_at &lt; #{toTime}
        <if test="userId != null">AND f.user_id = #{userId}</if>
        </script>
        """)
    Long countTokenRecords(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId
    );

    /**
     * 获取令牌Agents。
     *
     * @param fromTime {@code fromTime}参数
     * @param toTime {@code toTime}参数
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT f.agent_id, COALESCE(f.agent_name, CAST(f.agent_id AS text)) AS agent_name,
               COUNT(*) AS calls,
               COALESCE(SUM(f.prompt_tokens), 0) AS prompt_tokens,
               COALESCE(SUM(f.completion_tokens), 0) AS completion_tokens,
               COALESCE(SUM(f.total_tokens), 0) AS total_tokens
        FROM agent_dashboard_token_fact f
        WHERE f.created_at &gt;= #{fromTime} AND f.created_at &lt; #{toTime}
        <if test="userId != null">AND f.user_id = #{userId}</if>
        GROUP BY f.agent_id, f.agent_name
        ORDER BY COALESCE(SUM(f.total_tokens), 0) DESC, f.agent_id
        </script>
        """)
    List<DashboardTokenAgentRow> selectTokenAgents(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId
    );

    /**
     * 获取令牌Users。
     *
     * @param fromTime {@code fromTime}参数
     * @param toTime {@code toTime}参数
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT f.user_id, u.user_name AS username, u.nick_name AS display_name,
               COUNT(*) AS calls,
               COALESCE(SUM(f.prompt_tokens), 0) AS prompt_tokens,
               COALESCE(SUM(f.completion_tokens), 0) AS completion_tokens,
               COALESCE(SUM(f.total_tokens), 0) AS total_tokens
        FROM agent_dashboard_token_fact f
        LEFT JOIN sys_user u ON u.user_id = f.user_id
        WHERE f.created_at &gt;= #{fromTime} AND f.created_at &lt; #{toTime}
        <if test="userId != null">AND f.user_id = #{userId}</if>
        GROUP BY f.user_id, u.user_name, u.nick_name
        ORDER BY COALESCE(SUM(f.total_tokens), 0) DESC, f.user_id
        </script>
        """)
    List<DashboardTokenUserRow> selectTokenUsers(
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime,
        @Param("userId") Long userId
    );
}
