package group.aitools.nhs.platform.task.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.task.domain.AgentTaskVersion;

import java.util.List;

/**
 * 创建并保存快照。
 *
 * 定义智能体任务版本相关的数据访问契约。
 * Writes immutable task snapshots with PostgreSQL JSONB casts. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentTaskVersionMapper extends BaseMapper<AgentTaskVersion> {

    @Insert("""
        INSERT INTO agent_task_version (
            id, task_id, version_no, title, objective, agent_version_id, workflow_version_id,
            context_snapshot_json, resource_snapshot_json, acceptance_snapshot_json,
            input_snapshot_json, content_hash, created_by, created_at
        ) VALUES (
            #{id}, #{taskId}, #{versionNo}, #{title}, #{objective}, #{agentVersionId}, #{workflowVersionId},
            CAST(#{contextSnapshotJson} AS jsonb), CAST(#{resourceSnapshotJson} AS jsonb),
            CAST(#{acceptanceSnapshotJson} AS jsonb), CAST(#{inputSnapshotJson} AS jsonb),
            #{contentHash}, #{createdBy}, #{createdAt}
        )
        """)
    int insertSnapshot(AgentTaskVersion version);

    /**
     * 获取Next版本No。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Select("SELECT COALESCE(MAX(version_no), 0) + 1 FROM agent_task_version WHERE task_id = #{taskId}")
    int selectNextVersionNo(@Param("taskId") Long taskId);

    /**
     * 获取版本。
     *
     * @param taskId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, task_id, version_no, title, objective, agent_version_id, workflow_version_id,
               context_snapshot_json::text AS context_snapshot_json,
               resource_snapshot_json::text AS resource_snapshot_json,
               acceptance_snapshot_json::text AS acceptance_snapshot_json,
               input_snapshot_json::text AS input_snapshot_json,
               content_hash, created_by, created_at
        FROM agent_task_version
        WHERE task_id = #{taskId} AND id = #{versionId}
        """)
    AgentTaskVersion selectVersion(
        @Param("taskId") Long taskId,
        @Param("versionId") Long versionId
    );

    /**
     * 获取{@code Versions}。
     *
     * @param taskId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, task_id, version_no, title, objective, agent_version_id, workflow_version_id,
               context_snapshot_json::text AS context_snapshot_json,
               resource_snapshot_json::text AS resource_snapshot_json,
               acceptance_snapshot_json::text AS acceptance_snapshot_json,
               input_snapshot_json::text AS input_snapshot_json,
               content_hash, created_by, created_at
        FROM agent_task_version
        WHERE task_id = #{taskId}
        ORDER BY version_no DESC
        LIMIT #{limit}
        """)
    List<AgentTaskVersion> selectVersions(
        @Param("taskId") Long taskId,
        @Param("limit") int limit
    );
}
