package group.aitools.nhs.platform.artifact.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.artifact.domain.AgentAcceptanceRecord;
import group.aitools.nhs.platform.artifact.domain.AgentArtifact;
import group.aitools.nhs.platform.artifact.persistence.row.AcceptanceTaskRow;
import group.aitools.nhs.platform.artifact.persistence.row.ArtifactTaskRow;

import java.util.List;

/**
 * 获取任务。
 *
 * 定义制品验收相关的数据访问契约。
 * Artifact versions and append-only acceptance facts. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface ArtifactAcceptanceMapper {

    @Select("""
        SELECT id AS task_id, project_id, latest_run_id, status AS task_status, visibility
        FROM agent_task WHERE id = #{taskId} AND del_flag = '0'
        """)
    ArtifactTaskRow selectTask(@Param("taskId") Long taskId);

    /**
     * 获取验收任务。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT t.id AS task_id, t.project_id, t.latest_run_id, t.status AS task_status,
               t.visibility, t.acceptance_mode,
               tv.acceptance_snapshot_json::text AS acceptance_snapshot_json,
               r.status AS run_status
        FROM agent_task t
        JOIN agent_task_run r ON r.id = #{runId} AND r.task_id = t.id
        LEFT JOIN agent_task_version tv
          ON tv.id = t.current_version_id AND tv.task_id = t.id
        WHERE t.id = #{taskId} AND t.del_flag = '0'
        """)
    AcceptanceTaskRow selectAcceptanceTask(
        @Param("taskId") Long taskId,
        @Param("runId") Long runId
    );

    /**
     * 获取Next版本。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param artifactType 业务类型
     * @param name 名称
     * @return 处理结果
     */
    @Select("""
        SELECT COALESCE(MAX(version_no), 0) + 1
        FROM agent_artifact
        WHERE task_id = #{taskId} AND run_id = #{runId}
          AND artifact_type = #{artifactType} AND name = #{name}
        """)
    int selectNextVersion(
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("artifactType") String artifactType,
        @Param("name") String name
    );

    /**
     * 处理{@code stepBelongsToRun}并返回对应结果。
     *
     * @param runId 资源标识
     * @param stepId 资源标识
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Select("""
        SELECT EXISTS (
            SELECT 1 FROM agent_run_step WHERE id = #{stepId} AND run_id = #{runId}
        )
        """)
    boolean stepBelongsToRun(@Param("runId") Long runId, @Param("stepId") Long stepId);

    /**
     * 创建并保存制品。
     *
     * @param artifact 制品参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_artifact (
            id, project_id, task_id, run_id, step_id, artifact_type, name, version_no,
            storage_type, storage_ref, mime_type, size_bytes, content_hash,
            sensitive_level, visibility, status, metadata_json, created_by, created_at
        ) VALUES (
            #{id}, #{projectId}, #{taskId}, #{runId}, #{stepId}, #{artifactType}, #{name},
            #{versionNo}, #{storageType}, #{storageRef}, #{mimeType}, #{sizeBytes},
            #{contentHash}, #{sensitiveLevel}, #{visibility}, #{status},
            CAST(#{metadataJson} AS jsonb), #{createdBy}, #{createdAt}
        )
        """)
    int insertArtifact(AgentArtifact artifact);

    /**
     * 获取制品。
     *
     * @param taskId 资源标识
     * @param artifactId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, project_id, task_id, run_id, step_id, artifact_type, name, version_no,
               storage_type, storage_ref, mime_type, size_bytes, content_hash,
               sensitive_level, visibility, status, metadata_json::text AS metadata_json,
               created_by, created_at
        FROM agent_artifact
        WHERE id = #{artifactId} AND task_id = #{taskId}
        """)
    AgentArtifact selectArtifact(
        @Param("taskId") Long taskId,
        @Param("artifactId") Long artifactId
    );

    /**
     * 获取{@code Artifacts}。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, project_id, task_id, run_id, step_id, artifact_type, name, version_no,
               storage_type, storage_ref, mime_type, size_bytes, content_hash,
               sensitive_level, visibility, status, metadata_json::text AS metadata_json,
               created_by, created_at
        FROM agent_artifact
        WHERE task_id = #{taskId} AND (#{runId} IS NULL OR run_id = #{runId})
          AND status <> 'deleted'
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentArtifact> selectArtifacts(
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("limit") int limit
    );

    /**
     * 获取{@code AvailableArtifacts}。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param artifactIds 资源标识集合
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT id, project_id, task_id, run_id, step_id, artifact_type, name, version_no,
               storage_type, storage_ref, mime_type, size_bytes, content_hash,
               sensitive_level, visibility, status, metadata_json::text AS metadata_json,
               created_by, created_at
        FROM agent_artifact
        WHERE task_id = #{taskId} AND run_id = #{runId} AND status = 'available'
          AND id IN
          <foreach collection="artifactIds" item="artifactId" open="(" separator="," close=")">
              #{artifactId}
          </foreach>
        ORDER BY id
        </script>
        """)
    List<AgentArtifact> selectAvailableArtifacts(
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("artifactIds") List<Long> artifactIds
    );

    /**
     * 处理{@code countPendingApprovals}并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT count(*) FROM agent_approval_request
        WHERE task_id = #{taskId} AND run_id = #{runId} AND status = 'pending'
          AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
        """)
    int countPendingApprovals(@Param("taskId") Long taskId, @Param("runId") Long runId);

    /**
     * 获取验收ByKey。
     *
     * @param keyHash {@code keyHash}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_id, run_id, artifact_ids_json::text AS artifact_ids_json,
               acceptance_type, result, rule_result_json::text AS rule_result_json,
               comment, reviewer_id, reviewer_principal_type, rework_no, created_at,
               idempotency_key_hash, request_hash
        FROM agent_acceptance_record
        WHERE idempotency_key_hash = #{keyHash}
        """)
    AgentAcceptanceRecord selectAcceptanceByKey(@Param("keyHash") String keyHash);

    /**
     * 获取{@code Acceptances}。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, task_id, run_id, artifact_ids_json::text AS artifact_ids_json,
               acceptance_type, result, rule_result_json::text AS rule_result_json,
               comment, reviewer_id, reviewer_principal_type, rework_no, created_at,
               idempotency_key_hash, request_hash
        FROM agent_acceptance_record
        WHERE task_id = #{taskId} AND run_id = #{runId}
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentAcceptanceRecord> selectAcceptances(
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("limit") int limit
    );

    /**
     * 处理{@code countReworks}并返回对应结果。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT count(*) FROM agent_acceptance_record
        WHERE task_id = #{taskId} AND result = 'rework'
        """)
    int countReworks(@Param("taskId") Long taskId);

    /**
     * 创建并保存验收。
     *
     * @param record {@code record}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_acceptance_record (
            id, task_id, run_id, artifact_ids_json, acceptance_type, result,
            rule_result_json, comment, reviewer_id, reviewer_principal_type, rework_no, created_at,
            idempotency_key_hash, request_hash
        ) VALUES (
            #{id}, #{taskId}, #{runId}, CAST(#{artifactIdsJson} AS jsonb),
            #{acceptanceType}, #{result}, CAST(#{ruleResultJson} AS jsonb),
            #{comment}, #{reviewerId}, #{reviewerPrincipalType}, #{reworkNo}, #{createdAt},
            #{idempotencyKeyHash}, #{requestHash}
        )
        """)
    int insertAcceptance(AgentAcceptanceRecord record);

    /**
     * 处理transition任务并返回对应结果。
     *
     * @param taskId 资源标识
     * @param runId 资源标识
     * @param expectedStatuses 目标状态
     * @param targetStatus 目标状态
     * @param userId 资源标识
     * @return 处理结果
     */
    @Update("""
        <script>
        UPDATE agent_task
        SET status = #{targetStatus}, update_by = #{userId}, update_time = CURRENT_TIMESTAMP
        WHERE id = #{taskId} AND latest_run_id = #{runId} AND del_flag = '0'
          AND status IN
          <foreach collection="expectedStatuses" item="status" open="(" separator="," close=")">
              #{status}
          </foreach>
        </script>
        """)
    int transitionTask(
        @Param("taskId") Long taskId,
        @Param("runId") Long runId,
        @Param("expectedStatuses") List<String> expectedStatuses,
        @Param("targetStatus") String targetStatus,
        @Param("userId") Long userId
    );
}
