package group.aitools.nhs.platform.data.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataQuery;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.persistence.row.DataQueryStoredResultRow;
import group.aitools.nhs.platform.data.persistence.row.DatasetDeleteImpactRow;
import group.aitools.nhs.platform.data.domain.AgentDataTable;

import java.time.LocalDateTime;
import java.util.List;

/** Persistence boundary for data sources, datasets, synchronized metadata and query facts. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface DataCatalogMapper {

    @Select("""
        SELECT id, source_key, name, db_type, endpoint_url, database_name, credential_ref,
               readonly, status, config_json::text AS config_json, revision_no,
               connection_timeout_ms, statement_timeout_ms, max_rows, max_result_bytes,
               last_test_status, last_test_at, last_test_error,
               last_metadata_sync_at, last_metadata_sync_error,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_data_source
        WHERE del_flag = '0'
        ORDER BY create_time DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentDataSource> selectSources(@Param("limit") int limit);

    @Select("""
        SELECT id, source_key, name, db_type, endpoint_url, database_name, credential_ref,
               readonly, status, config_json::text AS config_json, revision_no,
               connection_timeout_ms, statement_timeout_ms, max_rows, max_result_bytes,
               last_test_status, last_test_at, last_test_error,
               last_metadata_sync_at, last_metadata_sync_error,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_data_source
        WHERE id = #{id} AND del_flag = '0'
        """)
    AgentDataSource selectSource(@Param("id") Long id);

    @Insert("""
        INSERT INTO agent_data_source (
            id, source_key, name, db_type, endpoint_url, database_name, credential_ref,
            readonly, status, config_json, revision_no, connection_timeout_ms,
            statement_timeout_ms, max_rows, max_result_bytes, create_by, create_time, del_flag
        ) VALUES (
            #{id}, #{sourceKey}, #{name}, #{dbType}, #{endpointUrl}, #{databaseName}, #{credentialRef},
            TRUE, #{status}, CAST(#{configJson} AS jsonb), 1, #{connectionTimeoutMs},
            #{statementTimeoutMs}, #{maxRows}, #{maxResultBytes}, #{createBy}, #{createTime}, '0'
        )
        """)
    int insertSource(AgentDataSource source);

    @Update("""
        UPDATE agent_data_source
        SET name = #{name}, db_type = #{dbType},
            endpoint_url = #{endpointUrl}, database_name = #{databaseName},
            credential_ref = #{credentialRef}, status = #{status},
            config_json = CAST(#{configJson} AS jsonb),
            connection_timeout_ms = #{connectionTimeoutMs},
            statement_timeout_ms = #{statementTimeoutMs}, max_rows = #{maxRows},
            max_result_bytes = #{maxResultBytes}, revision_no = revision_no + 1,
            update_by = #{updateBy}, update_time = #{updateTime},
            last_test_status = NULL, last_test_at = NULL, last_test_error = NULL
        WHERE id = #{id} AND revision_no = #{revisionNo} AND del_flag = '0'
        """)
    int updateSource(AgentDataSource source);

    @Update("""
        UPDATE agent_data_dataset
        SET status = CASE WHEN status = 'active' THEN 'error' ELSE status END,
            last_sync_error = '数据源连接配置已变化，必须重新同步元数据',
            update_by = #{actorId}, update_time = #{now}
        WHERE data_source_id = #{sourceId} AND del_flag = '0'
        """)
    int invalidateSourceDatasets(
        @Param("sourceId") Long sourceId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_table
        SET status = 'inactive', metadata_present = FALSE,
            update_by = #{actorId}, update_time = #{now}
        WHERE del_flag = '0' AND dataset_id IN (
            SELECT id FROM agent_data_dataset
            WHERE data_source_id = #{sourceId} AND del_flag = '0'
        )
        """)
    int invalidateSourceTables(
        @Param("sourceId") Long sourceId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_source
        SET last_test_status = #{testStatus}, last_test_at = #{testedAt},
            last_test_error = #{error}, update_by = #{actorId}, update_time = #{testedAt}
        WHERE id = #{sourceId} AND del_flag = '0'
        """)
    int recordConnectionTest(
        @Param("sourceId") Long sourceId,
        @Param("testStatus") String testStatus,
        @Param("error") String error,
        @Param("actorId") Long actorId,
        @Param("testedAt") LocalDateTime testedAt
    );

    @Update("""
        UPDATE agent_data_source
        SET del_flag = '1', revision_no = revision_no + 1,
            update_by = #{actorId}, update_time = #{now}
        WHERE id = #{sourceId} AND del_flag = '0'
          AND NOT EXISTS (
            SELECT 1 FROM agent_data_dataset
            WHERE data_source_id = #{sourceId} AND del_flag = '0'
          )
        """)
    int softDeleteSource(
        @Param("sourceId") Long sourceId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    @Select("""
        SELECT id, data_source_id, dataset_key, name, description, status,
               enable_row_policy, row_policy_json::text AS row_policy_json,
               schema_names_json::text AS schema_names_json, revision_no,
               last_sync_at, last_sync_error, owner_id,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_data_dataset
        WHERE del_flag = '0'
        ORDER BY create_time DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentDataDataset> selectDatasets(@Param("limit") int limit);

    @Select("""
        SELECT id, data_source_id, dataset_key, name, description, status,
               enable_row_policy, row_policy_json::text AS row_policy_json,
               schema_names_json::text AS schema_names_json, revision_no,
               last_sync_at, last_sync_error, owner_id,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_data_dataset
        WHERE id = #{id} AND del_flag = '0'
        """)
    AgentDataDataset selectDataset(@Param("id") Long id);

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
    AgentDataDataset lockDatasetForDelete(@Param("datasetId") Long datasetId);

    @Select("""
        SELECT
            (
                SELECT COUNT(DISTINCT resource.task_id)
                FROM agent_task_resource resource
                JOIN agent_task task ON task.id = resource.task_id
                WHERE resource.resource_type = 'dataset'
                  AND resource.resource_id = dataset.id
                  AND task.del_flag = '0'
                  AND task.status NOT IN ('archived', 'cancelled')
            ) AS active_task_bindings,
            (
                SELECT COUNT(*)
                FROM agent_report report
                WHERE report.dataset_id = dataset.id
                  AND report.del_flag = '0'
                  AND report.status <> 'archived'
            ) AS active_reports,
            (
                SELECT COUNT(*)
                FROM agent_data_query query_fact
                WHERE query_fact.dataset_id = dataset.id
                  AND query_fact.status IN ('planning', 'approved', 'running')
            ) AS running_data_queries,
            (
                SELECT COUNT(*)
                FROM agent_data_profile_job profile_job
                WHERE profile_job.dataset_id = dataset.id
                  AND profile_job.status IN ('queued', 'running')
            ) AS running_profile_jobs,
            (
                SELECT COUNT(*)
                FROM agent_data_smart_import_preview smart_import
                WHERE smart_import.dataset_id = dataset.id
                  AND smart_import.status = 'draft'
                  AND smart_import.expires_at > CURRENT_TIMESTAMP
            ) AS draft_smart_imports,
            (
                SELECT COUNT(*)
                FROM agent_data_catalog_import_preview catalog_import
                WHERE catalog_import.dataset_id = dataset.id
                  AND catalog_import.status = 'draft'
                  AND catalog_import.expires_at > CURRENT_TIMESTAMP
            ) AS draft_catalog_imports,
            CASE WHEN dataset.status = 'syncing' THEN 1::BIGINT ELSE 0::BIGINT END
                AS running_metadata_syncs,
            (
                SELECT COUNT(DISTINCT version_ref.agent_id)
                FROM (
                    SELECT version_fact.agent_id,
                           version_fact.runtime_config_json AS config_json
                    FROM agent_definition_version version_fact
                    JOIN agent_definition agent
                      ON agent.id = version_fact.agent_id
                     AND agent.del_flag = '0'
                     AND agent.status = 'active'
                    WHERE version_fact.status = 'published'
                    UNION ALL
                    SELECT version_fact.agent_id,
                           tool_binding.config_json
                    FROM agent_definition_version version_fact
                    JOIN agent_definition agent
                      ON agent.id = version_fact.agent_id
                     AND agent.del_flag = '0'
                     AND agent.status = 'active'
                    JOIN agent_agent_version_tool tool_binding
                      ON tool_binding.agent_version_id = version_fact.id
                    WHERE version_fact.status = 'published'
                ) version_ref
                WHERE jsonb_typeof(
                          COALESCE(version_ref.config_json, '{}'::jsonb) -> 'datasetIds'
                      ) = 'array'
                  AND EXISTS (
                      SELECT 1
                      FROM jsonb_array_elements(
                          CASE
                              WHEN jsonb_typeof(
                                  COALESCE(version_ref.config_json, '{}'::jsonb) -> 'datasetIds'
                              ) = 'array'
                              THEN COALESCE(version_ref.config_json, '{}'::jsonb) -> 'datasetIds'
                              ELSE '[]'::jsonb
                          END
                      ) dataset_ref(value)
                      WHERE dataset_ref.value #>> '{}' IN (dataset.id::text, dataset.dataset_key)
                  )
            ) AS active_agent_dataset_bindings,
            (
                SELECT COUNT(DISTINCT entry.id)
                FROM iam_permission_profile_entry entry
                JOIN iam_permission_profile profile
                  ON profile.id = entry.profile_id
                 AND profile.del_flag = '0'
                 AND profile.status = 'published'
                WHERE entry.resource_type = 'dataset'
                  AND (
                      entry.resource_id = dataset.id
                      OR entry.resource_key = dataset.dataset_key
                  )
            ) AS active_permission_profile_references,
            (
                SELECT COUNT(*)
                FROM iam_user_permission_override override_rule
                WHERE override_rule.resource_type = 'dataset'
                  AND (
                      override_rule.resource_id = dataset.id
                      OR override_rule.resource_key = dataset.dataset_key
                  )
                  AND override_rule.status = 'active'
                  AND (override_rule.expires_at IS NULL OR override_rule.expires_at > CURRENT_TIMESTAMP)
            ) AS active_permission_override_references,
            (
                SELECT COUNT(*)
                FROM iam_temporary_grant grant_rule
                WHERE grant_rule.resource_type = 'dataset'
                  AND (
                      grant_rule.resource_id = dataset.id
                      OR grant_rule.resource_key = dataset.dataset_key
                  )
                  AND grant_rule.revoked_at IS NULL
                  AND grant_rule.expires_at > CURRENT_TIMESTAMP
            ) AS active_temporary_grant_references,
            (
                SELECT COUNT(DISTINCT binding.id)
                FROM iam_user_permission_binding binding
                WHERE binding.binding_type = 'snapshot'
                  AND binding.status = 'active'
                  AND jsonb_typeof(
                          COALESCE(binding.snapshot_json, '{}'::jsonb) -> 'rules'
                      ) = 'array'
                  AND EXISTS (
                      SELECT 1
                      FROM jsonb_array_elements(
                          CASE
                              WHEN jsonb_typeof(
                                  COALESCE(binding.snapshot_json, '{}'::jsonb) -> 'rules'
                              ) = 'array'
                              THEN COALESCE(binding.snapshot_json, '{}'::jsonb) -> 'rules'
                              ELSE '[]'::jsonb
                          END
                      ) snapshot_rule(value)
                      WHERE snapshot_rule.value ->> 'resourceType' = 'dataset'
                        AND (
                            snapshot_rule.value ->> 'resourceId' = dataset.id::text
                            OR snapshot_rule.value ->> 'resourceKey' = dataset.dataset_key
                        )
                  )
            ) AS active_permission_snapshot_references
        FROM agent_data_dataset dataset
        WHERE dataset.id = #{datasetId} AND dataset.del_flag = '0'
        """)
    DatasetDeleteImpactRow selectDatasetDeleteImpact(@Param("datasetId") Long datasetId);

    @Insert("""
        INSERT INTO agent_data_dataset (
            id, data_source_id, dataset_key, name, description, status,
            enable_row_policy, row_policy_json, schema_names_json, revision_no,
            owner_id, create_by, create_time, del_flag
        ) VALUES (
            #{id}, #{dataSourceId}, #{datasetKey}, #{name}, #{description}, #{status},
            FALSE, '{}'::jsonb, CAST(#{schemaNamesJson} AS jsonb), 1,
            #{ownerId}, #{createBy}, #{createTime}, '0'
        )
        """)
    int insertDataset(AgentDataDataset dataset);

    @Update("""
        UPDATE agent_data_dataset
        SET name = #{name}, description = #{description}, status = #{status},
            schema_names_json = CAST(#{schemaNamesJson} AS jsonb), revision_no = revision_no + 1,
            update_by = #{updateBy}, update_time = #{updateTime}
        WHERE id = #{id} AND revision_no = #{revisionNo} AND del_flag = '0'
        """)
    int updateDataset(AgentDataDataset dataset);

    @Update("""
        UPDATE agent_data_dataset
        SET last_sync_error = 'Schema 配置已变化，必须重新同步元数据',
            update_by = #{actorId}, update_time = #{now}
        WHERE id = #{datasetId} AND del_flag = '0'
        """)
    int markDatasetMetadataStale(
        @Param("datasetId") Long datasetId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_dataset
        SET del_flag = '1', revision_no = revision_no + 1,
            update_by = #{actorId}, update_time = #{now}
        WHERE id = #{datasetId} AND del_flag = '0'
        """)
    int softDeleteDataset(
        @Param("datasetId") Long datasetId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_dataset
        SET status = 'syncing', last_sync_error = NULL,
            update_by = #{actorId}, update_time = #{now}
        WHERE id = #{datasetId} AND revision_no = #{expectedRevision}
          AND del_flag = '0' AND status IN ('active', 'error')
        """)
    int markDatasetSyncing(
        @Param("datasetId") Long datasetId,
        @Param("expectedRevision") Integer expectedRevision,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    @Select("""
        SELECT id, data_source_id, revision_no, status
        FROM agent_data_dataset
        WHERE id = #{datasetId} AND del_flag = '0'
        FOR UPDATE
        """)
    AgentDataDataset lockDatasetForMetadataApply(@Param("datasetId") Long datasetId);

    @Select("""
        SELECT id, revision_no, status
        FROM agent_data_source
        WHERE id = #{sourceId} AND del_flag = '0'
        FOR UPDATE
        """)
    AgentDataSource lockSourceForMetadataApply(@Param("sourceId") Long sourceId);

    @Update("""
        UPDATE agent_data_dataset
        SET status = #{status}, last_sync_at = #{syncAt}, last_sync_error = #{error},
            update_by = #{actorId}, update_time = #{syncAt}
        WHERE id = #{datasetId} AND revision_no = #{expectedRevision}
          AND del_flag = '0' AND status = 'syncing'
        """)
    int finishDatasetSync(
        @Param("datasetId") Long datasetId,
        @Param("expectedRevision") Integer expectedRevision,
        @Param("status") String status,
        @Param("error") String error,
        @Param("actorId") Long actorId,
        @Param("syncAt") LocalDateTime syncAt
    );

    @Update("""
        UPDATE agent_data_source
        SET last_metadata_sync_at = #{syncAt}, last_metadata_sync_error = #{error},
            update_by = #{actorId}, update_time = #{syncAt}
        WHERE id = #{sourceId} AND revision_no = #{expectedRevision} AND del_flag = '0'
        """)
    int recordSourceMetadataSync(
        @Param("sourceId") Long sourceId,
        @Param("expectedRevision") Integer expectedRevision,
        @Param("error") String error,
        @Param("actorId") Long actorId,
        @Param("syncAt") LocalDateTime syncAt
    );

    @Select("""
        SELECT id, dataset_id, table_key, physical_schema, physical_name, display_name,
               description, table_type, status, synonyms_json::text AS synonyms_json,
               metadata_present, metadata_json::text AS metadata_json,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_data_table
        WHERE dataset_id = #{datasetId} AND del_flag = '0'
        ORDER BY physical_schema, physical_name
        """)
    List<AgentDataTable> selectTables(@Param("datasetId") Long datasetId);

    @Select("""
        SELECT id, dataset_id, table_key, physical_schema, physical_name, display_name,
               description, table_type, status, synonyms_json::text AS synonyms_json,
               metadata_present, metadata_json::text AS metadata_json,
               create_by, create_time, update_by, update_time, del_flag
        FROM agent_data_table
        WHERE id = #{tableId} AND del_flag = '0'
        """)
    AgentDataTable selectTable(@Param("tableId") Long tableId);

    @Insert("""
        INSERT INTO agent_data_table (
            id, dataset_id, table_key, physical_schema, physical_name, display_name,
            description, table_type, status, synonyms_json, metadata_present, metadata_json,
            create_by, create_time, del_flag
        ) VALUES (
            #{id}, #{datasetId}, #{tableKey}, #{physicalSchema}, #{physicalName}, #{displayName},
            #{description}, #{tableType}, #{status}, CAST(#{synonymsJson} AS jsonb),
            TRUE, CAST(#{metadataJson} AS jsonb),
            #{createBy}, #{createTime}, '0'
        )
        """)
    int insertTable(AgentDataTable table);

    @Update("""
        UPDATE agent_data_table
        SET table_type = #{tableType}, metadata_json = CAST(#{metadataJson} AS jsonb),
            status = CASE WHEN metadata_present = FALSE THEN 'active' ELSE status END,
            metadata_present = TRUE, update_by = #{updateBy}, update_time = #{updateTime}
        WHERE id = #{id} AND dataset_id = #{datasetId} AND del_flag = '0'
        """)
    int refreshTable(AgentDataTable table);

    @Update("""
        <script>
        UPDATE agent_data_table SET status = 'inactive', metadata_present = FALSE,
            update_by = #{actorId}, update_time = #{now}
        WHERE dataset_id = #{datasetId} AND del_flag = '0'
        <if test="activeIds != null and !activeIds.isEmpty()">
          AND id NOT IN
          <foreach collection="activeIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
        </script>
        """)
    int deactivateMissingTables(
        @Param("datasetId") Long datasetId,
        @Param("activeIds") List<Long> activeIds,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_table
        SET display_name = #{displayName}, description = #{description}, status = #{status},
            update_by = #{actorId}, update_time = #{now}
        WHERE id = #{tableId} AND dataset_id = #{datasetId} AND del_flag = '0'
        """)
    int updateTableGovernance(
        @Param("datasetId") Long datasetId,
        @Param("tableId") Long tableId,
        @Param("displayName") String displayName,
        @Param("description") String description,
        @Param("status") String status,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    @Select("""
        SELECT c.id, c.table_id, c.column_key, c.physical_name, c.display_name, c.data_type,
               c.description, c.is_primary, c.is_sensitive, c.enum_json::text AS enum_json,
               c.synonyms_json::text AS synonyms_json, c.sample_values_json::text AS sample_values_json,
               c.status, c.metadata_present,
               c.created_at, c.updated_at
        FROM agent_data_column c
        JOIN agent_data_table t ON t.id = c.table_id
        WHERE t.dataset_id = #{datasetId} AND t.del_flag = '0'
        ORDER BY t.physical_schema, t.physical_name, c.id
        """)
    List<AgentDataColumn> selectColumns(@Param("datasetId") Long datasetId);

    @Select("""
        SELECT id, table_id, column_key, physical_name, display_name, data_type,
               description, is_primary, is_sensitive, enum_json::text AS enum_json,
               synonyms_json::text AS synonyms_json, sample_values_json::text AS sample_values_json,
               status, metadata_present, created_at, updated_at
        FROM agent_data_column
        WHERE id = #{columnId}
        """)
    AgentDataColumn selectColumn(@Param("columnId") Long columnId);

    @Insert("""
        INSERT INTO agent_data_column (
            id, table_id, column_key, physical_name, display_name, data_type,
            description, is_primary, is_sensitive, enum_json, synonyms_json, sample_values_json,
            status, metadata_present, created_at
        ) VALUES (
            #{id}, #{tableId}, #{columnKey}, #{physicalName}, #{displayName}, #{dataType},
            #{description}, #{isPrimary}, #{isSensitive}, CAST(#{enumJson} AS jsonb),
            CAST(#{synonymsJson} AS jsonb), CAST(#{sampleValuesJson} AS jsonb),
            #{status}, TRUE, #{createdAt}
        )
        """)
    int insertColumn(AgentDataColumn column);

    @Update("""
        UPDATE agent_data_column
        SET data_type = #{dataType}, is_primary = #{isPrimary},
            status = CASE WHEN metadata_present = FALSE THEN 'active' ELSE status END,
            metadata_present = TRUE, updated_at = #{updatedAt}
        WHERE id = #{id} AND table_id = #{tableId}
        """)
    int refreshColumn(AgentDataColumn column);

    @Update("""
        <script>
        UPDATE agent_data_column SET status = 'inactive', metadata_present = FALSE, updated_at = #{now}
        WHERE table_id = #{tableId}
        <if test="activeIds != null and !activeIds.isEmpty()">
          AND id NOT IN
          <foreach collection="activeIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
        </script>
        """)
    int deactivateMissingColumns(
        @Param("tableId") Long tableId,
        @Param("activeIds") List<Long> activeIds,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE agent_data_column c
        SET display_name = #{displayName}, description = #{description},
            is_sensitive = #{sensitive}, status = #{status}, updated_at = #{now}
        FROM agent_data_table t
        WHERE c.id = #{columnId} AND c.table_id = t.id
          AND t.dataset_id = #{datasetId} AND t.del_flag = '0'
        """)
    int updateColumnGovernance(
        @Param("datasetId") Long datasetId,
        @Param("columnId") Long columnId,
        @Param("displayName") String displayName,
        @Param("description") String description,
        @Param("sensitive") boolean sensitive,
        @Param("status") String status,
        @Param("now") LocalDateTime now
    );

    @Select("""
        SELECT COUNT(*)
        FROM agent_task_resource
        WHERE task_id = #{taskId} AND resource_type = 'dataset'
          AND resource_id = #{datasetId} AND permission IN ('query', 'admin')
        """)
    int countTaskDatasetQueryBinding(
        @Param("taskId") Long taskId,
        @Param("datasetId") Long datasetId
    );

    @Insert("""
        INSERT INTO agent_data_query (
            id, task_id, run_id, conversation_id, trace_id, data_source_id, dataset_id,
            data_source_revision, dataset_revision, user_query, sql_text, sql_hash,
            status, created_by, created_at
        ) VALUES (
            #{id}, #{taskId}, #{runId}, #{conversationId}, #{traceId}, #{dataSourceId}, #{datasetId},
            #{dataSourceRevision}, #{datasetRevision}, #{userQuery}, #{sqlText}, #{sqlHash},
            #{status}, #{createdBy}, #{createdAt}
        )
        """)
    int insertQuery(AgentDataQuery query);

    @Select("""
        SELECT id, task_id, run_id, conversation_id, trace_id, data_source_id, dataset_id,
               data_source_revision, dataset_revision, user_query,
               sql_plan_json::text AS sql_plan_json, sql_text, sql_hash,
               permission_summary_json::text AS permission_summary_json,
               row_count, result_bytes, result_truncated, status, error_summary,
               started_at, finished_at, created_by, created_at
        FROM agent_data_query
        WHERE id = #{queryId}
        """)
    AgentDataQuery selectQuery(@Param("queryId") Long queryId);

    @Select("""
        SELECT id, task_id, run_id, conversation_id, trace_id, data_source_id, dataset_id,
               data_source_revision, dataset_revision, user_query,
               sql_plan_json::text AS sql_plan_json, sql_text, sql_hash,
               permission_summary_json::text AS permission_summary_json,
               row_count, result_bytes, result_truncated, status, error_summary,
               started_at, finished_at, created_by, created_at
        FROM agent_data_query
        WHERE trace_id = #{traceId} AND created_by = #{userId} AND status = 'succeeded'
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """)
    AgentDataQuery selectLatestSucceededQueryByTrace(
        @Param("traceId") String traceId,
        @Param("userId") Long userId
    );

    @Select("""
        SELECT q.id, q.task_id, q.run_id, q.conversation_id, q.trace_id,
               q.data_source_id, q.dataset_id, q.data_source_revision, q.dataset_revision,
               q.user_query, q.sql_plan_json::text AS sql_plan_json, q.sql_text, q.sql_hash,
               q.permission_summary_json::text AS permission_summary_json,
               q.row_count, q.result_bytes, q.result_truncated, q.status, q.error_summary,
               q.started_at, q.finished_at, q.created_by, q.created_at
        FROM agent_chatbi_federated_run r
        INNER JOIN agent_chatbi_federated_source s ON s.run_id = r.id
        INNER JOIN agent_data_query q ON q.id = s.query_id
        WHERE r.result_query_id = #{queryId}
          AND r.status = 'succeeded' AND s.status = 'succeeded' AND q.status = 'succeeded'
        ORDER BY s.sequence_no, s.id
        """)
    List<AgentDataQuery> selectFederatedSourceQueries(@Param("queryId") Long queryId);

    @Select("""
        SELECT query_id, columns_json::text AS columns_json, rows_json::text AS rows_json,
               content_hash, row_count, result_bytes, created_by, created_at
        FROM agent_data_query_result
        WHERE query_id = #{queryId}
        """)
    DataQueryStoredResultRow selectQueryResult(@Param("queryId") Long queryId);

    @Update("""
        UPDATE agent_data_query
        SET sql_text = #{sqlText}, sql_hash = #{sqlHash},
            sql_plan_json = CAST(#{sqlPlanJson} AS jsonb),
            permission_summary_json = CAST(#{permissionSummaryJson} AS jsonb),
            status = 'running', started_at = #{startedAt}
        WHERE id = #{id} AND status = 'planning'
        """)
    int markQueryRunning(AgentDataQuery query);

    @Update("""
        WITH stored AS (
            INSERT INTO agent_data_query_result (
                query_id, columns_json, rows_json, content_hash, row_count,
                result_bytes, created_by, created_at
            ) VALUES (
                #{query.id}, CAST(#{columnsJson} AS jsonb), CAST(#{rowsJson} AS jsonb),
                #{contentHash}, #{query.rowCount}, #{query.resultBytes},
                #{query.createdBy}, #{query.finishedAt}
            ) ON CONFLICT DO NOTHING
            RETURNING query_id
        )
        UPDATE agent_data_query
        SET status = 'succeeded', row_count = #{query.rowCount},
            result_bytes = #{query.resultBytes}, result_truncated = #{query.resultTruncated},
            finished_at = #{query.finishedAt}, error_summary = NULL
        WHERE id = #{query.id} AND status = 'running'
          AND EXISTS (SELECT 1 FROM stored WHERE stored.query_id = agent_data_query.id)
        """)
    int completeQueryWithResult(
        @Param("query") AgentDataQuery query,
        @Param("columnsJson") String columnsJson,
        @Param("rowsJson") String rowsJson,
        @Param("contentHash") String contentHash
    );

    @Update("""
        UPDATE agent_data_query
        SET status = #{status}, error_summary = #{errorSummary}, finished_at = #{finishedAt}
        WHERE id = #{id} AND status IN ('planning', 'running')
        """)
    int markQueryFailed(AgentDataQuery query);
}
