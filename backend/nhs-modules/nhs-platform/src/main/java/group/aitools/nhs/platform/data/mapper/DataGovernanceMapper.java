package group.aitools.nhs.platform.data.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.data.domain.AgentDataMetric;
import group.aitools.nhs.platform.data.domain.AgentDataRelation;
import group.aitools.nhs.platform.data.persistence.row.MetadataChangeRow;

import java.time.LocalDateTime;
import java.util.List;

/** Persistence boundary for metrics, relationships, row policies and their change history. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface DataGovernanceMapper {

    @Select("""
        SELECT DISTINCT ON (metric_key)
               id, dataset_id, metric_key, name, description, calculation_logic, unit,
               status, version_no, created_by, created_at, updated_at
        FROM agent_data_metric
        WHERE dataset_id = #{datasetId}
        ORDER BY metric_key, version_no DESC, id DESC
        """)
    List<AgentDataMetric> selectLatestMetrics(@Param("datasetId") Long datasetId);

    @Select("""
        SELECT id, dataset_id, metric_key, name, description, calculation_logic, unit,
               status, version_no, created_by, created_at, updated_at
        FROM agent_data_metric m
        WHERE m.dataset_id = #{datasetId} AND m.id = #{metricId}
          AND m.version_no = (
              SELECT MAX(latest.version_no) FROM agent_data_metric latest
              WHERE latest.dataset_id = m.dataset_id AND latest.metric_key = m.metric_key
          )
        FOR UPDATE
        """)
    AgentDataMetric selectLatestMetricForUpdate(
        @Param("datasetId") Long datasetId,
        @Param("metricId") Long metricId
    );

    @Select("""
        SELECT COUNT(*) FROM agent_data_metric
        WHERE dataset_id = #{datasetId} AND metric_key = #{metricKey}
        """)
    int countMetricKey(
        @Param("datasetId") Long datasetId,
        @Param("metricKey") String metricKey
    );

    @Insert("""
        INSERT INTO agent_data_metric (
            id, dataset_id, metric_key, name, description, calculation_logic, unit,
            status, version_no, created_by, created_at, updated_at
        ) VALUES (
            #{id}, #{datasetId}, #{metricKey}, #{name}, #{description}, #{calculationLogic}, #{unit},
            #{status}, #{versionNo}, #{createdBy}, #{createdAt}, #{updatedAt}
        )
        """)
    int insertMetric(AgentDataMetric metric);

    @Select("""
        SELECT id, dataset_id, source_table_id, target_table_id, join_type, join_condition,
               description, status, revision_no, created_by, created_at, updated_by, updated_at
        FROM agent_data_relation
        WHERE dataset_id = #{datasetId}
        ORDER BY status, created_at DESC, id DESC
        """)
    List<AgentDataRelation> selectRelationships(@Param("datasetId") Long datasetId);

    @Select("""
        SELECT id, dataset_id, source_table_id, target_table_id, join_type, join_condition,
               description, status, revision_no, created_by, created_at, updated_by, updated_at
        FROM agent_data_relation
        WHERE dataset_id = #{datasetId} AND id = #{relationshipId}
        FOR UPDATE
        """)
    AgentDataRelation selectRelationshipForUpdate(
        @Param("datasetId") Long datasetId,
        @Param("relationshipId") Long relationshipId
    );

    @Select("""
        SELECT COUNT(*) FROM agent_data_relation
        WHERE dataset_id = #{datasetId} AND source_table_id = #{sourceTableId}
          AND target_table_id = #{targetTableId} AND status = 'active'
          AND (CAST(#{excludeId} AS BIGINT) IS NULL OR id <> #{excludeId})
        """)
    int countActiveRelationship(
        @Param("datasetId") Long datasetId,
        @Param("sourceTableId") Long sourceTableId,
        @Param("targetTableId") Long targetTableId,
        @Param("excludeId") Long excludeId
    );

    @Insert("""
        INSERT INTO agent_data_relation (
            id, dataset_id, source_table_id, target_table_id, join_type, join_condition,
            description, status, revision_no, created_by, created_at, updated_by, updated_at
        ) VALUES (
            #{id}, #{datasetId}, #{sourceTableId}, #{targetTableId}, #{joinType}, #{joinCondition},
            #{description}, #{status}, #{revisionNo}, #{createdBy}, #{createdAt}, #{updatedBy}, #{updatedAt}
        )
        """)
    int insertRelationship(AgentDataRelation relationship);

    @Update("""
        UPDATE agent_data_relation
        SET source_table_id = #{sourceTableId}, target_table_id = #{targetTableId},
            join_type = #{joinType}, join_condition = #{joinCondition}, description = #{description},
            status = #{status}, revision_no = revision_no + 1,
            updated_by = #{updatedBy}, updated_at = #{updatedAt}
        WHERE id = #{id} AND dataset_id = #{datasetId} AND revision_no = #{revisionNo}
        """)
    int updateRelationship(AgentDataRelation relationship);

    @Update("""
        UPDATE agent_data_dataset
        SET enable_row_policy = #{enabled}, row_policy_json = CAST(#{policyJson} AS jsonb),
            revision_no = revision_no + 1, update_by = #{actorId}, update_time = #{now}
        WHERE id = #{datasetId} AND revision_no = #{revisionNo} AND del_flag = '0'
        """)
    int updateRowPolicy(
        @Param("datasetId") Long datasetId,
        @Param("revisionNo") Integer revisionNo,
        @Param("enabled") boolean enabled,
        @Param("policyJson") String policyJson,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    @Insert("""
        INSERT INTO agent_data_metadata_change (
            id, dataset_id, resource_type, resource_id, action,
            before_json, after_json, before_hash, after_hash, actor_id, created_at
        ) VALUES (
            #{id}, #{datasetId}, #{resourceType}, #{resourceId}, #{action},
            CAST(#{beforeJson} AS jsonb), CAST(#{afterJson} AS jsonb),
            #{beforeHash}, #{afterHash}, #{actorId}, #{createdAt}
        )
        """)
    int insertChange(MetadataChangeRow change);

    @Select("""
        SELECT id, dataset_id, resource_type, resource_id, action,
               before_json::text AS before_json, after_json::text AS after_json,
               before_hash, after_hash, actor_id, created_at
        FROM agent_data_metadata_change
        WHERE dataset_id = #{datasetId}
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<MetadataChangeRow> selectChanges(
        @Param("datasetId") Long datasetId,
        @Param("limit") int limit
    );
}
