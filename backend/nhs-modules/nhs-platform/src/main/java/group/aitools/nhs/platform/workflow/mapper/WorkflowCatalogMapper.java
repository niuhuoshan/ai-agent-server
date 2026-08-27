package group.aitools.nhs.platform.workflow.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.workflow.persistence.row.WorkflowTemplateRow;

import java.util.List;

/**
 * 定义工作流目录相关的数据访问契约。
 */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface WorkflowCatalogMapper {

    /**
     * 获取{@code Published}。
     *
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT d.id AS workflow_id, d.workflow_key, d.name,
               d.workflow_type, d.status AS workflow_status,
               v.id AS version_id, v.version_no,
               v.graph_json::text AS graph_json,
               v.runtime_policy_json::text AS runtime_policy_json,
               v.content_hash, v.status AS version_status, v.published_at
        FROM agent_workflow_definition d
        JOIN agent_workflow_version v ON v.workflow_id = d.id
        WHERE d.del_flag = '0' AND d.workflow_type = 'fixed_template'
          AND d.status = 'active' AND v.status = 'published'
        ORDER BY d.workflow_key, v.version_no DESC
        """)
    List<WorkflowTemplateRow> selectPublished();

    /**
     * 获取版本。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT d.id AS workflow_id, d.workflow_key, d.name,
               d.workflow_type, d.status AS workflow_status,
               v.id AS version_id, v.version_no,
               v.graph_json::text AS graph_json,
               v.runtime_policy_json::text AS runtime_policy_json,
               v.content_hash, v.status AS version_status, v.published_at
        FROM agent_workflow_definition d
        JOIN agent_workflow_version v ON v.workflow_id = d.id
        WHERE v.id = #{versionId} AND d.del_flag = '0'
        """)
    WorkflowTemplateRow selectVersion(@Param("versionId") Long versionId);
}
