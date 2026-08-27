package group.aitools.nhs.platform.data.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.data.domain.AgentDataCatalogImportItem;
import group.aitools.nhs.platform.data.domain.AgentDataCatalogImportPreview;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataMetric;
import group.aitools.nhs.platform.data.domain.AgentDataRelation;
import group.aitools.nhs.platform.data.domain.AgentDataTable;

import java.time.LocalDateTime;
import java.util.List;

/** Persistence boundary for durable catalog-import previews and atomic apply locks. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface DataCatalogImportMapper {

    @Insert("""
        INSERT INTO agent_data_catalog_import_preview (
            id, dataset_id, source_type, source_hash, status, dataset_revision, revision_no,
            table_count, column_count, diagnostics_json, expires_at, created_by, created_at
        ) VALUES (
            #{id}, #{datasetId}, #{sourceType}, #{sourceHash}, #{status}, #{datasetRevision}, #{revisionNo},
            #{tableCount}, #{columnCount}, CAST(#{diagnosticsJson} AS jsonb), #{expiresAt}, #{createdBy}, #{createdAt}
        )
        """)
    int insertPreview(AgentDataCatalogImportPreview preview);

    @Insert("""
        INSERT INTO agent_data_catalog_import_item (
            id, preview_id, item_type, resource_key, action, current_hash, content_hash,
            proposed_json, status, created_at
        ) VALUES (
            #{id}, #{previewId}, #{itemType}, #{resourceKey}, #{action}, #{currentHash}, #{contentHash},
            CAST(#{proposedJson} AS jsonb), #{status}, #{createdAt}
        )
        """)
    int insertItem(AgentDataCatalogImportItem item);

    @Update("""
        UPDATE agent_data_catalog_import_preview
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
        SELECT id, dataset_id, source_type, source_hash, status, dataset_revision, revision_no,
               table_count, column_count, diagnostics_json::text AS diagnostics_json,
               expires_at, created_by, created_at, applied_by, applied_at
        FROM agent_data_catalog_import_preview
        WHERE id = #{previewId} AND dataset_id = #{datasetId}
        """)
    AgentDataCatalogImportPreview selectPreview(
        @Param("datasetId") Long datasetId,
        @Param("previewId") Long previewId
    );

    @Select("""
        SELECT id, dataset_id, source_type, source_hash, status, dataset_revision, revision_no,
               table_count, column_count, diagnostics_json::text AS diagnostics_json,
               expires_at, created_by, created_at, applied_by, applied_at
        FROM agent_data_catalog_import_preview
        WHERE id = #{previewId} AND dataset_id = #{datasetId}
        FOR UPDATE
        """)
    AgentDataCatalogImportPreview selectPreviewForUpdate(
        @Param("datasetId") Long datasetId,
        @Param("previewId") Long previewId
    );

    @Select("""
        SELECT id, preview_id, item_type, resource_key, action, current_hash, content_hash,
               proposed_json::text AS proposed_json, status, applied_resource_id,
               error_message, created_at, updated_at
        FROM agent_data_catalog_import_item
        WHERE preview_id = #{previewId}
        ORDER BY CASE item_type WHEN 'table' THEN 1 WHEN 'metric' THEN 2 ELSE 3 END, id
        """)
    List<AgentDataCatalogImportItem> selectItems(@Param("previewId") Long previewId);

    @Select("""
        SELECT id, preview_id, item_type, resource_key, action, current_hash, content_hash,
               proposed_json::text AS proposed_json, status, applied_resource_id,
               error_message, created_at, updated_at
        FROM agent_data_catalog_import_item
        WHERE preview_id = #{previewId}
        ORDER BY CASE item_type WHEN 'table' THEN 1 WHEN 'metric' THEN 2 ELSE 3 END, id
        FOR UPDATE
        """)
    List<AgentDataCatalogImportItem> selectItemsForUpdate(@Param("previewId") Long previewId);

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
               description, table_type, status, synonyms_json::text AS synonyms_json,
               metadata_present, metadata_json::text AS metadata_json,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_data_table
        WHERE dataset_id = #{datasetId} AND del_flag = '0'
        ORDER BY id
        FOR UPDATE
        """)
    List<AgentDataTable> selectTablesForUpdate(@Param("datasetId") Long datasetId);

    @Select("""
        SELECT c.id, c.table_id, c.column_key, c.physical_name, c.display_name, c.data_type,
               c.description, c.is_primary, c.is_sensitive, c.enum_json::text AS enum_json,
               c.synonyms_json::text AS synonyms_json, c.sample_values_json::text AS sample_values_json,
               c.status, c.metadata_present, c.created_at, c.updated_at
        FROM agent_data_column c
        JOIN agent_data_table t ON t.id = c.table_id
        WHERE t.dataset_id = #{datasetId} AND t.del_flag = '0'
        ORDER BY c.id
        FOR UPDATE OF c
        """)
    List<AgentDataColumn> selectColumnsForUpdate(@Param("datasetId") Long datasetId);

    @Select("""
        SELECT m.id, m.dataset_id, m.metric_key, m.name, m.description, m.calculation_logic,
               m.unit, m.status, m.version_no, m.created_by, m.created_at, m.updated_at
        FROM agent_data_metric m
        WHERE m.dataset_id = #{datasetId}
          AND NOT EXISTS (
              SELECT 1 FROM agent_data_metric later
              WHERE later.dataset_id = m.dataset_id AND later.metric_key = m.metric_key
                AND (later.version_no > m.version_no
                  OR (later.version_no = m.version_no AND later.id > m.id))
          )
        ORDER BY m.metric_key, m.id
        FOR UPDATE OF m
        """)
    List<AgentDataMetric> selectLatestMetricsForUpdate(@Param("datasetId") Long datasetId);

    @Select("""
        SELECT id, dataset_id, source_table_id, target_table_id, join_type, join_condition,
               description, status, revision_no, created_by, created_at, updated_by, updated_at
        FROM agent_data_relation
        WHERE dataset_id = #{datasetId}
        ORDER BY id
        FOR UPDATE
        """)
    List<AgentDataRelation> selectRelationshipsForUpdate(@Param("datasetId") Long datasetId);

    @Insert("""
        INSERT INTO agent_data_table (
            id, dataset_id, table_key, physical_schema, physical_name, display_name,
            description, table_type, status, synonyms_json, metadata_present, metadata_json,
            create_by, create_time, del_flag
        ) VALUES (
            #{id}, #{datasetId}, #{tableKey}, #{physicalSchema}, #{physicalName}, #{displayName},
            #{description}, #{tableType}, 'inactive', CAST(#{synonymsJson} AS jsonb), FALSE,
            CAST(#{metadataJson} AS jsonb), #{createBy}, #{createTime}, '0'
        )
        """)
    int insertDeclaredTable(AgentDataTable table);

    @Update("""
        UPDATE agent_data_table
        SET display_name = #{displayName}, description = #{description},
            synonyms_json = CAST(#{synonymsJson} AS jsonb),
            table_type = CASE WHEN metadata_present THEN table_type ELSE #{tableType} END,
            status = CASE WHEN metadata_present THEN #{status} ELSE 'inactive' END,
            update_by = #{updateBy}, update_time = #{updateTime}
        WHERE id = #{id} AND dataset_id = #{datasetId} AND del_flag = '0'
        """)
    int updateImportedTable(AgentDataTable table);

    @Insert("""
        INSERT INTO agent_data_column (
            id, table_id, column_key, physical_name, display_name, data_type, description,
            is_primary, is_sensitive, enum_json, synonyms_json, sample_values_json,
            status, metadata_present, created_at
        ) VALUES (
            #{id}, #{tableId}, #{columnKey}, #{physicalName}, #{displayName}, #{dataType}, #{description},
            #{isPrimary}, #{isSensitive}, CAST(#{enumJson} AS jsonb), CAST(#{synonymsJson} AS jsonb),
            '[]'::jsonb, 'inactive', FALSE, #{createdAt}
        )
        """)
    int insertDeclaredColumn(AgentDataColumn column);

    @Update("""
        UPDATE agent_data_column
        SET display_name = #{displayName}, description = #{description},
            is_sensitive = #{isSensitive}, enum_json = CAST(#{enumJson} AS jsonb),
            synonyms_json = CAST(#{synonymsJson} AS jsonb),
            data_type = CASE WHEN metadata_present THEN data_type ELSE #{dataType} END,
            is_primary = CASE WHEN metadata_present THEN is_primary ELSE #{isPrimary} END,
            status = CASE WHEN metadata_present THEN #{status} ELSE 'inactive' END,
            updated_at = #{updatedAt}
        WHERE id = #{id} AND table_id = #{tableId}
        """)
    int updateImportedColumn(AgentDataColumn column);

    @Update("""
        UPDATE agent_data_catalog_import_item
        SET status = 'applied', applied_resource_id = #{resourceId}, updated_at = #{now}
        WHERE preview_id = #{previewId} AND id = #{itemId} AND status = 'available'
        """)
    int markItemApplied(
        @Param("previewId") Long previewId,
        @Param("itemId") Long itemId,
        @Param("resourceId") Long resourceId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_catalog_import_item
        SET status = 'skipped', updated_at = #{now}
        WHERE preview_id = #{previewId} AND status = 'available'
        """)
    int skipRemainingItems(
        @Param("previewId") Long previewId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_dataset
        SET revision_no = revision_no + 1, update_by = #{actorId}, update_time = #{now}
        WHERE id = #{datasetId} AND revision_no = #{expectedRevision} AND del_flag = '0'
        """)
    int advanceDatasetRevision(
        @Param("datasetId") Long datasetId,
        @Param("expectedRevision") Integer expectedRevision,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_catalog_import_preview
        SET status = 'applied', revision_no = revision_no + 1,
            applied_by = #{actorId}, applied_at = #{now}
        WHERE id = #{previewId} AND dataset_id = #{datasetId}
          AND status = 'draft' AND revision_no = #{revisionNo}
        """)
    int completePreview(
        @Param("datasetId") Long datasetId,
        @Param("previewId") Long previewId,
        @Param("revisionNo") Integer revisionNo,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );
}
