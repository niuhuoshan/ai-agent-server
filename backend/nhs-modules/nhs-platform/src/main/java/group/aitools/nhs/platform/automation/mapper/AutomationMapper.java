package group.aitools.nhs.platform.automation.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.automation.domain.AutomationFire;
import group.aitools.nhs.platform.automation.domain.AutomationTrigger;
import group.aitools.nhs.platform.automation.persistence.row.AutomationJobRow;
import group.aitools.nhs.platform.automation.persistence.row.AutomationTaskTargetRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义自动化相关的数据访问契约。
 */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AutomationMapper {

    /**
     * 获取{@code Triggers}。
     *
     * @param status 目标状态
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT id, trigger_key, name, trigger_type, task_id, task_version_id,
               task_revision_no, service_account_id, cron_expr, timezone, status,
               misfire_policy, max_catchup_count, max_attempts, input_template,
               last_run_at, next_run_at, revision_no, config_json::text AS config_json,
               create_by, create_time, update_by, update_time
        FROM agent_automation_trigger
        WHERE del_flag = '0'
        <if test="status != null and status != ''">AND status = #{status}</if>
        ORDER BY create_time DESC, id DESC
        LIMIT #{limit}
        </script>
        """)
    List<AutomationTrigger> selectTriggers(
        @Param("status") String status,
        @Param("limit") int limit
    );

    /**
     * 获取{@code Trigger}。
     *
     * @param triggerId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, trigger_key, name, trigger_type, task_id, task_version_id,
               task_revision_no, service_account_id, cron_expr, timezone, status,
               misfire_policy, max_catchup_count, max_attempts, input_template,
               last_run_at, next_run_at, revision_no, config_json::text AS config_json,
               create_by, create_time, update_by, update_time
        FROM agent_automation_trigger
        WHERE id = #{triggerId} AND del_flag = '0'
        """)
    AutomationTrigger selectTrigger(@Param("triggerId") Long triggerId);

    /**
     * 获取{@code TriggerByKey}。
     *
     * @param triggerKey {@code triggerKey}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, trigger_key, name, trigger_type, task_id, task_version_id,
               task_revision_no, service_account_id, cron_expr, timezone, status,
               misfire_policy, max_catchup_count, max_attempts, input_template,
               last_run_at, next_run_at, revision_no, config_json::text AS config_json,
               create_by, create_time, update_by, update_time
        FROM agent_automation_trigger
        WHERE trigger_key = #{triggerKey} AND del_flag = '0'
        """)
    AutomationTrigger selectTriggerByKey(@Param("triggerKey") String triggerKey);

    /**
     * 处理{@code lockTrigger}并返回对应结果。
     *
     * @param triggerId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, trigger_key, name, trigger_type, task_id, task_version_id,
               task_revision_no, service_account_id, cron_expr, timezone, status,
               misfire_policy, max_catchup_count, max_attempts, input_template,
               last_run_at, next_run_at, revision_no, config_json::text AS config_json,
               create_by, create_time, update_by, update_time
        FROM agent_automation_trigger
        WHERE id = #{triggerId} AND del_flag = '0'
        FOR UPDATE
        """)
    AutomationTrigger lockTrigger(@Param("triggerId") Long triggerId);

    /**
     * 处理lockRecurringTriggerBy任务Id并返回对应结果。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, trigger_key, name, trigger_type, task_id, task_version_id,
               task_revision_no, service_account_id, cron_expr, timezone, status,
               misfire_policy, max_catchup_count, max_attempts, input_template,
               last_run_at, next_run_at, revision_no, config_json::text AS config_json,
               create_by, create_time, update_by, update_time
        FROM agent_automation_trigger
        WHERE task_id = #{taskId} AND trigger_type = 'cron' AND del_flag = '0'
        ORDER BY create_time DESC, id DESC
        LIMIT 1
        FOR UPDATE
        """)
    AutomationTrigger lockRecurringTriggerByTaskId(@Param("taskId") Long taskId);

    /**
     * 获取任务Target。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT t.id AS task_id, t.current_version_id AS task_version_id,
               tv.version_no AS task_revision_no
        FROM agent_task t
        JOIN agent_task_version tv
          ON tv.id = t.current_version_id AND tv.task_id = t.id
        WHERE t.id = #{taskId} AND t.del_flag = '0'
        """)
    AutomationTaskTargetRow selectTaskTarget(@Param("taskId") Long taskId);

    /**
     * 创建并保存{@code Trigger}。
     *
     * @param trigger {@code trigger}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_automation_trigger (
            id, trigger_key, name, trigger_type, task_id, task_version_id,
            task_revision_no, service_account_id, cron_expr, timezone, status,
            misfire_policy, max_catchup_count, max_attempts, input_template,
            next_run_at, revision_no, config_json, create_by, create_time, del_flag, extra_json
        ) VALUES (
            #{id}, #{triggerKey}, #{name}, #{triggerType}, #{taskId}, #{taskVersionId},
            #{taskRevisionNo}, #{serviceAccountId}, #{cronExpr}, #{timezone}, #{status},
            #{misfirePolicy}, #{maxCatchupCount}, #{maxAttempts}, #{inputTemplate},
            #{nextRunAt}, #{revisionNo}, CAST(#{configJson} AS jsonb), #{createBy},
            #{createTime}, '0', '{}'::jsonb
        )
        ON CONFLICT DO NOTHING
        """)
    int insertTrigger(AutomationTrigger trigger);

    /**
     * 更新{@code Trigger}。
     *
     * @param trigger {@code trigger}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_automation_trigger
        SET name = #{name}, task_id = #{taskId}, task_version_id = #{taskVersionId},
            task_revision_no = #{taskRevisionNo}, service_account_id = #{serviceAccountId},
            cron_expr = #{cronExpr}, timezone = #{timezone}, status = #{status},
            misfire_policy = #{misfirePolicy}, max_catchup_count = #{maxCatchupCount},
            max_attempts = #{maxAttempts}, input_template = #{inputTemplate},
            next_run_at = #{nextRunAt}, config_json = CAST(#{configJson} AS jsonb),
            revision_no = revision_no + 1, update_by = #{updateBy}, update_time = #{updateTime}
        WHERE id = #{id} AND revision_no = #{revisionNo} AND del_flag = '0'
          AND status <> 'archived'
        """)
    int updateTrigger(AutomationTrigger trigger);

    /**
     * 处理{@code lockDueTriggers}并返回对应结果。
     *
     * @param now {@code now}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, trigger_key, name, trigger_type, task_id, task_version_id,
               task_revision_no, service_account_id, cron_expr, timezone, status,
               misfire_policy, max_catchup_count, max_attempts, input_template,
               last_run_at, next_run_at, revision_no, config_json::text AS config_json,
               create_by, create_time, update_by, update_time
        FROM agent_automation_trigger
        WHERE trigger_type = 'cron' AND status = 'active' AND del_flag = '0'
          AND next_run_at IS NOT NULL AND next_run_at <= #{now}
        ORDER BY next_run_at, id
        LIMIT #{limit}
        FOR UPDATE SKIP LOCKED
        """)
    List<AutomationTrigger> lockDueTriggers(
        @Param("now") LocalDateTime now,
        @Param("limit") int limit
    );

    /**
     * 更新调度。
     *
     * @param triggerId 资源标识
     * @param revisionNo {@code revisionNo}参数
     * @param lastRunAt {@code lastRunAt}参数
     * @param nextRunAt {@code nextRunAt}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_automation_trigger
        SET last_run_at = COALESCE(#{lastRunAt}, last_run_at), next_run_at = #{nextRunAt},
            update_time = #{now}
        WHERE id = #{triggerId} AND revision_no = #{revisionNo}
          AND trigger_type = 'cron' AND status = 'active' AND del_flag = '0'
        """)
    int updateSchedule(
        @Param("triggerId") Long triggerId,
        @Param("revisionNo") Long revisionNo,
        @Param("lastRunAt") LocalDateTime lastRunAt,
        @Param("nextRunAt") LocalDateTime nextRunAt,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code markTriggerError}并返回对应结果。
     *
     * @param triggerId 资源标识
     * @param revisionNo {@code revisionNo}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_automation_trigger
        SET status = 'error', next_run_at = NULL, update_time = #{now}
        WHERE id = #{triggerId} AND revision_no = #{revisionNo}
          AND status = 'active' AND del_flag = '0'
        """)
    int markTriggerError(
        @Param("triggerId") Long triggerId,
        @Param("revisionNo") Long revisionNo,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存{@code Fire}。
     *
     * @param fire {@code fire}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_automation_fire (
            id, trigger_id, trigger_revision_no, service_account_id, source_type,
            fire_key, payload_hash, payload_json, scheduled_at, status, accepted_at
        ) VALUES (
            #{id}, #{triggerId}, #{triggerRevisionNo}, #{serviceAccountId}, #{sourceType},
            #{fireKey}, #{payloadHash}, CAST(#{payloadJson} AS jsonb), #{scheduledAt},
            #{status}, #{acceptedAt}
        )
        ON CONFLICT DO NOTHING
        """)
    int insertFire(AutomationFire fire);

    /**
     * 获取{@code FireByKey}。
     *
     * @param triggerId 资源标识
     * @param fireKey {@code fireKey}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, trigger_id, trigger_revision_no, service_account_id, source_type,
               fire_key, payload_hash, payload_json::text AS payload_json, scheduled_at,
               status, job_id, run_id, attempt_no, last_error, accepted_at,
               dispatched_at, completed_at
        FROM agent_automation_fire
        WHERE trigger_id = #{triggerId} AND fire_key = #{fireKey}
        """)
    AutomationFire selectFireByKey(
        @Param("triggerId") Long triggerId,
        @Param("fireKey") String fireKey
    );

    /**
     * 获取{@code Fire}。
     *
     * @param fireId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, trigger_id, trigger_revision_no, service_account_id, source_type,
               fire_key, payload_hash, payload_json::text AS payload_json, scheduled_at,
               status, job_id, run_id, attempt_no, last_error, accepted_at,
               dispatched_at, completed_at
        FROM agent_automation_fire
        WHERE id = #{fireId}
        """)
    AutomationFire selectFire(@Param("fireId") Long fireId);

    /**
     * 创建并保存Fire作业。
     *
     * @param id 资源标识
     * @param fireId 资源标识
     * @param bizKey {@code bizKey}参数
     * @param payloadJson {@code payloadJson}参数
     * @param maxAttempts {@code maxAttempts}参数
     * @param availableAt {@code availableAt}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_job_queue (
            id, fire_id, job_type, biz_key, payload_json, status, priority,
            attempt_no, max_attempts, available_at, created_at
        ) VALUES (
            #{id}, #{fireId}, 'automation_fire', #{bizKey}, CAST(#{payloadJson} AS jsonb),
            'queued', 0, 0, #{maxAttempts}, #{availableAt}, #{availableAt}
        )
        ON CONFLICT DO NOTHING
        """)
    int insertFireJob(
        @Param("id") Long id,
        @Param("fireId") Long fireId,
        @Param("bizKey") String bizKey,
        @Param("payloadJson") String payloadJson,
        @Param("maxAttempts") int maxAttempts,
        @Param("availableAt") LocalDateTime availableAt
    );

    /**
     * 处理bindFire作业并返回对应结果。
     *
     * @param fireId 资源标识
     * @param jobId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_automation_fire
        SET job_id = #{jobId}
        WHERE id = #{fireId} AND job_id IS NULL AND status = 'queued'
        """)
    int bindFireJob(@Param("fireId") Long fireId, @Param("jobId") Long jobId);

    /**
     * 创建并保存回调通知Nonce。
     *
     * @param id 资源标识
     * @param credentialId 资源标识
     * @param nonceHash {@code nonceHash}参数
     * @param requestTimestamp {@code requestTimestamp}参数
     * @param expiresAt {@code expiresAt}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_webhook_nonce (
            id, credential_id, nonce_hash, request_timestamp, expires_at, created_at
        ) VALUES (
            #{id}, #{credentialId}, #{nonceHash}, #{requestTimestamp}, #{expiresAt}, #{now}
        )
        ON CONFLICT DO NOTHING
        """)
    int insertWebhookNonce(
        @Param("id") Long id,
        @Param("credentialId") Long credentialId,
        @Param("nonceHash") String nonceHash,
        @Param("requestTimestamp") LocalDateTime requestTimestamp,
        @Param("expiresAt") LocalDateTime expiresAt,
        @Param("now") LocalDateTime now
    );

    /**
     * 删除{@code ExpiredNonces}。
     *
     * @param before {@code before}参数
     * @return 处理结果
     */
    @Delete("DELETE FROM agent_webhook_nonce WHERE expires_at < #{before}")
    int deleteExpiredNonces(@Param("before") LocalDateTime before);

    /**
     * 处理claim作业并返回对应结果。
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
            FROM agent_job_queue
            WHERE job_type = 'automation_fire'
              AND available_at <= #{now}
              AND (status = 'queued' OR (status = 'running' AND lease_until < #{now}))
            ORDER BY priority DESC, available_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
        )
        UPDATE agent_job_queue job
        SET status = 'running', attempt_no = attempt_no + 1,
            worker_id = #{workerId}, lease_token = #{leaseToken},
            lease_until = #{leaseUntil}, started_at = COALESCE(started_at, #{now}),
            updated_at = #{now}, last_error = NULL
        FROM candidate
        WHERE job.id = candidate.id
        RETURNING job.id, job.fire_id, job.job_type, job.biz_key,
                  job.payload_json::text AS payload_json, job.status, job.attempt_no,
                  job.max_attempts, job.lease_until, job.worker_id, job.lease_token
        """)
    AutomationJobRow claimJob(
        @Param("workerId") String workerId,
        @Param("leaseToken") String leaseToken,
        @Param("now") LocalDateTime now,
        @Param("leaseUntil") LocalDateTime leaseUntil
    );

    /**
     * 处理{@code markFireRunning}并返回对应结果。
     *
     * @param fireId 资源标识
     * @param jobId 资源标识
     * @param attemptNo {@code attemptNo}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_automation_fire
        SET status = 'running', attempt_no = #{attemptNo}, last_error = NULL
        WHERE id = #{fireId} AND job_id = #{jobId}
          AND status IN ('queued', 'retry', 'running')
        """)
    int markFireRunning(
        @Param("fireId") Long fireId,
        @Param("jobId") Long jobId,
        @Param("attemptNo") int attemptNo
    );

    /**
     * 处理renew作业并返回对应结果。
     *
     * @param jobId 资源标识
     * @param workerId 资源标识
     * @param leaseToken lease令牌参数
     * @param now {@code now}参数
     * @param leaseUntil {@code leaseUntil}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_job_queue
        SET lease_until = #{leaseUntil}, updated_at = #{now}
        WHERE id = #{jobId} AND status = 'running' AND worker_id = #{workerId}
          AND lease_token = #{leaseToken} AND lease_until >= #{now}
        """)
    int renewJob(
        @Param("jobId") Long jobId,
        @Param("workerId") String workerId,
        @Param("leaseToken") String leaseToken,
        @Param("now") LocalDateTime now,
        @Param("leaseUntil") LocalDateTime leaseUntil
    );

    /**
     * 处理complete作业并返回对应结果。
     *
     * @param jobId 资源标识
     * @param workerId 资源标识
     * @param leaseToken lease令牌参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_job_queue
        SET status = 'success', lease_until = NULL, worker_id = NULL, lease_token = NULL,
            completed_at = #{now}, updated_at = #{now}
        WHERE id = #{jobId} AND status = 'running' AND worker_id = #{workerId}
          AND lease_token = #{leaseToken} AND lease_until >= #{now}
        """)
    int completeJob(
        @Param("jobId") Long jobId,
        @Param("workerId") String workerId,
        @Param("leaseToken") String leaseToken,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code completeFire}并返回对应结果。
     *
     * @param fireId 资源标识
     * @param jobId 资源标识
     * @param runId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_automation_fire
        SET status = 'dispatched', run_id = #{runId}, dispatched_at = #{now},
            completed_at = #{now}, last_error = NULL
        WHERE id = #{fireId} AND job_id = #{jobId} AND status = 'running'
        """)
    int completeFire(
        @Param("fireId") Long fireId,
        @Param("jobId") Long jobId,
        @Param("runId") Long runId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理fail作业并返回对应结果。
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
        UPDATE agent_job_queue
        SET status = #{targetStatus}, available_at = #{availableAt}, last_error = #{error},
            lease_until = NULL, worker_id = NULL, lease_token = NULL, updated_at = #{now},
            completed_at = CASE WHEN #{targetStatus} = 'dead' THEN #{now} ELSE completed_at END
        WHERE id = #{jobId} AND status = 'running' AND worker_id = #{workerId}
          AND lease_token = #{leaseToken} AND lease_until >= #{now}
        """)
    int failJob(
        @Param("jobId") Long jobId,
        @Param("workerId") String workerId,
        @Param("leaseToken") String leaseToken,
        @Param("targetStatus") String targetStatus,
        @Param("availableAt") LocalDateTime availableAt,
        @Param("error") String error,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code failFire}并返回对应结果。
     *
     * @param fireId 资源标识
     * @param jobId 资源标识
     * @param attemptNo {@code attemptNo}参数
     * @param targetStatus 目标状态
     * @param error {@code error}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_automation_fire
        SET status = #{targetStatus}, attempt_no = #{attemptNo}, last_error = #{error},
            completed_at = CASE WHEN #{targetStatus} = 'dead' THEN #{now} ELSE NULL END
        WHERE id = #{fireId} AND job_id = #{jobId} AND status = 'running'
        """)
    int failFire(
        @Param("fireId") Long fireId,
        @Param("jobId") Long jobId,
        @Param("attemptNo") int attemptNo,
        @Param("targetStatus") String targetStatus,
        @Param("error") String error,
        @Param("now") LocalDateTime now
    );
}
