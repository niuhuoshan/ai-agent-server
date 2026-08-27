package group.aitools.nhs.platform.operations.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.operations.domain.LogMaintenanceRun;
import group.aitools.nhs.platform.operations.domain.LogRetentionPolicy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取策略。
 *
 * 定义{@code LogMaintenance}相关的数据访问契约。
 * Persistence boundary for retention policy and auditable maintenance runs. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface LogMaintenanceMapper {

    @Select("""
        SELECT id, retention_days, revision_no, updated_by, updated_at, change_reason
        FROM agent_log_retention_policy
        WHERE id = 1
        """)
    LogRetentionPolicy selectPolicy();

    /**
     * 更新策略。
     *
     * @param retentionDays {@code retentionDays}参数
     * @param expectedRevision {@code expectedRevision}参数
     * @param updatedBy {@code updatedBy}参数
     * @param updatedAt {@code updatedAt}参数
     * @param changeReason {@code changeReason}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_log_retention_policy
        SET retention_days = #{retentionDays}, revision_no = revision_no + 1,
            updated_by = #{updatedBy}, updated_at = #{updatedAt}, change_reason = #{changeReason}
        WHERE id = 1 AND revision_no = #{expectedRevision}
        """)
    int updatePolicy(
        @Param("retentionDays") int retentionDays,
        @Param("expectedRevision") int expectedRevision,
        @Param("updatedBy") Long updatedBy,
        @Param("updatedAt") LocalDateTime updatedAt,
        @Param("changeReason") String changeReason
    );

    /**
     * 创建并保存{@code Run}。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_log_maintenance_run (
            id, trigger_type, status, retention_days, policy_revision, cutoff_at,
            confirmation_token_hash, confirmation_expires_at, requested_by,
            confirmed_at, started_at, finished_at, summary_json,
            error_code, error_message, created_at, updated_at
        ) VALUES (
            #{id}, #{triggerType}, #{status}, #{retentionDays}, #{policyRevision}, #{cutoffAt},
            #{confirmationTokenHash}, #{confirmationExpiresAt}, #{requestedBy},
            #{confirmedAt}, #{startedAt}, #{finishedAt}, CAST(#{summaryJson} AS jsonb),
            #{errorCode}, #{errorMessage}, #{createdAt}, #{updatedAt}
        )
        """)
    int insertRun(LogMaintenanceRun run);

    /**
     * 获取RunBy令牌Hash。
     *
     * @param tokenHash 令牌Hash参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, trigger_type, status, retention_days, policy_revision, cutoff_at,
               confirmation_token_hash, confirmation_expires_at, requested_by,
               confirmed_at, started_at, finished_at, CAST(summary_json AS text) AS summary_json,
               error_code, error_message, created_at, updated_at
        FROM agent_log_maintenance_run
        WHERE confirmation_token_hash = #{tokenHash}
        ORDER BY created_at DESC
        LIMIT 1
        """)
    LogMaintenanceRun selectRunByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * 处理{@code claimManualRun}并返回对应结果。
     *
     * @param runId 资源标识
     * @param policyRevision 策略Revision参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_log_maintenance_run
        SET status = 'running', confirmed_at = #{now}, started_at = #{now}, updated_at = #{now}
        WHERE id = #{runId}
          AND status = 'previewed'
          AND confirmation_expires_at > #{now}
          AND policy_revision = #{policyRevision}
        """)
    int claimManualRun(
        @Param("runId") Long runId,
        @Param("policyRevision") int policyRevision,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code expirePreview}并返回对应结果。
     *
     * @param runId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_log_maintenance_run
        SET status = 'expired', updated_at = #{now}
        WHERE id = #{runId} AND status = 'previewed'
        """)
    int expirePreview(@Param("runId") Long runId, @Param("now") LocalDateTime now);

    /**
     * 处理{@code finishRun}并返回对应结果。
     *
     * @param runId 资源标识
     * @param status 目标状态
     * @param summaryJson {@code summaryJson}参数
     * @param errorCode {@code errorCode}参数
     * @param errorMessage 待处理内容
     * @param finishedAt {@code finishedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_log_maintenance_run
        SET status = #{status}, finished_at = #{finishedAt}, updated_at = #{finishedAt},
            summary_json = CAST(#{summaryJson} AS jsonb), error_code = #{errorCode},
            error_message = #{errorMessage}
        WHERE id = #{runId} AND status = 'running'
        """)
    int finishRun(
        @Param("runId") Long runId,
        @Param("status") String status,
        @Param("summaryJson") String summaryJson,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("finishedAt") LocalDateTime finishedAt
    );

    /**
     * 处理markCompletedRun审计Failure并返回对应结果。
     *
     * @param runId 资源标识
     * @param errorCode {@code errorCode}参数
     * @param errorMessage 待处理内容
     * @param updatedAt {@code updatedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_log_maintenance_run
        SET status = 'failed', error_code = #{errorCode}, error_message = #{errorMessage},
            updated_at = #{updatedAt}
        WHERE id = #{runId} AND status IN ('succeeded', 'partial')
        """)
    int markCompletedRunAuditFailure(
        @Param("runId") Long runId,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("updatedAt") LocalDateTime updatedAt
    );

    /**
     * 获取{@code RecentRuns}。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, trigger_type, status, retention_days, policy_revision, cutoff_at,
               confirmation_token_hash, confirmation_expires_at, requested_by,
               confirmed_at, started_at, finished_at, CAST(summary_json AS text) AS summary_json,
               error_code, error_message, created_at, updated_at
        FROM agent_log_maintenance_run
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<LogMaintenanceRun> selectRecentRuns(@Param("limit") int limit);
}
