package group.aitools.nhs.platform.data.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataProfileJob;
import group.aitools.nhs.platform.data.domain.AgentDataProfileJobTable;
import group.aitools.nhs.platform.data.domain.AgentDataProfileRelationRecommendation;
import group.aitools.nhs.platform.data.domain.AgentDataSmartImportItem;
import group.aitools.nhs.platform.data.domain.AgentDataSmartImportPreview;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.domain.AgentDataTableProfile;

import java.time.LocalDateTime;
import java.util.List;

/** Persistence boundary for durable metadata profiling and smart import. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface DataProfileMapper {

    @Select("""
        SELECT COUNT(*)
        FROM agent_data_profile_job
        WHERE dataset_id = #{datasetId} AND status IN ('queued', 'running')
        """)
    int countActiveJobs(@Param("datasetId") Long datasetId);

    @Insert("""
        INSERT INTO agent_data_profile_job (
            id, dataset_id, data_source_id, mode, status, requested_table_ids_json,
            dataset_revision, data_source_revision, total_tables, completed_tables,
            failed_tables, progress_percent, current_table_id, cancel_requested,
            resume_of_job_id, attempt_no, max_attempts, revision_no, error_message,
            requested_by, created_at, started_at, finished_at, updated_at
        ) VALUES (
            #{id}, #{datasetId}, #{dataSourceId}, #{mode}, #{status},
            CAST(#{requestedTableIdsJson} AS jsonb), #{datasetRevision}, #{dataSourceRevision},
            #{totalTables}, #{completedTables}, #{failedTables}, #{progressPercent},
            #{currentTableId}, #{cancelRequested}, #{resumeOfJobId}, #{attemptNo},
            #{maxAttempts}, #{revisionNo}, #{errorMessage}, #{requestedBy}, #{createdAt},
            #{startedAt}, #{finishedAt}, #{updatedAt}
        )
        """)
    int insertJob(AgentDataProfileJob job);

    @Insert("""
        INSERT INTO agent_data_profile_job_table (
            id, job_id, dataset_id, table_id, sequence_no, source_hash,
            status, attempt_no, profile_id, error_message,
            started_at, finished_at, updated_at
        ) VALUES (
            #{id}, #{jobId}, #{datasetId}, #{tableId}, #{sequenceNo}, #{sourceHash},
            #{status}, #{attemptNo}, #{profileId}, #{errorMessage},
            #{startedAt}, #{finishedAt}, #{updatedAt}
        )
        """)
    int insertJobTable(AgentDataProfileJobTable item);

    @Select("""
        SELECT id, dataset_id, data_source_id, mode, status,
               requested_table_ids_json::text AS requested_table_ids_json,
               dataset_revision, data_source_revision, total_tables, completed_tables,
               failed_tables, progress_percent, current_table_id, cancel_requested,
               resume_of_job_id, worker_id, lease_until, attempt_no, max_attempts,
               revision_no, error_message, requested_by, created_at, started_at,
               finished_at, updated_at
        FROM agent_data_profile_job
        WHERE dataset_id = #{datasetId}
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentDataProfileJob> selectJobs(
        @Param("datasetId") Long datasetId,
        @Param("limit") int limit
    );

    @Select("""
        SELECT id, dataset_id, data_source_id, mode, status,
               requested_table_ids_json::text AS requested_table_ids_json,
               dataset_revision, data_source_revision, total_tables, completed_tables,
               failed_tables, progress_percent, current_table_id, cancel_requested,
               resume_of_job_id, worker_id, lease_until, attempt_no, max_attempts,
               revision_no, error_message, requested_by, created_at, started_at,
               finished_at, updated_at
        FROM agent_data_profile_job
        WHERE id = #{jobId} AND dataset_id = #{datasetId}
        """)
    AgentDataProfileJob selectJob(
        @Param("datasetId") Long datasetId,
        @Param("jobId") Long jobId
    );

    @Select("""
        SELECT id, dataset_id, data_source_id, mode, status,
               requested_table_ids_json::text AS requested_table_ids_json,
               dataset_revision, data_source_revision, total_tables, completed_tables,
               failed_tables, progress_percent, current_table_id, cancel_requested,
               resume_of_job_id, worker_id, lease_until, attempt_no, max_attempts,
               revision_no, error_message, requested_by, created_at, started_at,
               finished_at, updated_at
        FROM agent_data_profile_job
        WHERE id = #{jobId} AND dataset_id = #{datasetId}
        FOR UPDATE
        """)
    AgentDataProfileJob selectJobForUpdate(
        @Param("datasetId") Long datasetId,
        @Param("jobId") Long jobId
    );

    @Select("""
        SELECT id, job_id, dataset_id, table_id, sequence_no, source_hash,
               status, attempt_no, profile_id, error_message,
               started_at, finished_at, updated_at
        FROM agent_data_profile_job_table
        WHERE job_id = #{jobId} AND dataset_id = #{datasetId}
        ORDER BY sequence_no, id
        """)
    List<AgentDataProfileJobTable> selectJobTables(
        @Param("datasetId") Long datasetId,
        @Param("jobId") Long jobId
    );

    @Select("""
        SELECT DISTINCT ON (jt.table_id)
               jt.id, jt.job_id, jt.dataset_id, jt.table_id, jt.sequence_no,
               jt.source_hash, jt.status, jt.attempt_no, jt.profile_id,
               jt.error_message, jt.started_at, jt.finished_at, jt.updated_at
        FROM agent_data_profile_job_table jt
        JOIN agent_data_profile_job j ON j.id = jt.job_id
        WHERE jt.dataset_id = #{datasetId}
        ORDER BY jt.table_id, j.created_at DESC, j.id DESC
        """)
    List<AgentDataProfileJobTable> selectLatestJobTables(@Param("datasetId") Long datasetId);

    @Select("""
        SELECT table_id
        FROM agent_data_profile_job_table
        WHERE job_id = #{jobId} AND dataset_id = #{datasetId}
          AND status IN ('pending', 'failed')
        ORDER BY sequence_no, id
        """)
    List<Long> selectResumableTableIds(
        @Param("datasetId") Long datasetId,
        @Param("jobId") Long jobId
    );

    @Update("""
        UPDATE agent_data_profile_job
        SET status = 'cancelled', cancel_requested = TRUE,
            error_message = '用户在任务开始前取消', finished_at = #{now},
            progress_percent = CASE WHEN total_tables = 0 THEN 100 ELSE progress_percent END,
            revision_no = revision_no + 1, updated_at = #{now}
        WHERE id = #{jobId} AND dataset_id = #{datasetId}
          AND revision_no = #{revisionNo} AND status = 'queued'
        """)
    int cancelQueuedJob(
        @Param("datasetId") Long datasetId,
        @Param("jobId") Long jobId,
        @Param("revisionNo") Integer revisionNo,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_profile_job
        SET cancel_requested = TRUE, revision_no = revision_no + 1, updated_at = #{now}
        WHERE id = #{jobId} AND dataset_id = #{datasetId}
          AND revision_no = #{revisionNo} AND status = 'running'
          AND cancel_requested = FALSE
        """)
    int requestRunningCancel(
        @Param("datasetId") Long datasetId,
        @Param("jobId") Long jobId,
        @Param("revisionNo") Integer revisionNo,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_profile_job_table jt
        SET status = 'pending', error_message = NULL, started_at = NULL,
            finished_at = NULL, updated_at = CURRENT_TIMESTAMP
        WHERE jt.status = 'running'
          AND EXISTS (
              SELECT 1 FROM agent_data_profile_job j
              WHERE j.id = jt.job_id AND j.status = 'running'
                AND j.cancel_requested = TRUE
                AND (j.lease_until IS NULL OR j.lease_until < CURRENT_TIMESTAMP)
          )
        """)
    int resetExpiredCancelledJobTables();

    @Update("""
        WITH counts AS (
            SELECT j.id,
                   COUNT(jt.id) FILTER (WHERE jt.status = 'succeeded')::INTEGER AS completed,
                   COUNT(jt.id) FILTER (WHERE jt.status = 'failed')::INTEGER AS failed
            FROM agent_data_profile_job j
            LEFT JOIN agent_data_profile_job_table jt ON jt.job_id = j.id
            WHERE j.status = 'running' AND j.cancel_requested = TRUE
              AND (j.lease_until IS NULL OR j.lease_until < CURRENT_TIMESTAMP)
            GROUP BY j.id
        )
        UPDATE agent_data_profile_job j
        SET status = 'cancelled', completed_tables = counts.completed,
            failed_tables = counts.failed,
            progress_percent = CASE
                WHEN j.total_tables = 0 THEN 100
                ELSE ROUND(((counts.completed + counts.failed) * 100.0 / j.total_tables)::numeric, 2)
            END,
            error_message = '用户主动中断画像任务', current_table_id = NULL,
            worker_id = NULL, lease_until = NULL, finished_at = CURRENT_TIMESTAMP,
            revision_no = revision_no + 1, updated_at = CURRENT_TIMESTAMP
        FROM counts
        WHERE j.id = counts.id
        """)
    int finishExpiredCancelledJobs();

    @Update("""
        UPDATE agent_data_profile_job
        SET status = 'failed', error_message = '工作节点多次失联，任务已停止',
            worker_id = NULL, lease_until = NULL, current_table_id = NULL,
            completed_tables = (
                SELECT COUNT(*)::INTEGER FROM agent_data_profile_job_table jt
                WHERE jt.job_id = agent_data_profile_job.id AND jt.status = 'succeeded'
            ),
            failed_tables = (
                SELECT COUNT(*)::INTEGER FROM agent_data_profile_job_table jt
                WHERE jt.job_id = agent_data_profile_job.id AND jt.status = 'failed'
            ),
            progress_percent = CASE WHEN total_tables = 0 THEN 100 ELSE ROUND((
                SELECT COUNT(*) * 100.0 / total_tables
                FROM agent_data_profile_job_table jt
                WHERE jt.job_id = agent_data_profile_job.id
                  AND jt.status IN ('succeeded', 'failed')
            )::numeric, 2) END,
            finished_at = CURRENT_TIMESTAMP, revision_no = revision_no + 1,
            updated_at = CURRENT_TIMESTAMP
        WHERE status = 'running' AND lease_until < CURRENT_TIMESTAMP
          AND attempt_no >= max_attempts
        """)
    int failExhaustedJobs();

    @Update("""
        UPDATE agent_data_profile_job_table jt
        SET status = 'failed', error_message = '工作节点多次失联，表画像未完成',
            finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
        WHERE jt.status = 'running'
          AND EXISTS (
              SELECT 1 FROM agent_data_profile_job j
              WHERE j.id = jt.job_id AND j.status = 'running'
                AND j.lease_until < CURRENT_TIMESTAMP AND j.attempt_no >= j.max_attempts
          )
        """)
    int failExhaustedJobTables();

    @Select("""
        WITH candidate AS (
            SELECT id, status AS previous_status
            FROM agent_data_profile_job
            WHERE cancel_requested = FALSE
              AND attempt_no < max_attempts
              AND (
                  status = 'queued'
                  OR (status = 'running' AND lease_until < CURRENT_TIMESTAMP)
              )
            ORDER BY created_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT 1
        )
        UPDATE agent_data_profile_job j
        SET status = 'running', attempt_no = attempt_no + 1,
            worker_id = #{workerId},
            lease_until = CURRENT_TIMESTAMP + INTERVAL '10 minutes',
            started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
            revision_no = revision_no + 1, updated_at = CURRENT_TIMESTAMP
        FROM candidate c
        WHERE j.id = c.id
        RETURNING j.id, j.dataset_id, j.data_source_id, j.mode, j.status,
                  j.requested_table_ids_json::text AS requested_table_ids_json,
                  j.dataset_revision, j.data_source_revision, j.total_tables,
                  j.completed_tables, j.failed_tables, j.progress_percent,
                  j.current_table_id, j.cancel_requested, j.resume_of_job_id,
                  j.worker_id, j.lease_until, j.attempt_no, j.max_attempts,
                  j.revision_no, j.error_message, j.requested_by, j.created_at,
                  j.started_at, j.finished_at, j.updated_at,
                  (c.previous_status = 'running') AS recovered
        """)
    AgentDataProfileJob claimJob(@Param("workerId") String workerId);

    @Update("""
        UPDATE agent_data_profile_job_table
        SET status = 'pending', error_message = NULL, started_at = NULL,
            finished_at = NULL, updated_at = CURRENT_TIMESTAMP
        WHERE job_id = #{jobId} AND status = 'running'
        """)
    int recoverRunningJobTables(@Param("jobId") Long jobId);

    @Update("""
        UPDATE agent_data_profile_job
        SET lease_until = CURRENT_TIMESTAMP + INTERVAL '10 minutes',
            updated_at = CURRENT_TIMESTAMP
        WHERE id = #{jobId} AND status = 'running' AND worker_id = #{workerId}
          AND lease_until > CURRENT_TIMESTAMP
        """)
    int renewJobLease(
        @Param("jobId") Long jobId,
        @Param("workerId") String workerId
    );

    @Select("""
        SELECT id, dataset_id, data_source_id, mode, status,
               requested_table_ids_json::text AS requested_table_ids_json,
               dataset_revision, data_source_revision, total_tables, completed_tables,
               failed_tables, progress_percent, current_table_id, cancel_requested,
               resume_of_job_id, worker_id, lease_until, attempt_no, max_attempts,
               revision_no, error_message, requested_by, created_at, started_at,
               finished_at, updated_at
        FROM agent_data_profile_job
        WHERE id = #{jobId}
        """)
    AgentDataProfileJob selectClaimedJob(@Param("jobId") Long jobId);

    @Select("""
        WITH candidate AS (
            SELECT jt.id
            FROM agent_data_profile_job_table jt
            JOIN agent_data_profile_job j ON j.id = jt.job_id
            WHERE jt.job_id = #{jobId} AND jt.status = 'pending'
              AND j.status = 'running' AND j.worker_id = #{workerId}
              AND j.cancel_requested = FALSE AND j.lease_until > CURRENT_TIMESTAMP
            ORDER BY jt.sequence_no, jt.id
            FOR UPDATE OF jt SKIP LOCKED
            LIMIT 1
        )
        UPDATE agent_data_profile_job_table jt
        SET status = 'running', attempt_no = attempt_no + 1,
            started_at = CURRENT_TIMESTAMP, finished_at = NULL,
            error_message = NULL, updated_at = CURRENT_TIMESTAMP
        FROM candidate c
        WHERE jt.id = c.id
        RETURNING jt.id, jt.job_id, jt.dataset_id, jt.table_id, jt.sequence_no,
                  jt.source_hash, jt.status, jt.attempt_no, jt.profile_id,
                  jt.error_message, jt.started_at, jt.finished_at, jt.updated_at
        """)
    AgentDataProfileJobTable claimNextJobTable(
        @Param("jobId") Long jobId,
        @Param("workerId") String workerId
    );

    @Update("""
        UPDATE agent_data_profile_job
        SET current_table_id = #{tableId}, updated_at = CURRENT_TIMESTAMP
        WHERE id = #{jobId} AND status = 'running' AND worker_id = #{workerId}
          AND lease_until > CURRENT_TIMESTAMP
        """)
    int setCurrentTable(
        @Param("jobId") Long jobId,
        @Param("workerId") String workerId,
        @Param("tableId") Long tableId
    );

    @Update("""
        UPDATE agent_data_profile_job_table jt
        SET status = 'pending', started_at = NULL, finished_at = NULL,
            error_message = NULL, updated_at = CURRENT_TIMESTAMP
        WHERE jt.id = #{itemId} AND jt.job_id = #{jobId} AND jt.status = 'running'
          AND EXISTS (
              SELECT 1 FROM agent_data_profile_job j
              WHERE j.id = jt.job_id AND j.status = 'running'
                AND j.worker_id = #{workerId} AND j.cancel_requested = TRUE
                AND j.lease_until > CURRENT_TIMESTAMP
          )
        """)
    int resetJobTable(
        @Param("jobId") Long jobId,
        @Param("itemId") Long itemId,
        @Param("workerId") String workerId
    );

    @Insert("""
        INSERT INTO agent_data_table_profile (
            id, dataset_id, table_id, job_id, source_hash, table_type, term, description, ddl_text,
            row_count_estimate, column_count, columns_profile_json, sample_data_json,
            sample_row_count, sample_redacted, confidence_score, confidence_reason,
            tags_json, temporary_classification, ignored, ignore_decision, profile_json,
            revision_no, created_by, created_at, updated_by, updated_at
        ) VALUES (
            #{id}, #{datasetId}, #{tableId}, #{jobId}, #{sourceHash}, #{tableType},
            #{term}, #{description}, #{ddlText}, #{rowCountEstimate}, #{columnCount},
            CAST(#{columnsProfileJson} AS jsonb), CAST(#{sampleDataJson} AS jsonb),
            #{sampleRowCount}, #{sampleRedacted}, #{confidenceScore}, #{confidenceReason},
            CAST(#{tagsJson} AS jsonb), #{temporaryClassification}, #{ignored},
            #{ignoreDecision}, CAST(#{profileJson} AS jsonb), #{revisionNo},
            #{createdBy}, #{createdAt}, #{updatedBy}, #{updatedAt}
        )
        """)
    int insertProfile(AgentDataTableProfile profile);

    @Update("""
        UPDATE agent_data_profile_job_table jt
        SET status = 'succeeded', profile_id = #{profileId}, error_message = NULL,
            finished_at = #{now}, updated_at = #{now}
        WHERE jt.id = #{itemId} AND jt.job_id = #{jobId} AND jt.status = 'running'
          AND EXISTS (
              SELECT 1 FROM agent_data_profile_job j
              WHERE j.id = jt.job_id AND j.status = 'running'
                AND j.worker_id = #{workerId} AND j.lease_until > CURRENT_TIMESTAMP
          )
        """)
    int completeJobTable(
        @Param("jobId") Long jobId,
        @Param("itemId") Long itemId,
        @Param("profileId") Long profileId,
        @Param("workerId") String workerId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_profile_job_table jt
        SET status = 'failed', error_message = #{error}, finished_at = #{now}, updated_at = #{now}
        WHERE jt.id = #{itemId} AND jt.job_id = #{jobId} AND jt.status = 'running'
          AND EXISTS (
              SELECT 1 FROM agent_data_profile_job j
              WHERE j.id = jt.job_id AND j.status = 'running'
                AND j.worker_id = #{workerId} AND j.lease_until > CURRENT_TIMESTAMP
          )
        """)
    int failJobTable(
        @Param("jobId") Long jobId,
        @Param("itemId") Long itemId,
        @Param("workerId") String workerId,
        @Param("error") String error,
        @Param("now") LocalDateTime now
    );

    @Update("""
        WITH counts AS (
            SELECT COUNT(*) FILTER (WHERE status = 'succeeded')::INTEGER AS completed,
                   COUNT(*) FILTER (WHERE status = 'failed')::INTEGER AS failed
            FROM agent_data_profile_job_table
            WHERE job_id = #{jobId}
        )
        UPDATE agent_data_profile_job j
        SET completed_tables = counts.completed,
            failed_tables = counts.failed,
            progress_percent = CASE
                WHEN j.total_tables = 0 THEN 100
                ELSE ROUND(((counts.completed + counts.failed) * 100.0 / j.total_tables)::numeric, 2)
            END,
            current_table_id = NULL,
            revision_no = revision_no + 1,
            updated_at = CURRENT_TIMESTAMP
        FROM counts
        WHERE j.id = #{jobId} AND j.status = 'running' AND j.worker_id = #{workerId}
          AND j.lease_until > CURRENT_TIMESTAMP
        """)
    int refreshJobProgress(
        @Param("jobId") Long jobId,
        @Param("workerId") String workerId
    );

    @Update("""
        WITH counts AS (
            SELECT COUNT(*) FILTER (WHERE status = 'succeeded')::INTEGER AS completed,
                   COUNT(*) FILTER (WHERE status = 'failed')::INTEGER AS failed,
                   COUNT(*) FILTER (WHERE status IN ('pending', 'running'))::INTEGER AS unfinished
            FROM agent_data_profile_job_table
            WHERE job_id = #{jobId}
        )
        UPDATE agent_data_profile_job j
        SET status = CASE
                WHEN j.cancel_requested THEN 'cancelled'
                WHEN counts.unfinished > 0 THEN 'failed'
                WHEN counts.failed > 0 THEN 'failed'
                ELSE 'succeeded'
            END,
            completed_tables = counts.completed,
            failed_tables = counts.failed,
            progress_percent = CASE
                WHEN j.cancel_requested AND j.total_tables > 0
                    THEN ROUND(((counts.completed + counts.failed) * 100.0 / j.total_tables)::numeric, 2)
                ELSE 100
            END,
            error_message = CASE
                WHEN j.cancel_requested THEN '用户主动中断画像任务'
                WHEN counts.unfinished > 0 THEN '画像任务未完整执行，可续跑'
                WHEN counts.failed > 0 THEN '部分数据表画像失败，可续跑失败表'
                ELSE NULL
            END,
            current_table_id = NULL, worker_id = NULL, lease_until = NULL,
            finished_at = #{now}, revision_no = revision_no + 1, updated_at = #{now}
        FROM counts
        WHERE j.id = #{jobId} AND j.status = 'running' AND j.worker_id = #{workerId}
          AND j.lease_until > CURRENT_TIMESTAMP
        """)
    int finishJob(
        @Param("jobId") Long jobId,
        @Param("workerId") String workerId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_profile_job
        SET status = 'failed', error_message = #{error}, current_table_id = NULL,
            worker_id = NULL, lease_until = NULL, finished_at = #{now},
            revision_no = revision_no + 1, updated_at = #{now}
        WHERE id = #{jobId} AND status = 'running' AND worker_id = #{workerId}
          AND lease_until > CURRENT_TIMESTAMP
        """)
    int failJob(
        @Param("jobId") Long jobId,
        @Param("workerId") String workerId,
        @Param("error") String error,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_profile_job_table jt
        SET status = 'failed', error_message = #{error},
            finished_at = #{now}, updated_at = #{now}
        WHERE jt.job_id = #{jobId} AND jt.status = 'running'
          AND EXISTS (
              SELECT 1 FROM agent_data_profile_job j
              WHERE j.id = jt.job_id AND j.status = 'running'
                AND j.worker_id = #{workerId} AND j.lease_until > CURRENT_TIMESTAMP
          )
        """)
    int failRunningJobTables(
        @Param("jobId") Long jobId,
        @Param("workerId") String workerId,
        @Param("error") String error,
        @Param("now") LocalDateTime now
    );

    @Select("""
        SELECT DISTINCT ON (table_id)
               id, dataset_id, table_id, job_id, source_hash, table_type, term, description, ddl_text,
               row_count_estimate, column_count,
               columns_profile_json::text AS columns_profile_json,
               sample_data_json::text AS sample_data_json,
               sample_row_count, sample_redacted, confidence_score, confidence_reason,
               tags_json::text AS tags_json, temporary_classification, ignored,
               ignore_decision, profile_json::text AS profile_json, revision_no,
               created_by, created_at, updated_by, updated_at
        FROM agent_data_table_profile
        WHERE dataset_id = #{datasetId}
        ORDER BY table_id, created_at DESC, id DESC
        """)
    List<AgentDataTableProfile> selectLatestProfiles(@Param("datasetId") Long datasetId);

    @Select("""
        SELECT id, dataset_id, table_id, job_id, source_hash, table_type, term, description, ddl_text,
               row_count_estimate, column_count,
               columns_profile_json::text AS columns_profile_json,
               sample_data_json::text AS sample_data_json,
               sample_row_count, sample_redacted, confidence_score, confidence_reason,
               tags_json::text AS tags_json, temporary_classification, ignored,
               ignore_decision, profile_json::text AS profile_json, revision_no,
               created_by, created_at, updated_by, updated_at
        FROM agent_data_table_profile
        WHERE dataset_id = #{datasetId} AND table_id = #{tableId}
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """)
    AgentDataTableProfile selectLatestProfile(
        @Param("datasetId") Long datasetId,
        @Param("tableId") Long tableId
    );

    @Select("""
        SELECT id, dataset_id, table_id, job_id, source_hash, table_type, term, description, ddl_text,
               row_count_estimate, column_count,
               columns_profile_json::text AS columns_profile_json,
               sample_data_json::text AS sample_data_json,
               sample_row_count, sample_redacted, confidence_score, confidence_reason,
               tags_json::text AS tags_json, temporary_classification, ignored,
               ignore_decision, profile_json::text AS profile_json, revision_no,
               created_by, created_at, updated_by, updated_at
        FROM agent_data_table_profile
        WHERE dataset_id = #{datasetId} AND job_id = #{jobId}
        ORDER BY created_at, id
        """)
    List<AgentDataTableProfile> selectJobProfiles(
        @Param("datasetId") Long datasetId,
        @Param("jobId") Long jobId
    );

    @Update("""
        UPDATE agent_data_table_profile p
        SET ignored = #{ignored},
            ignore_decision = CASE WHEN #{ignored} THEN 'manual_ignore' ELSE 'manual_include' END,
            revision_no = revision_no + 1, updated_by = #{actorId}, updated_at = #{now}
        WHERE p.id = #{profileId} AND p.dataset_id = #{datasetId}
          AND p.table_id = #{tableId} AND p.revision_no = #{revisionNo}
          AND p.id = (
              SELECT latest.id FROM agent_data_table_profile latest
              WHERE latest.dataset_id = p.dataset_id AND latest.table_id = p.table_id
              ORDER BY latest.created_at DESC, latest.id DESC LIMIT 1
          )
        """)
    int updateProfileIgnore(
        @Param("datasetId") Long datasetId,
        @Param("tableId") Long tableId,
        @Param("profileId") Long profileId,
        @Param("revisionNo") Integer revisionNo,
        @Param("ignored") boolean ignored,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    @Insert("""
        INSERT INTO agent_data_profile_relation_recommendation (
            id, dataset_id, profile_job_id, source_table_id, source_column_id,
            target_table_id, target_column_id, confidence_score, join_type,
            join_condition, reason, status, applied_relation_id, created_at, updated_at
        ) VALUES (
            #{id}, #{datasetId}, #{profileJobId}, #{sourceTableId}, #{sourceColumnId},
            #{targetTableId}, #{targetColumnId}, #{confidenceScore}, #{joinType},
            #{joinCondition}, #{reason}, #{status}, #{appliedRelationId}, #{createdAt}, #{updatedAt}
        ) ON CONFLICT (profile_job_id, source_column_id, target_table_id, target_column_id)
          DO NOTHING
        """)
    int insertRecommendation(AgentDataProfileRelationRecommendation recommendation);

    @Select("""
        SELECT id, dataset_id, profile_job_id, source_table_id, source_column_id,
               target_table_id, target_column_id, confidence_score, join_type,
               join_condition, reason, status, applied_relation_id, created_at, updated_at
        FROM agent_data_profile_relation_recommendation
        WHERE dataset_id = #{datasetId} AND profile_job_id = #{jobId}
        ORDER BY confidence_score DESC, id
        """)
    List<AgentDataProfileRelationRecommendation> selectJobRecommendations(
        @Param("datasetId") Long datasetId,
        @Param("jobId") Long jobId
    );

    @Select("""
        SELECT id, dataset_id, profile_job_id, source_table_id, source_column_id,
               target_table_id, target_column_id, confidence_score, join_type,
               join_condition, reason, status, applied_relation_id, created_at, updated_at
        FROM agent_data_profile_relation_recommendation
        WHERE dataset_id = #{datasetId} AND profile_job_id = #{jobId}
          AND (source_table_id = #{tableId} OR target_table_id = #{tableId})
        ORDER BY confidence_score DESC, id
        LIMIT #{limit}
        """)
    List<AgentDataProfileRelationRecommendation> selectTableRecommendations(
        @Param("datasetId") Long datasetId,
        @Param("jobId") Long jobId,
        @Param("tableId") Long tableId,
        @Param("limit") int limit
    );

    @Select("""
        SELECT id, dataset_id, profile_job_id, source_table_id, source_column_id,
               target_table_id, target_column_id, confidence_score, join_type,
               join_condition, reason, status, applied_relation_id, created_at, updated_at
        FROM agent_data_profile_relation_recommendation
        WHERE id = #{recommendationId} AND dataset_id = #{datasetId}
        FOR UPDATE
        """)
    AgentDataProfileRelationRecommendation selectRecommendationForUpdate(
        @Param("datasetId") Long datasetId,
        @Param("recommendationId") Long recommendationId
    );

    @Select("""
        SELECT id, dataset_id, data_source_id, mode, status,
               requested_table_ids_json::text AS requested_table_ids_json,
               dataset_revision, data_source_revision, total_tables, completed_tables,
               failed_tables, progress_percent, current_table_id, cancel_requested,
               resume_of_job_id, worker_id, lease_until, attempt_no, max_attempts,
               revision_no, error_message, requested_by, created_at, started_at,
               finished_at, updated_at
        FROM agent_data_profile_job j
        WHERE j.dataset_id = #{datasetId} AND j.status = 'succeeded'
          AND EXISTS (SELECT 1 FROM agent_data_table_profile p WHERE p.job_id = j.id)
        ORDER BY finished_at DESC, id DESC
        LIMIT 1
        """)
    AgentDataProfileJob selectLatestSucceededJob(@Param("datasetId") Long datasetId);

    @Insert("""
        INSERT INTO agent_data_smart_import_preview (
            id, dataset_id, profile_job_id, status, dataset_revision, revision_no,
            expires_at, created_by, created_at, applied_by, applied_at
        ) VALUES (
            #{id}, #{datasetId}, #{profileJobId}, #{status}, #{datasetRevision},
            #{revisionNo}, #{expiresAt}, #{createdBy}, #{createdAt}, #{appliedBy}, #{appliedAt}
        )
        """)
    int insertPreview(AgentDataSmartImportPreview preview);

    @Insert("""
        INSERT INTO agent_data_smart_import_item (
            id, preview_id, item_type, resource_id, content_hash, proposed_json,
            status, applied_resource_id, error_message, created_at, updated_at
        ) VALUES (
            #{id}, #{previewId}, #{itemType}, #{resourceId}, #{contentHash},
            CAST(#{proposedJson} AS jsonb), #{status}, #{appliedResourceId},
            #{errorMessage}, #{createdAt}, #{updatedAt}
        )
        """)
    int insertPreviewItem(AgentDataSmartImportItem item);

    @Select("""
        SELECT id, dataset_id, profile_job_id, status, dataset_revision, revision_no,
               expires_at, created_by, created_at, applied_by, applied_at
        FROM agent_data_smart_import_preview
        WHERE id = #{previewId} AND dataset_id = #{datasetId}
        """)
    AgentDataSmartImportPreview selectPreview(
        @Param("datasetId") Long datasetId,
        @Param("previewId") Long previewId
    );

    @Select("""
        SELECT id, dataset_id, profile_job_id, status, dataset_revision, revision_no,
               expires_at, created_by, created_at, applied_by, applied_at
        FROM agent_data_smart_import_preview
        WHERE id = #{previewId} AND dataset_id = #{datasetId}
        FOR UPDATE
        """)
    AgentDataSmartImportPreview selectPreviewForUpdate(
        @Param("datasetId") Long datasetId,
        @Param("previewId") Long previewId
    );

    @Select("""
        SELECT id, preview_id, item_type, resource_id, content_hash,
               proposed_json::text AS proposed_json, status, applied_resource_id,
               error_message, created_at, updated_at
        FROM agent_data_smart_import_item
        WHERE preview_id = #{previewId}
        ORDER BY item_type, id
        """)
    List<AgentDataSmartImportItem> selectPreviewItems(@Param("previewId") Long previewId);

    @Select("""
        SELECT id, preview_id, item_type, resource_id, content_hash,
               proposed_json::text AS proposed_json, status, applied_resource_id,
               error_message, created_at, updated_at
        FROM agent_data_smart_import_item
        WHERE preview_id = #{previewId}
        ORDER BY item_type, id
        FOR UPDATE
        """)
    List<AgentDataSmartImportItem> selectPreviewItemsForUpdate(@Param("previewId") Long previewId);

    @Update("""
        UPDATE agent_data_smart_import_preview
        SET status = 'expired', revision_no = revision_no + 1
        WHERE id = #{previewId} AND dataset_id = #{datasetId}
          AND status = 'draft' AND expires_at <= #{now}
        """)
    int expirePreview(
        @Param("datasetId") Long datasetId,
        @Param("previewId") Long previewId,
        @Param("now") LocalDateTime now
    );

    @Select("""
        SELECT id, data_source_id, dataset_key, name, description, status,
               enable_row_policy, row_policy_json::text AS row_policy_json,
               schema_names_json::text AS schema_names_json, revision_no,
               last_sync_at, last_sync_error, owner_id,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_data_dataset
        WHERE id = #{datasetId} AND del_flag = '0'
        FOR UPDATE
        """)
    AgentDataDataset selectDatasetForUpdate(@Param("datasetId") Long datasetId);

    @Select("""
        SELECT id, dataset_id, table_key, physical_schema, physical_name, display_name,
               description, table_type, status, metadata_present, metadata_json::text AS metadata_json,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_data_table
        WHERE id = #{tableId} AND dataset_id = #{datasetId} AND del_flag = '0'
        FOR UPDATE
        """)
    AgentDataTable selectTableForSmartImport(
        @Param("datasetId") Long datasetId,
        @Param("tableId") Long tableId
    );

    @Select("""
        SELECT c.id, c.table_id, c.column_key, c.physical_name, c.display_name,
               c.data_type, c.description, c.is_primary, c.is_sensitive,
               c.status, c.metadata_present, c.created_at, c.updated_at
        FROM agent_data_column c
        JOIN agent_data_table t ON t.id = c.table_id
        WHERE c.table_id = #{tableId} AND t.dataset_id = #{datasetId} AND t.del_flag = '0'
        ORDER BY c.id
        FOR UPDATE OF c
        """)
    List<AgentDataColumn> selectColumnsForSmartImport(
        @Param("datasetId") Long datasetId,
        @Param("tableId") Long tableId
    );

    @Select("""
        SELECT c.id, c.table_id, c.column_key, c.physical_name, c.display_name,
               c.data_type, c.description, c.is_primary, c.is_sensitive,
               c.status, c.metadata_present, c.created_at, c.updated_at
        FROM agent_data_column c
        JOIN agent_data_table t ON t.id = c.table_id
        WHERE c.id = #{columnId} AND c.table_id = #{tableId}
          AND t.dataset_id = #{datasetId} AND t.del_flag = '0'
        FOR UPDATE OF c
        """)
    AgentDataColumn selectColumnForSmartImport(
        @Param("datasetId") Long datasetId,
        @Param("tableId") Long tableId,
        @Param("columnId") Long columnId
    );

    @Update("""
        UPDATE agent_data_dataset
        SET revision_no = revision_no + 1, update_by = #{actorId}, update_time = #{now}
        WHERE id = #{datasetId} AND revision_no = #{revisionNo} AND del_flag = '0'
        """)
    int advanceDatasetRevision(
        @Param("datasetId") Long datasetId,
        @Param("revisionNo") Integer revisionNo,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_profile_relation_recommendation
        SET status = 'applied', applied_relation_id = #{relationId}, updated_at = #{now}
        WHERE id = #{recommendationId} AND dataset_id = #{datasetId} AND status = 'pending'
        """)
    int markRecommendationApplied(
        @Param("datasetId") Long datasetId,
        @Param("recommendationId") Long recommendationId,
        @Param("relationId") Long relationId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_smart_import_item
        SET status = 'applied', applied_resource_id = #{appliedResourceId},
            error_message = NULL, updated_at = #{now}
        WHERE id = #{itemId} AND preview_id = #{previewId} AND status = 'available'
        """)
    int markPreviewItemApplied(
        @Param("previewId") Long previewId,
        @Param("itemId") Long itemId,
        @Param("appliedResourceId") Long appliedResourceId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_smart_import_item
        SET status = 'skipped', updated_at = #{now}
        WHERE preview_id = #{previewId} AND status = 'available'
        """)
    int skipRemainingPreviewItems(
        @Param("previewId") Long previewId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_smart_import_preview
        SET status = 'applied', revision_no = revision_no + 1,
            applied_by = #{actorId}, applied_at = #{now}
        WHERE id = #{previewId} AND dataset_id = #{datasetId}
          AND status = 'draft' AND revision_no = #{revisionNo} AND expires_at > #{now}
        """)
    int completePreview(
        @Param("datasetId") Long datasetId,
        @Param("previewId") Long previewId,
        @Param("revisionNo") Integer revisionNo,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );
}
