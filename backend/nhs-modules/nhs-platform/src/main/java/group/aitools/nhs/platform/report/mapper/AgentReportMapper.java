package group.aitools.nhs.platform.report.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.report.domain.AgentReport;
import group.aitools.nhs.platform.report.domain.AgentReportRun;
import group.aitools.nhs.platform.report.domain.AgentReportSubscription;
import group.aitools.nhs.platform.report.domain.ReportDeliveryJob;
import group.aitools.nhs.platform.report.domain.ReportNotificationOutboxEvent;
import group.aitools.nhs.platform.report.persistence.row.ReportExecutionPrincipalRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义智能体报表相关的数据访问契约。
 * Persistence boundary for saved reports, runs and delivery subscriptions. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentReportMapper {

    String REPORT_COLUMNS = "id, report_key, name, dataset_id, sql_template, "
        + "params_schema_json::text AS params_schema_json, visibility, owner_id, status, "
        + "create_by, create_time, update_by, update_time, del_flag, extra_json::text AS extra_json";

    String RUN_COLUMNS = "id, report_id, run_id, trigger_type, resolved_params_json::text AS resolved_params_json, "
        + "executed_sql, result_artifact_id, result_hash, row_count, status, error_summary, started_at, finished_at, created_at";

    String SUBSCRIPTION_COLUMNS = "id, report_id, schedule_type, cron_expr, interval_minutes, timezone, "
        + "params_json::text AS params_json, notify_policy_json::text AS notify_policy_json, status, "
        + "max_attempts, revision_no, last_run_at, next_run_at, create_by, create_time, update_by, "
        + "update_time, del_flag, extra_json::text AS extra_json";

    /**
     * 获取报表执行操作主体。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT user_id, user_name, user_type, status, del_flag
        FROM sys_user
        WHERE user_id = #{userId}
        """)
    ReportExecutionPrincipalRow selectReportExecutionPrincipal(@Param("userId") Long userId);

    /**
     * 获取报表执行操作主体ByUsername。
     *
     * @param username 名称
     * @return 处理结果
     */
    @Select("""
        SELECT user_id, user_name, user_type, status, del_flag
        FROM sys_user
        WHERE lower(user_name) = lower(#{username})
        LIMIT 1
        """)
    ReportExecutionPrincipalRow selectReportExecutionPrincipalByUsername(@Param("username") String username);

    /**
     * 获取报表执行角色Keys。
     *
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT DISTINCT r.role_key
        FROM sys_user_role ur
        JOIN sys_role r ON r.role_id = ur.role_id
        WHERE ur.user_id = #{userId} AND r.status = '0' AND r.del_flag = '0'
        ORDER BY r.role_key
        """)
    List<String> selectReportExecutionRoleKeys(@Param("userId") Long userId);

    /**
     * 获取{@code ById}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, report_key, name, dataset_id, sql_template,
               params_schema_json::text AS params_schema_json, visibility, owner_id, status,
               create_by, create_time, update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_report
        WHERE id = #{id} AND del_flag = '0'
        """)
    AgentReport selectById(@Param("id") Long id);

    /**
     * 获取{@code ByKey}。
     *
     * @param reportKey 报表Key参数
     * @param ownerId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, report_key, name, dataset_id, sql_template,
               params_schema_json::text AS params_schema_json, visibility, owner_id, status,
               create_by, create_time, update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_report
        WHERE report_key = #{reportKey} AND owner_id = #{ownerId} AND del_flag = '0'
        LIMIT 1
        """)
    AgentReport selectByKey(
        @Param("reportKey") String reportKey,
        @Param("ownerId") Long ownerId
    );

    /**
     * 获取{@code Visible}。
     *
     * @param ownerId 资源标识
     * @param admin {@code admin}参数
     * @param status 目标状态
     * @param search {@code search}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT id, report_key, name, dataset_id, sql_template,
               params_schema_json::text AS params_schema_json, visibility, owner_id, status,
               create_by, create_time, update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_report
        WHERE del_flag = '0'
          AND status &lt;&gt; 'archived'
          AND (
            owner_id = #{ownerId}
            OR visibility = 'enterprise_shared'
            <if test="admin">
              OR #{admin} = TRUE
            </if>
          )
        <if test="status != null and status != ''">
          AND status = #{status}
        </if>
        <if test="search != null and search != ''">
          AND (position(lower(#{search}) in lower(name)) &gt; 0
            OR position(lower(#{search}) in lower(report_key)) &gt; 0)
        </if>
        ORDER BY update_time DESC NULLS LAST, create_time DESC, id DESC
        LIMIT #{limit}
        </script>
        """)
    List<AgentReport> selectVisible(
        @Param("ownerId") Long ownerId,
        @Param("admin") boolean admin,
        @Param("status") String status,
        @Param("search") String search,
        @Param("limit") int limit
    );

    /**
     * 创建并保存{@code insert}。
     *
     * @param report 报表参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_report (
            id, report_key, name, dataset_id, sql_template, params_schema_json,
            visibility, owner_id, status, create_by, create_time, del_flag, extra_json
        ) VALUES (
            #{id}, #{reportKey}, #{name}, #{datasetId}, #{sqlTemplate},
            CAST(#{paramsSchemaJson} AS jsonb), #{visibility}, #{ownerId}, #{status},
            #{createBy}, #{createTime}, '0', CAST(#{extraJson} AS jsonb)
        )
        """)
    int insert(AgentReport report);

    /**
     * 更新{@code update}。
     *
     * @param report 报表参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_report
        SET name = #{name}, dataset_id = #{datasetId}, sql_template = #{sqlTemplate},
            params_schema_json = CAST(#{paramsSchemaJson} AS jsonb), visibility = #{visibility},
            status = #{status}, update_by = #{updateBy}, update_time = #{updateTime}
        WHERE id = #{id} AND del_flag = '0' AND status &lt;&gt; 'archived'
        """)
    int update(AgentReport report);

    /**
     * 更新{@code Lineage}。
     *
     * @param reportId 资源标识
     * @param ownerId 资源标识
     * @param extraJson {@code extraJson}参数
     * @param updatedAt {@code updatedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_report
        SET extra_json = CAST(#{extraJson} AS jsonb), update_by = #{ownerId}, update_time = #{updatedAt}
        WHERE id = #{reportId} AND owner_id = #{ownerId} AND del_flag = '0' AND status <> 'archived'
        """)
    int updateLineage(
        @Param("reportId") Long reportId,
        @Param("ownerId") Long ownerId,
        @Param("extraJson") String extraJson,
        @Param("updatedAt") LocalDateTime updatedAt
    );

    /**
     * 处理{@code archive}并返回对应结果。
     *
     * @param id 资源标识
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_report
        SET status = 'archived', del_flag = '1', update_by = #{actorId}, update_time = #{now}
        WHERE id = #{id} AND del_flag = '0'
        """)
    int archive(@Param("id") Long id, @Param("actorId") Long actorId, @Param("now") LocalDateTime now);

    /**
     * 获取{@code Runs}。
     *
     * @param reportId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, report_id, run_id, trigger_type, resolved_params_json::text AS resolved_params_json,
               executed_sql, result_artifact_id, result_hash, row_count, status, error_summary,
               started_at, finished_at, created_at
        FROM agent_report_run
        WHERE report_id = #{reportId}
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentReportRun> selectRuns(@Param("reportId") Long reportId, @Param("limit") int limit);

    /**
     * 创建并保存{@code Run}。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_report_run (
            id, report_id, run_id, trigger_type, resolved_params_json, executed_sql,
            status, started_at, created_at
        ) VALUES (
            #{id}, #{reportId}, #{runId}, #{triggerType}, CAST(#{resolvedParamsJson} AS jsonb),
            #{executedSql}, #{status}, #{startedAt}, #{createdAt}
        )
        """)
    int insertRun(AgentReportRun run);

    /**
     * 处理{@code finishRun}并返回对应结果。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_report_run
        SET run_id = #{runId}, status = #{status}, row_count = #{rowCount}, result_artifact_id = #{resultArtifactId},
            result_hash = #{resultHash},
            error_summary = #{errorSummary}, finished_at = #{finishedAt}
        WHERE id = #{id}
        """)
    int finishRun(AgentReportRun run);

    /**
     * 获取{@code Subscriptions}。
     *
     * @param reportId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, report_id, schedule_type, cron_expr, interval_minutes, timezone,
               params_json::text AS params_json, notify_policy_json::text AS notify_policy_json,
               status, max_attempts, revision_no, last_run_at, next_run_at, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_report_subscription
        WHERE report_id = #{reportId} AND del_flag = '0'
        ORDER BY create_time DESC, id DESC
        """)
    List<AgentReportSubscription> selectSubscriptions(@Param("reportId") Long reportId);

    /**
     * 获取{@code VisibleSubscriptions}。
     *
     * @param ownerId 资源标识
     * @param admin {@code admin}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, report_id, schedule_type, cron_expr, interval_minutes, timezone,
               params_json::text AS params_json, notify_policy_json::text AS notify_policy_json,
               status, max_attempts, revision_no, last_run_at, next_run_at, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_report_subscription
        WHERE del_flag = '0'
          AND (create_by = #{ownerId} OR #{admin} = TRUE)
        ORDER BY create_time DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentReportSubscription> selectVisibleSubscriptions(
        @Param("ownerId") Long ownerId,
        @Param("admin") boolean admin,
        @Param("limit") int limit
    );

    /**
     * 获取{@code Subscription}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, report_id, schedule_type, cron_expr, interval_minutes, timezone,
               params_json::text AS params_json, notify_policy_json::text AS notify_policy_json,
               status, max_attempts, revision_no, last_run_at, next_run_at, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_report_subscription
        WHERE id = #{id} AND del_flag = '0'
        """)
    AgentReportSubscription selectSubscription(@Param("id") Long id);

    /**
     * 创建并保存{@code Subscription}。
     *
     * @param subscription {@code subscription}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_report_subscription (
            id, report_id, schedule_type, cron_expr, interval_minutes, timezone,
            params_json, notify_policy_json, status, max_attempts, revision_no,
            next_run_at, create_by, create_time, del_flag, extra_json
        ) VALUES (
            #{id}, #{reportId}, #{scheduleType}, #{cronExpr}, #{intervalMinutes}, #{timezone},
            CAST(#{paramsJson} AS jsonb), CAST(#{notifyPolicyJson} AS jsonb), #{status},
            #{maxAttempts}, #{revisionNo}, #{nextRunAt}, #{createBy}, #{createTime}, '0', '{}'::jsonb
        )
        """)
    int insertSubscription(AgentReportSubscription subscription);

    /**
     * 更新{@code SubscriptionStatus}。
     *
     * @param id 资源标识
     * @param reportId 资源标识
     * @param status 目标状态
     * @param nextRunAt {@code nextRunAt}参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_report_subscription
        SET status = #{status}, next_run_at = #{nextRunAt}, revision_no = revision_no + 1,
            update_by = #{actorId}, update_time = #{now}
        WHERE id = #{id} AND report_id = #{reportId} AND del_flag = '0'
        """)
    int updateSubscriptionStatus(
        @Param("id") Long id,
        @Param("reportId") Long reportId,
        @Param("status") String status,
        @Param("nextRunAt") LocalDateTime nextRunAt,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code recordSubscriptionRun}并返回对应结果。
     *
     * @param id 资源标识
     * @param reportId 资源标识
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_report_subscription
        SET last_run_at = #{now}, update_by = #{actorId}, update_time = #{now}
        WHERE id = #{id} AND report_id = #{reportId} AND del_flag = '0'
        """)
    int recordSubscriptionRun(
        @Param("id") Long id,
        @Param("reportId") Long reportId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 删除{@code Subscription}。
     *
     * @param id 资源标识
     * @param reportId 资源标识
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_report_subscription
        SET del_flag = '1', status = 'paused', next_run_at = NULL,
            revision_no = revision_no + 1, update_by = #{actorId}, update_time = #{now}
        WHERE id = #{id} AND report_id = #{reportId} AND del_flag = '0'
        """)
    int deleteSubscription(
        @Param("id") Long id,
        @Param("reportId") Long reportId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code lockDueSubscriptions}并返回对应结果。
     *
     * @param now {@code now}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, report_id, schedule_type, cron_expr, interval_minutes, timezone,
               params_json::text AS params_json, notify_policy_json::text AS notify_policy_json,
               status, max_attempts, revision_no, last_run_at, next_run_at, create_by, create_time,
               update_by, update_time, del_flag, extra_json::text AS extra_json
        FROM agent_report_subscription
        WHERE status = 'active' AND del_flag = '0'
          AND next_run_at IS NOT NULL AND next_run_at <= #{now}
        ORDER BY next_run_at, id
        LIMIT #{limit}
        FOR UPDATE SKIP LOCKED
        """)
    List<AgentReportSubscription> lockDueSubscriptions(
        @Param("now") LocalDateTime now,
        @Param("limit") int limit
    );

    /**
     * 创建并保存Delivery作业。
     *
     * @param job 作业参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_report_delivery_job (
            id, subscription_id, report_id, recipient_id, scheduled_at, status,
            attempt_no, max_attempts, available_at, created_at
        ) VALUES (
            #{id}, #{subscriptionId}, #{reportId}, #{recipientId}, #{scheduledAt}, 'queued',
            0, #{maxAttempts}, #{availableAt}, #{createdAt}
        )
        ON CONFLICT (subscription_id, scheduled_at) DO NOTHING
        """)
    int insertDeliveryJob(ReportDeliveryJob job);

    /**
     * 处理advanceSubscription调度并返回对应结果。
     *
     * @param id 资源标识
     * @param revisionNo {@code revisionNo}参数
     * @param scheduledAt {@code scheduledAt}参数
     * @param nextRunAt {@code nextRunAt}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_report_subscription
        SET next_run_at = #{nextRunAt}, revision_no = revision_no + 1, update_time = #{now}
        WHERE id = #{id} AND revision_no = #{revisionNo} AND status = 'active'
          AND del_flag = '0' AND next_run_at = #{scheduledAt}
        """)
    int advanceSubscriptionSchedule(
        @Param("id") Long id,
        @Param("revisionNo") Long revisionNo,
        @Param("scheduledAt") LocalDateTime scheduledAt,
        @Param("nextRunAt") LocalDateTime nextRunAt,
        @Param("now") LocalDateTime now
    );

    /**
     * 判断{@code celPendingDeliveries}是否满足要求。
     *
     * @param subscriptionId 资源标识
     * @param reportId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_report_delivery_job
        SET status = 'cancelled', completed_at = #{now}, updated_at = #{now}
        WHERE subscription_id = #{subscriptionId} AND report_id = #{reportId}
          AND status IN ('queued', 'retry')
        """)
    int cancelPendingDeliveries(
        @Param("subscriptionId") Long subscriptionId,
        @Param("reportId") Long reportId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理claimDelivery作业并返回对应结果。
     *
     * @param workerId 资源标识
     * @param leaseToken lease令牌参数
     * @param now {@code now}参数
     * @param leaseUntil {@code leaseUntil}参数
     * @return 处理结果
     */
    @Select("""
        WITH candidate AS (
            SELECT id
            FROM agent_report_delivery_job
            WHERE available_at <= #{now}
              AND (status IN ('queued', 'retry')
                OR (status = 'running' AND lease_until < #{now}))
            ORDER BY available_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
        )
        UPDATE agent_report_delivery_job job
        SET status = 'running', attempt_no = attempt_no + 1,
            worker_id = #{workerId}, lease_token = #{leaseToken}, lease_until = #{leaseUntil},
            started_at = COALESCE(started_at, #{now}), updated_at = #{now}, last_error = NULL
        FROM candidate
        WHERE job.id = candidate.id
        RETURNING job.id, job.subscription_id, job.report_id, job.recipient_id,
                  job.scheduled_at, job.status, job.attempt_no, job.max_attempts,
                  job.available_at, job.lease_token, job.lease_until, job.worker_id,
                  job.report_run_id, job.last_error, job.started_at, job.completed_at,
                  job.created_at, job.updated_at
        """)
    ReportDeliveryJob claimDeliveryJob(
        @Param("workerId") String workerId,
        @Param("leaseToken") String leaseToken,
        @Param("now") LocalDateTime now,
        @Param("leaseUntil") LocalDateTime leaseUntil
    );

    /**
     * 处理completeDelivery作业并返回对应结果。
     *
     * @param jobId 资源标识
     * @param workerId 资源标识
     * @param leaseToken lease令牌参数
     * @param reportRunId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_report_delivery_job
        SET status = 'succeeded', report_run_id = #{reportRunId}, lease_token = NULL,
            lease_until = NULL, worker_id = NULL, completed_at = #{now}, updated_at = #{now}
        WHERE id = #{jobId} AND status = 'running' AND worker_id = #{workerId}
          AND lease_token = #{leaseToken} AND lease_until >= #{now}
        """)
    int completeDeliveryJob(
        @Param("jobId") Long jobId,
        @Param("workerId") String workerId,
        @Param("leaseToken") String leaseToken,
        @Param("reportRunId") Long reportRunId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理failDelivery作业并返回对应结果。
     *
     * @param jobId 资源标识
     * @param workerId 资源标识
     * @param leaseToken lease令牌参数
     * @param targetStatus 目标状态
     * @param availableAt {@code availableAt}参数
     * @param error {@code error}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_report_delivery_job
        SET status = #{targetStatus}, available_at = #{availableAt}, last_error = #{error},
            lease_token = NULL, lease_until = NULL, worker_id = NULL, updated_at = #{now},
            completed_at = CASE WHEN #{targetStatus} = 'dead' THEN #{now} ELSE NULL END
        WHERE id = #{jobId} AND status = 'running' AND worker_id = #{workerId}
          AND lease_token = #{leaseToken} AND lease_until >= #{now}
        """)
    int failDeliveryJob(
        @Param("jobId") Long jobId,
        @Param("workerId") String workerId,
        @Param("leaseToken") String leaseToken,
        @Param("targetStatus") String targetStatus,
        @Param("availableAt") LocalDateTime availableAt,
        @Param("error") String error,
        @Param("now") LocalDateTime now
    );

    /**
     * 判断celDelivery作业是否满足要求。
     *
     * @param jobId 资源标识
     * @param workerId 资源标识
     * @param leaseToken lease令牌参数
     * @param reason {@code reason}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_report_delivery_job
        SET status = 'cancelled', lease_token = NULL, lease_until = NULL, worker_id = NULL,
            completed_at = #{now}, updated_at = #{now}, last_error = #{reason}
        WHERE id = #{jobId} AND status = 'running' AND worker_id = #{workerId}
          AND lease_token = #{leaseToken} AND lease_until >= #{now}
        """)
    int cancelDeliveryJob(
        @Param("jobId") Long jobId,
        @Param("workerId") String workerId,
        @Param("leaseToken") String leaseToken,
        @Param("reason") String reason,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存报表通知Outbox。
     *
     * @param event 事件参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_outbox_event (
            id, event_type, aggregate_type, aggregate_id, event_key, payload_json,
            status, attempt_no, next_attempt_at, created_at
        ) VALUES (
            #{id}, #{eventType}, 'report_subscription', #{aggregateId}, #{eventKey},
            CAST(#{payloadJson} AS jsonb), 'pending', 0, #{nextAttemptAt}, #{createdAt}
        )
        ON CONFLICT (event_key) DO NOTHING
        """)
    int insertReportNotificationOutbox(ReportNotificationOutboxEvent event);

    /**
     * 处理lockDue报表通知Outbox并返回对应结果。
     *
     * @param now {@code now}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, event_type, aggregate_id, event_key, payload_json::text AS payload_json,
               status, attempt_no, next_attempt_at, last_error, created_at
        FROM agent_outbox_event
        WHERE event_type IN ('report.delivery.succeeded', 'report.delivery.failed')
          AND status = 'pending' AND next_attempt_at <= #{now}
        ORDER BY next_attempt_at, id
        LIMIT #{limit}
        FOR UPDATE SKIP LOCKED
        """)
    List<ReportNotificationOutboxEvent> lockDueReportNotificationOutbox(
        @Param("now") LocalDateTime now,
        @Param("limit") int limit
    );

    /**
     * 处理mark报表通知Published并返回对应结果。
     *
     * @param id 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_outbox_event
        SET status = 'published', attempt_no = attempt_no + 1,
            published_at = #{now}, last_error = NULL
        WHERE id = #{id} AND status = 'pending'
        """)
    int markReportNotificationPublished(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 处理mark报表通知Failed并返回对应结果。
     *
     * @param id 资源标识
     * @param status 目标状态
     * @param nextAttemptAt {@code nextAttemptAt}参数
     * @param error {@code error}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_outbox_event
        SET status = #{status}, attempt_no = attempt_no + 1,
            next_attempt_at = #{nextAttemptAt}, last_error = #{error}
        WHERE id = #{id} AND status = 'pending'
        """)
    int markReportNotificationFailed(
        @Param("id") Long id,
        @Param("status") String status,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
        @Param("error") String error
    );
}
