package group.aitools.nhs.platform.sandbox.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobRow;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobOutputRow;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxRunnerRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义沙箱Runner相关的数据访问契约。
 */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface SandboxRunnerMapper {

    /**
     * 获取{@code RunnerByKey}。
     *
     * @param runnerKey {@code runnerKey}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, runner_key, name, secret_hash, status,
               capabilities_json::text AS capabilities_json, max_concurrency,
               active_job_count, runner_version, last_heartbeat_at,
               heartbeat_expires_at
        FROM agent_sandbox_runner
        WHERE runner_key = #{runnerKey}
        """)
    SandboxRunnerRow selectRunnerByKey(@Param("runnerKey") String runnerKey);

    /**
     * 处理{@code upsertRunner}并返回对应结果。
     *
     * @param id 资源标识
     * @param runnerKey {@code runnerKey}参数
     * @param name 名称
     * @param secretHash {@code secretHash}参数
     * @param capabilitiesJson {@code capabilitiesJson}参数
     * @param maxConcurrency {@code maxConcurrency}参数
     * @param runnerVersion runner版本参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_sandbox_runner (
            id, runner_key, name, secret_hash, status, capabilities_json,
            max_concurrency, active_job_count, runner_version,
            registered_at, secret_rotated_at, updated_at
        ) VALUES (
            #{id}, #{runnerKey}, #{name}, #{secretHash}, 'active',
            CAST(#{capabilitiesJson} AS jsonb), #{maxConcurrency}, 0,
            #{runnerVersion}, #{now}, #{now}, #{now}
        )
        ON CONFLICT (runner_key) DO UPDATE SET
            name = EXCLUDED.name,
            secret_hash = EXCLUDED.secret_hash,
            status = 'active',
            capabilities_json = EXCLUDED.capabilities_json,
            max_concurrency = EXCLUDED.max_concurrency,
            active_job_count = 0,
            runner_version = EXCLUDED.runner_version,
            last_heartbeat_at = NULL,
            heartbeat_expires_at = NULL,
            secret_rotated_at = EXCLUDED.secret_rotated_at,
            updated_at = EXCLUDED.updated_at
        """)
    int upsertRunner(
        @Param("id") Long id,
        @Param("runnerKey") String runnerKey,
        @Param("name") String name,
        @Param("secretHash") String secretHash,
        @Param("capabilitiesJson") String capabilitiesJson,
        @Param("maxConcurrency") int maxConcurrency,
        @Param("runnerVersion") String runnerVersion,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存{@code Nonce}。
     *
     * @param id 资源标识
     * @param runnerId 资源标识
     * @param nonceHash {@code nonceHash}参数
     * @param requestTimestamp {@code requestTimestamp}参数
     * @param expiresAt {@code expiresAt}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_sandbox_nonce (
            id, runner_id, nonce_hash, request_timestamp, expires_at, created_at
        ) VALUES (
            #{id}, #{runnerId}, #{nonceHash}, #{requestTimestamp}, #{expiresAt}, #{now}
        )
        ON CONFLICT DO NOTHING
        """)
    int insertNonce(
        @Param("id") Long id,
        @Param("runnerId") Long runnerId,
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
    @Delete("DELETE FROM agent_sandbox_nonce WHERE expires_at < #{before}")
    int deleteExpiredNonces(@Param("before") LocalDateTime before);

    /**
     * 处理{@code heartbeat}并返回对应结果。
     *
     * @param runnerId 资源标识
     * @param capabilitiesJson {@code capabilitiesJson}参数
     * @param maxConcurrency {@code maxConcurrency}参数
     * @param activeJobCount active作业Count参数
     * @param runnerVersion runner版本参数
     * @param now {@code now}参数
     * @param expiresAt {@code expiresAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_sandbox_runner
        SET capabilities_json = CAST(#{capabilitiesJson} AS jsonb),
            max_concurrency = #{maxConcurrency},
            active_job_count = LEAST(#{activeJobCount}, #{maxConcurrency}),
            runner_version = #{runnerVersion},
            status = CASE WHEN status = 'stale' THEN 'active' ELSE status END,
            last_heartbeat_at = #{now}, heartbeat_expires_at = #{expiresAt},
            updated_at = #{now}
        WHERE id = #{runnerId} AND status IN ('active', 'stale', 'draining')
        """)
    int heartbeat(
        @Param("runnerId") Long runnerId,
        @Param("capabilitiesJson") String capabilitiesJson,
        @Param("maxConcurrency") int maxConcurrency,
        @Param("activeJobCount") int activeJobCount,
        @Param("runnerVersion") String runnerVersion,
        @Param("now") LocalDateTime now,
        @Param("expiresAt") LocalDateTime expiresAt
    );

    /**
     * 处理{@code markStaleRunners}并返回对应结果。
     *
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_sandbox_runner
        SET status = 'stale', updated_at = #{now}
        WHERE status = 'active' AND heartbeat_expires_at < #{now}
        """)
    int markStaleRunners(@Param("now") LocalDateTime now);

    /**
     * 处理{@code countAvailableRunners}并返回对应结果。
     *
     * @param templateKey 模板Key参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Select("""
        SELECT COUNT(*)
        FROM agent_sandbox_runner runner
        WHERE runner.status = 'active'
          AND runner.heartbeat_expires_at >= #{now}
          AND jsonb_exists(runner.capabilities_json, #{templateKey})
        """)
    int countAvailableRunners(
        @Param("templateKey") String templateKey,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code expireExhaustedJobs}并返回对应结果。
     *
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_sandbox_job
        SET status = 'expired', finished_at = #{now}, updated_at = #{now},
            token_consumed_at = #{now}, failure_code = 'LEASE_ATTEMPTS_EXHAUSTED',
            failure_message = 'sandbox job lease attempts exhausted'
        WHERE status IN ('leased', 'running') AND lease_until < #{now}
          AND attempt_no >= 10
        """)
    int expireExhaustedJobs(@Param("now") LocalDateTime now);

    /**
     * 处理claim作业并返回对应结果。
     *
     * @param runnerId 资源标识
     * @param jobTokenHash 作业令牌Hash参数
     * @param now {@code now}参数
     * @param leaseUntil {@code leaseUntil}参数
     * @return 处理结果
     */
    @Select("""
        WITH eligible_runner AS (
            SELECT id, capabilities_json, max_concurrency
            FROM agent_sandbox_runner runner
            WHERE runner.id = #{runnerId}
              AND runner.status = 'active'
              AND runner.heartbeat_expires_at >= #{now}
              AND (
                  SELECT COUNT(*) FROM agent_sandbox_job active_job
                  WHERE active_job.assigned_runner_id = runner.id
                    AND active_job.status IN ('leased', 'running')
                    AND active_job.lease_until >= #{now}
              ) < runner.max_concurrency
        ), candidate AS (
            SELECT job.id
            FROM agent_sandbox_job job
            JOIN eligible_runner runner
              ON jsonb_exists(runner.capabilities_json, job.template_key)
            WHERE job.attempt_no < 10
              AND (job.status = 'queued'
                   OR (job.status IN ('leased', 'running') AND job.lease_until < #{now}))
            ORDER BY job.priority DESC, job.created_at, job.id
            LIMIT 1
            FOR UPDATE OF job SKIP LOCKED
        )
        UPDATE agent_sandbox_job job
        SET status = 'leased', assigned_runner_id = #{runnerId},
            job_token_hash = #{jobTokenHash}, lease_until = #{leaseUntil},
            attempt_no = attempt_no + 1, started_at = NULL, finished_at = NULL,
            token_consumed_at = NULL, exit_code = NULL, stdout_text = NULL,
            stderr_text = NULL, output_manifest_json = '[]'::jsonb,
            resource_usage_json = '{}'::jsonb, failure_code = NULL,
            failure_message = NULL, updated_at = #{now}
        FROM candidate
        WHERE job.id = candidate.id
        RETURNING job.id, job.source_type, job.owner_user_id, job.conversation_id,
                  job.task_id, job.run_id, job.step_id, job.tool_id,
                  job.trace_id, job.request_hash, job.template_key,
                  job.script_language, job.script_text,
                  job.argv_json::text AS argv_json, job.workspace_path, job.workspace_key,
                  job.workspace_access, job.network_policy,
                  job.allowed_hosts_json::text AS allowed_hosts_json,
                  job.skill_manifest_json::text AS skill_manifest_json,
                  job.skill_manifest_hash,
                  job.timeout_seconds, job.memory_mb, job.cpu_millis,
                  job.pids_limit, job.max_output_bytes, job.output_sequence,
                  job.output_bytes, job.output_truncated, job.status, job.priority,
                  job.assigned_runner_id, job.job_token_hash, job.lease_until,
                  job.attempt_no, job.created_at, job.updated_at
        """)
    SandboxJobRow claimJob(
        @Param("runnerId") Long runnerId,
        @Param("jobTokenHash") String jobTokenHash,
        @Param("now") LocalDateTime now,
        @Param("leaseUntil") LocalDateTime leaseUntil
    );

    /**
     * 获取作业。
     *
     * @param jobId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, source_type, owner_user_id, conversation_id,
               task_id, run_id, step_id, tool_id, external_reply_id,
               tool_call_id, tool_name, trace_id, request_hash,
               template_key, script_language, script_text,
               argv_json::text AS argv_json, workspace_path, workspace_key,
               workspace_access, network_policy,
               allowed_hosts_json::text AS allowed_hosts_json,
               skill_manifest_json::text AS skill_manifest_json, skill_manifest_hash,
               timeout_seconds, memory_mb, cpu_millis, pids_limit,
               max_output_bytes, output_sequence, output_bytes, output_truncated,
               status, priority, assigned_runner_id,
               job_token_hash, lease_until, attempt_no, started_at, finished_at,
               resume_dispatched_at,
               exit_code, stdout_text, stderr_text,
               output_manifest_json::text AS output_manifest_json,
               resource_usage_json::text AS resource_usage_json,
               failure_code, failure_message, created_at, updated_at
        FROM agent_sandbox_job WHERE id = #{jobId}
        """)
    SandboxJobRow selectJob(@Param("jobId") Long jobId);

    /**
     * 获取Owned对话作业。
     *
     * @param jobId 资源标识
     * @param ownerUserId 资源标识
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, source_type, owner_user_id, conversation_id,
               task_id, run_id, step_id, tool_id, trace_id, request_hash,
               template_key, script_language, script_text,
               argv_json::text AS argv_json, workspace_path, workspace_key, workspace_access,
               network_policy, allowed_hosts_json::text AS allowed_hosts_json,
               skill_manifest_json::text AS skill_manifest_json, skill_manifest_hash,
               timeout_seconds, memory_mb, cpu_millis, pids_limit,
               max_output_bytes, output_sequence, output_bytes, output_truncated,
               status, priority, assigned_runner_id, lease_until, attempt_no,
               started_at, finished_at, exit_code, stdout_text, stderr_text,
               output_manifest_json::text AS output_manifest_json,
               resource_usage_json::text AS resource_usage_json,
               failure_code, failure_message, created_at, updated_at
        FROM agent_sandbox_job
        WHERE id = #{jobId} AND source_type = 'chat_code'
          AND owner_user_id = #{ownerUserId} AND conversation_id = #{conversationId}
        """)
    SandboxJobRow selectOwnedChatJob(
        @Param("jobId") Long jobId,
        @Param("ownerUserId") Long ownerUserId,
        @Param("conversationId") Long conversationId
    );

    /**
     * 获取对话作业OwnedBy用户。
     *
     * @param jobId 资源标识
     * @param ownerUserId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, source_type, owner_user_id, conversation_id,
               trace_id, request_hash, template_key, script_language,
               workspace_path, workspace_key,
               timeout_seconds, memory_mb, cpu_millis, pids_limit,
               max_output_bytes, output_sequence, output_bytes, output_truncated,
               skill_manifest_json::text AS skill_manifest_json, skill_manifest_hash,
               status, priority, assigned_runner_id, lease_until, attempt_no,
               started_at, finished_at, exit_code,
               resource_usage_json::text AS resource_usage_json,
               failure_code, failure_message, created_at, updated_at
        FROM agent_sandbox_job
        WHERE id = #{jobId} AND source_type = 'chat_code'
          AND owner_user_id = #{ownerUserId}
        """)
    SandboxJobRow selectChatJobOwnedByUser(
        @Param("jobId") Long jobId,
        @Param("ownerUserId") Long ownerUserId
    );

    /**
     * 获取Owned对话Jobs。
     *
     * @param ownerUserId 资源标识
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, source_type, owner_user_id, conversation_id,
               trace_id, request_hash, template_key, script_language,
               workspace_path, workspace_key,
               timeout_seconds, memory_mb, cpu_millis, pids_limit,
               max_output_bytes, output_sequence, output_bytes, output_truncated,
               skill_manifest_json::text AS skill_manifest_json, skill_manifest_hash,
               status, priority, assigned_runner_id, lease_until, attempt_no,
               started_at, finished_at, exit_code,
               resource_usage_json::text AS resource_usage_json,
               failure_code, failure_message, created_at, updated_at
        FROM agent_sandbox_job
        WHERE source_type = 'chat_code'
          AND owner_user_id = #{ownerUserId} AND conversation_id = #{conversationId}
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<SandboxJobRow> selectOwnedChatJobs(
        @Param("ownerUserId") Long ownerUserId,
        @Param("conversationId") Long conversationId,
        @Param("limit") int limit
    );

    /**
     * 处理start作业并返回对应结果。
     *
     * @param jobId 资源标识
     * @param runnerId 资源标识
     * @param jobTokenHash 作业令牌Hash参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_sandbox_job
        SET status = 'running', started_at = COALESCE(started_at, #{now}),
            updated_at = #{now}
        WHERE id = #{jobId} AND assigned_runner_id = #{runnerId}
          AND status = 'leased' AND job_token_hash = #{jobTokenHash}
          AND lease_until >= #{now} AND token_consumed_at IS NULL
        """)
    int startJob(
        @Param("jobId") Long jobId,
        @Param("runnerId") Long runnerId,
        @Param("jobTokenHash") String jobTokenHash,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理renew作业并返回对应结果。
     *
     * @param jobId 资源标识
     * @param runnerId 资源标识
     * @param jobTokenHash 作业令牌Hash参数
     * @param now {@code now}参数
     * @param leaseUntil {@code leaseUntil}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_sandbox_job
        SET lease_until = #{leaseUntil}, updated_at = #{now}
        WHERE id = #{jobId} AND assigned_runner_id = #{runnerId}
          AND status IN ('leased', 'running') AND job_token_hash = #{jobTokenHash}
          AND lease_until >= #{now} AND token_consumed_at IS NULL
        """)
    int renewJob(
        @Param("jobId") Long jobId,
        @Param("runnerId") Long runnerId,
        @Param("jobTokenHash") String jobTokenHash,
        @Param("now") LocalDateTime now,
        @Param("leaseUntil") LocalDateTime leaseUntil
    );

    /**
     * 处理complete作业并返回对应结果。
     *
     * @param jobId 资源标识
     * @param runnerId 资源标识
     * @param jobTokenHash 作业令牌Hash参数
     * @param status 目标状态
     * @param exitCode {@code exitCode}参数
     * @param stdoutText 待处理内容
     * @param stderrText 待处理内容
     * @param outputManifestJson {@code outputManifestJson}参数
     * @param resourceUsageJson 资源UsageJson参数
     * @param failureCode {@code failureCode}参数
     * @param failureMessage 待处理内容
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_sandbox_job
        SET status = #{status}, exit_code = #{exitCode}, stdout_text = #{stdoutText},
            stderr_text = #{stderrText},
            output_manifest_json = CAST(#{outputManifestJson} AS jsonb),
            resource_usage_json = CAST(#{resourceUsageJson} AS jsonb),
            failure_code = #{failureCode}, failure_message = #{failureMessage},
            finished_at = #{now}, token_consumed_at = #{now},
            lease_until = NULL, updated_at = #{now}
        WHERE id = #{jobId} AND assigned_runner_id = #{runnerId}
          AND status IN ('leased', 'running') AND job_token_hash = #{jobTokenHash}
          AND lease_until >= #{now} AND token_consumed_at IS NULL
        """)
    int completeJob(
        @Param("jobId") Long jobId,
        @Param("runnerId") Long runnerId,
        @Param("jobTokenHash") String jobTokenHash,
        @Param("status") String status,
        @Param("exitCode") Integer exitCode,
        @Param("stdoutText") String stdoutText,
        @Param("stderrText") String stderrText,
        @Param("outputManifestJson") String outputManifestJson,
        @Param("resourceUsageJson") String resourceUsageJson,
        @Param("failureCode") String failureCode,
        @Param("failureMessage") String failureMessage,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存作业。
     *
     * @param id 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param toolId 资源标识
     * @param externalReplyId 资源标识
     * @param toolCallId 资源标识
     * @param toolName 名称
     * @param traceId 资源标识
     * @param requestHash {@code requestHash}参数
     * @param templateKey 模板Key参数
     * @param argvJson {@code argvJson}参数
     * @param workspacePath 工作空间Path参数
     * @param workspaceAccess 工作空间Access参数
     * @param networkPolicy network策略参数
     * @param allowedHostsJson {@code allowedHostsJson}参数
     * @param timeoutSeconds {@code timeoutSeconds}参数
     * @param memoryMb 记忆Mb参数
     * @param cpuMillis {@code cpuMillis}参数
     * @param pidsLimit 数量上限
     * @param maxOutputBytes {@code maxOutputBytes}参数
     * @param priority {@code priority}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_sandbox_job (
            id, task_id, run_id, step_id, tool_id, external_reply_id,
            tool_call_id, tool_name, trace_id, request_hash,
            template_key, argv_json, workspace_path, workspace_access,
            network_policy, allowed_hosts_json, timeout_seconds, memory_mb,
            cpu_millis, pids_limit, max_output_bytes, status, priority,
            created_at, updated_at
        ) VALUES (
            #{id}, #{taskId}, #{runId}, #{stepId}, #{toolId}, #{externalReplyId},
            #{toolCallId}, #{toolName}, #{traceId},
            #{requestHash}, #{templateKey}, CAST(#{argvJson} AS jsonb),
            #{workspacePath}, #{workspaceAccess}, #{networkPolicy},
            CAST(#{allowedHostsJson} AS jsonb), #{timeoutSeconds}, #{memoryMb},
            #{cpuMillis}, #{pidsLimit}, #{maxOutputBytes}, 'queued', #{priority},
            #{now}, #{now}
        )
        ON CONFLICT (trace_id) DO NOTHING
        """)
    int insertJob(
        @Param("id") Long id,
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("stepId") Long stepId,
        @Param("toolId") Long toolId,
        @Param("externalReplyId") String externalReplyId,
        @Param("toolCallId") String toolCallId,
        @Param("toolName") String toolName,
        @Param("traceId") String traceId,
        @Param("requestHash") String requestHash,
        @Param("templateKey") String templateKey,
        @Param("argvJson") String argvJson,
        @Param("workspacePath") String workspacePath,
        @Param("workspaceAccess") String workspaceAccess,
        @Param("networkPolicy") String networkPolicy,
        @Param("allowedHostsJson") String allowedHostsJson,
        @Param("timeoutSeconds") int timeoutSeconds,
        @Param("memoryMb") int memoryMb,
        @Param("cpuMillis") int cpuMillis,
        @Param("pidsLimit") int pidsLimit,
        @Param("maxOutputBytes") int maxOutputBytes,
        @Param("priority") int priority,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存作业WithManifest。
     *
     * @param id 资源标识
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param stepId 资源标识
     * @param toolId 资源标识
     * @param externalReplyId 资源标识
     * @param toolCallId 资源标识
     * @param toolName 名称
     * @param traceId 资源标识
     * @param requestHash {@code requestHash}参数
     * @param templateKey 模板Key参数
     * @param argvJson {@code argvJson}参数
     * @param workspacePath 工作空间Path参数
     * @param workspaceKey 工作空间Key参数
     * @param workspaceAccess 工作空间Access参数
     * @param networkPolicy network策略参数
     * @param allowedHostsJson {@code allowedHostsJson}参数
     * @param skillManifestJson 技能ManifestJson参数
     * @param skillManifestHash 技能ManifestHash参数
     * @param timeoutSeconds {@code timeoutSeconds}参数
     * @param memoryMb 记忆Mb参数
     * @param cpuMillis {@code cpuMillis}参数
     * @param pidsLimit 数量上限
     * @param maxOutputBytes {@code maxOutputBytes}参数
     * @param priority {@code priority}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_sandbox_job (
            id, task_id, run_id, step_id, tool_id, external_reply_id,
            tool_call_id, tool_name, trace_id, request_hash,
            template_key, argv_json, workspace_path, workspace_key, workspace_access,
            network_policy, allowed_hosts_json, skill_manifest_json, skill_manifest_hash,
            timeout_seconds, memory_mb, cpu_millis, pids_limit, max_output_bytes,
            status, priority, created_at, updated_at
        ) VALUES (
            #{id}, #{taskId}, #{runId}, #{stepId}, #{toolId}, #{externalReplyId},
            #{toolCallId}, #{toolName}, #{traceId}, #{requestHash}, #{templateKey},
            CAST(#{argvJson} AS jsonb), #{workspacePath}, #{workspaceKey}, #{workspaceAccess},
            #{networkPolicy}, CAST(#{allowedHostsJson} AS jsonb),
            CAST(#{skillManifestJson} AS jsonb), #{skillManifestHash},
            #{timeoutSeconds}, #{memoryMb}, #{cpuMillis}, #{pidsLimit}, #{maxOutputBytes},
            'queued', #{priority}, #{now}, #{now}
        )
        ON CONFLICT (trace_id) DO NOTHING
        """)
    int insertJobWithManifest(
        @Param("id") Long id,
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("stepId") Long stepId,
        @Param("toolId") Long toolId,
        @Param("externalReplyId") String externalReplyId,
        @Param("toolCallId") String toolCallId,
        @Param("toolName") String toolName,
        @Param("traceId") String traceId,
        @Param("requestHash") String requestHash,
        @Param("templateKey") String templateKey,
        @Param("argvJson") String argvJson,
        @Param("workspacePath") String workspacePath,
        @Param("workspaceKey") String workspaceKey,
        @Param("workspaceAccess") String workspaceAccess,
        @Param("networkPolicy") String networkPolicy,
        @Param("allowedHostsJson") String allowedHostsJson,
        @Param("skillManifestJson") String skillManifestJson,
        @Param("skillManifestHash") String skillManifestHash,
        @Param("timeoutSeconds") int timeoutSeconds,
        @Param("memoryMb") int memoryMb,
        @Param("cpuMillis") int cpuMillis,
        @Param("pidsLimit") int pidsLimit,
        @Param("maxOutputBytes") int maxOutputBytes,
        @Param("priority") int priority,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存对话Code作业。
     *
     * @param id 资源标识
     * @param ownerUserId 资源标识
     * @param conversationId 资源标识
     * @param traceId 资源标识
     * @param requestHash {@code requestHash}参数
     * @param templateKey 模板Key参数
     * @param scriptLanguage {@code scriptLanguage}参数
     * @param scriptText 待处理内容
     * @param argvJson {@code argvJson}参数
     * @param workspacePath 工作空间Path参数
     * @param timeoutSeconds {@code timeoutSeconds}参数
     * @param memoryMb 记忆Mb参数
     * @param cpuMillis {@code cpuMillis}参数
     * @param pidsLimit 数量上限
     * @param maxOutputBytes {@code maxOutputBytes}参数
     * @param priority {@code priority}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_sandbox_job (
            id, source_type, owner_user_id, conversation_id,
            task_id, run_id, step_id, tool_id,
            trace_id, request_hash, template_key, script_language, script_text,
            argv_json, workspace_path, workspace_access, network_policy,
            allowed_hosts_json, timeout_seconds, memory_mb, cpu_millis,
            pids_limit, max_output_bytes, status, priority, created_at, updated_at
        ) VALUES (
            #{id}, 'chat_code', #{ownerUserId}, #{conversationId},
            NULL, NULL, NULL, NULL,
            #{traceId}, #{requestHash}, #{templateKey}, #{scriptLanguage}, #{scriptText},
            CAST(#{argvJson} AS jsonb), #{workspacePath}, 'read_write', 'none',
            '[]'::jsonb, #{timeoutSeconds}, #{memoryMb}, #{cpuMillis},
            #{pidsLimit}, #{maxOutputBytes}, 'queued', #{priority}, #{now}, #{now}
        )
        ON CONFLICT (trace_id) DO NOTHING
        """)
    int insertChatCodeJob(
        @Param("id") Long id,
        @Param("ownerUserId") Long ownerUserId,
        @Param("conversationId") Long conversationId,
        @Param("traceId") String traceId,
        @Param("requestHash") String requestHash,
        @Param("templateKey") String templateKey,
        @Param("scriptLanguage") String scriptLanguage,
        @Param("scriptText") String scriptText,
        @Param("argvJson") String argvJson,
        @Param("workspacePath") String workspacePath,
        @Param("timeoutSeconds") int timeoutSeconds,
        @Param("memoryMb") int memoryMb,
        @Param("cpuMillis") int cpuMillis,
        @Param("pidsLimit") int pidsLimit,
        @Param("maxOutputBytes") int maxOutputBytes,
        @Param("priority") int priority,
        @Param("now") LocalDateTime now
    );

    /**
     * 创建并保存对话Code作业WithManifest。
     *
     * @param id 资源标识
     * @param ownerUserId 资源标识
     * @param conversationId 资源标识
     * @param traceId 资源标识
     * @param requestHash {@code requestHash}参数
     * @param templateKey 模板Key参数
     * @param scriptLanguage {@code scriptLanguage}参数
     * @param scriptText 待处理内容
     * @param argvJson {@code argvJson}参数
     * @param workspacePath 工作空间Path参数
     * @param workspaceKey 工作空间Key参数
     * @param skillManifestJson 技能ManifestJson参数
     * @param skillManifestHash 技能ManifestHash参数
     * @param timeoutSeconds {@code timeoutSeconds}参数
     * @param memoryMb 记忆Mb参数
     * @param cpuMillis {@code cpuMillis}参数
     * @param pidsLimit 数量上限
     * @param maxOutputBytes {@code maxOutputBytes}参数
     * @param priority {@code priority}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_sandbox_job (
            id, source_type, owner_user_id, conversation_id,
            task_id, run_id, step_id, tool_id,
            trace_id, request_hash, template_key, script_language, script_text,
            argv_json, workspace_path, workspace_key, workspace_access, network_policy,
            allowed_hosts_json, skill_manifest_json, skill_manifest_hash,
            timeout_seconds, memory_mb, cpu_millis, pids_limit,
            max_output_bytes, status, priority, created_at, updated_at
        ) VALUES (
            #{id}, 'chat_code', #{ownerUserId}, #{conversationId},
            NULL, NULL, NULL, NULL,
            #{traceId}, #{requestHash}, #{templateKey}, #{scriptLanguage}, #{scriptText},
            CAST(#{argvJson} AS jsonb), #{workspacePath}, #{workspaceKey}, 'read_write', 'none',
            '[]'::jsonb, CAST(#{skillManifestJson} AS jsonb), #{skillManifestHash},
            #{timeoutSeconds}, #{memoryMb}, #{cpuMillis}, #{pidsLimit},
            #{maxOutputBytes}, 'queued', #{priority}, #{now}, #{now}
        )
        ON CONFLICT (trace_id) DO NOTHING
        """)
    int insertChatCodeJobWithManifest(
        @Param("id") Long id,
        @Param("ownerUserId") Long ownerUserId,
        @Param("conversationId") Long conversationId,
        @Param("traceId") String traceId,
        @Param("requestHash") String requestHash,
        @Param("templateKey") String templateKey,
        @Param("scriptLanguage") String scriptLanguage,
        @Param("scriptText") String scriptText,
        @Param("argvJson") String argvJson,
        @Param("workspacePath") String workspacePath,
        @Param("workspaceKey") String workspaceKey,
        @Param("skillManifestJson") String skillManifestJson,
        @Param("skillManifestHash") String skillManifestHash,
        @Param("timeoutSeconds") int timeoutSeconds,
        @Param("memoryMb") int memoryMb,
        @Param("cpuMillis") int cpuMillis,
        @Param("pidsLimit") int pidsLimit,
        @Param("maxOutputBytes") int maxOutputBytes,
        @Param("priority") int priority,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code reserveOutputSequence}并返回对应结果。
     *
     * @param jobId 资源标识
     * @param runnerId 资源标识
     * @param jobTokenHash 作业令牌Hash参数
     * @param runnerSequenceNo 起始位置或序号
     * @param contentBytes 待处理内容
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Select("""
        UPDATE agent_sandbox_job job
        SET output_sequence = output_sequence + 1,
            output_bytes = output_bytes + #{contentBytes},
            updated_at = #{now}
        WHERE job.id = #{jobId}
          AND job.assigned_runner_id = #{runnerId}
          AND job.status IN ('leased', 'running')
          AND job.job_token_hash = #{jobTokenHash}
          AND job.lease_until >= #{now}
          AND job.token_consumed_at IS NULL
          AND job.output_bytes + #{contentBytes} <= job.max_output_bytes
          AND NOT EXISTS (
              SELECT 1 FROM agent_sandbox_job_output existing
              WHERE existing.job_id = job.id
                AND existing.attempt_no = job.attempt_no
                AND existing.runner_sequence_no = #{runnerSequenceNo}
          )
        RETURNING job.output_sequence
        """)
    Long reserveOutputSequence(
        @Param("jobId") Long jobId,
        @Param("runnerId") Long runnerId,
        @Param("jobTokenHash") String jobTokenHash,
        @Param("runnerSequenceNo") long runnerSequenceNo,
        @Param("contentBytes") int contentBytes,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取{@code OutputByRunnerSequence}。
     *
     * @param jobId 资源标识
     * @param attemptNo {@code attemptNo}参数
     * @param runnerSequenceNo 起始位置或序号
     * @return 处理结果
     */
    @Select("""
        SELECT id, job_id, attempt_no, sequence_no, runner_sequence_no,
               stream, content, content_bytes, created_at
        FROM agent_sandbox_job_output
        WHERE job_id = #{jobId} AND attempt_no = #{attemptNo}
          AND runner_sequence_no = #{runnerSequenceNo}
        """)
    SandboxJobOutputRow selectOutputByRunnerSequence(
        @Param("jobId") Long jobId,
        @Param("attemptNo") int attemptNo,
        @Param("runnerSequenceNo") long runnerSequenceNo
    );

    /**
     * 创建并保存{@code Output}。
     *
     * @param id 资源标识
     * @param jobId 资源标识
     * @param attemptNo {@code attemptNo}参数
     * @param sequenceNo 起始位置或序号
     * @param runnerSequenceNo 起始位置或序号
     * @param stream {@code stream}参数
     * @param content 待处理内容
     * @param contentBytes 待处理内容
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_sandbox_job_output (
            id, job_id, attempt_no, sequence_no, runner_sequence_no,
            stream, content, content_bytes, created_at
        ) VALUES (
            #{id}, #{jobId}, #{attemptNo}, #{sequenceNo}, #{runnerSequenceNo},
            #{stream}, #{content}, #{contentBytes}, #{now}
        )
        """)
    int insertOutput(
        @Param("id") Long id,
        @Param("jobId") Long jobId,
        @Param("attemptNo") int attemptNo,
        @Param("sequenceNo") long sequenceNo,
        @Param("runnerSequenceNo") long runnerSequenceNo,
        @Param("stream") String stream,
        @Param("content") String content,
        @Param("contentBytes") int contentBytes,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code markOutputTruncated}并返回对应结果。
     *
     * @param jobId 资源标识
     * @param runnerId 资源标识
     * @param jobTokenHash 作业令牌Hash参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_sandbox_job
        SET output_truncated = TRUE, updated_at = #{now}
        WHERE id = #{jobId} AND assigned_runner_id = #{runnerId}
          AND status IN ('leased', 'running') AND job_token_hash = #{jobTokenHash}
          AND lease_until >= #{now} AND token_consumed_at IS NULL
        """)
    int markOutputTruncated(
        @Param("jobId") Long jobId,
        @Param("runnerId") Long runnerId,
        @Param("jobTokenHash") String jobTokenHash,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取{@code Outputs}。
     *
     * @param jobId 资源标识
     * @param afterSequence 起始位置或序号
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, job_id, attempt_no, sequence_no, runner_sequence_no,
               stream, content, content_bytes, created_at
        FROM agent_sandbox_job_output
        WHERE job_id = #{jobId} AND sequence_no > #{afterSequence}
        ORDER BY sequence_no
        LIMIT #{limit}
        """)
    List<SandboxJobOutputRow> selectOutputs(
        @Param("jobId") Long jobId,
        @Param("afterSequence") long afterSequence,
        @Param("limit") int limit
    );

    /**
     * 判断celOwned对话作业是否满足要求。
     *
     * @param jobId 资源标识
     * @param ownerUserId 资源标识
     * @param conversationId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_sandbox_job
        SET status = 'cancelled', finished_at = #{now}, token_consumed_at = #{now},
            lease_until = NULL, failure_code = 'USER_CANCELLED',
            failure_message = 'sandbox execution cancelled by its owner', updated_at = #{now}
        WHERE id = #{jobId} AND source_type = 'chat_code'
          AND owner_user_id = #{ownerUserId} AND conversation_id = #{conversationId}
          AND status IN ('queued', 'leased', 'running')
        """)
    int cancelOwnedChatJob(
        @Param("jobId") Long jobId,
        @Param("ownerUserId") Long ownerUserId,
        @Param("conversationId") Long conversationId,
        @Param("now") LocalDateTime now
    );

    /**
     * 判断celOwned对话Jobs是否满足要求。
     *
     * @param ownerUserId 资源标识
     * @param conversationId 资源标识
     * @param reason {@code reason}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_sandbox_job
        SET status = 'cancelled', finished_at = #{now}, token_consumed_at = #{now},
            lease_until = NULL, failure_code = 'USER_CANCELLED',
            failure_message = #{reason}, updated_at = #{now}
        WHERE source_type = 'chat_code'
          AND owner_user_id = #{ownerUserId} AND conversation_id = #{conversationId}
          AND status IN ('queued', 'leased', 'running')
        """)
    int cancelOwnedChatJobs(
        @Param("ownerUserId") Long ownerUserId,
        @Param("conversationId") Long conversationId,
        @Param("reason") String reason,
        @Param("now") LocalDateTime now
    );

    /**
     * 判断cel任务RunJobs是否满足要求。
     *
     * @param runIds 资源标识集合
     * @param reason {@code reason}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        <script>
        UPDATE agent_sandbox_job
        SET status = 'cancelled', finished_at = #{now}, token_consumed_at = #{now},
            lease_until = NULL, failure_code = 'USER_CANCELLED',
            failure_message = #{reason}, updated_at = #{now}
        WHERE source_type = 'task_tool'
          AND run_id IN
          <foreach collection="runIds" item="runId" open="(" separator="," close=")">
            #{runId}
          </foreach>
          AND status IN ('queued', 'leased', 'running')
        </script>
        """)
    int cancelTaskRunJobs(
        @Param("runIds") List<Long> runIds,
        @Param("reason") String reason,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code markExternalBatchResumeDispatched}并返回对应结果。
     *
     * @param runId 资源标识
     * @param replyId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_sandbox_job batch
        SET resume_dispatched_at = #{now}, updated_at = #{now}
        WHERE batch.run_id = #{runId}
          AND batch.external_reply_id = #{replyId}
          AND batch.resume_dispatched_at IS NULL
          AND batch.status IN ('succeeded', 'failed', 'cancelled', 'expired')
          AND NOT EXISTS (
              SELECT 1 FROM agent_sandbox_job pending
              WHERE pending.run_id = #{runId}
                AND pending.external_reply_id = #{replyId}
                AND pending.status NOT IN ('succeeded', 'failed', 'cancelled', 'expired')
          )
          AND NOT EXISTS (
              SELECT 1 FROM agent_sandbox_job dispatched
              WHERE dispatched.run_id = #{runId}
                AND dispatched.external_reply_id = #{replyId}
                AND dispatched.resume_dispatched_at IS NOT NULL
          )
        """)
    int markExternalBatchResumeDispatched(
        @Param("runId") Long runId,
        @Param("replyId") String replyId,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取{@code ExternalBatch}。
     *
     * @param runId 资源标识
     * @param replyId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_id, run_id, step_id, tool_id, external_reply_id,
               tool_call_id, tool_name, trace_id, request_hash, template_key,
               argv_json::text AS argv_json, workspace_path, workspace_key, workspace_access,
               network_policy, allowed_hosts_json::text AS allowed_hosts_json,
               timeout_seconds, memory_mb, cpu_millis, pids_limit,
               max_output_bytes, status, priority, assigned_runner_id,
               skill_manifest_json::text AS skill_manifest_json, skill_manifest_hash,
               lease_until, attempt_no, started_at, finished_at,
               resume_dispatched_at, exit_code, stdout_text, stderr_text,
               output_manifest_json::text AS output_manifest_json,
               resource_usage_json::text AS resource_usage_json,
               failure_code, failure_message, created_at, updated_at
        FROM agent_sandbox_job
        WHERE run_id = #{runId} AND external_reply_id = #{replyId}
        ORDER BY id
        """)
    java.util.List<SandboxJobRow> selectExternalBatch(
        @Param("runId") Long runId,
        @Param("replyId") String replyId
    );
}
