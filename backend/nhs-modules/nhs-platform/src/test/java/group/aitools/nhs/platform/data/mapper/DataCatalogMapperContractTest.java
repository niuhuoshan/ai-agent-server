package group.aitools.nhs.platform.data.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class DataCatalogMapperContractTest {

    @Test
    void dataSourceUpdatePersistsTheRequestedDatabaseType() throws Exception {
        Update update = DataCatalogMapper.class
            .getMethod("updateSource", AgentDataSource.class)
            .getAnnotation(Update.class);

        String sql = String.join(" ", update.value())
            .replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("db_type = #{dbtype}"));
    }

    @Test
    void datasetDeleteImpactUsesOnlyBlockingStatesAndLocksBeforeDelete() throws Exception {
        Select impact = DataCatalogMapper.class
            .getMethod("selectDatasetDeleteImpact", Long.class)
            .getAnnotation(Select.class);
        Select lock = DataCatalogMapper.class
            .getMethod("lockDatasetForDelete", Long.class)
            .getAnnotation(Select.class);

        String impactSql = sql(impact.value());
        String lockSql = sql(lock.value());

        assertTrue(impactSql.contains("join agent_task task on task.id = resource.task_id"));
        assertTrue(impactSql.contains("task.status not in ('archived', 'cancelled')"));
        assertTrue(impactSql.contains("report.status <> 'archived'"));
        assertTrue(impactSql.contains("query_fact.status in ('planning', 'approved', 'running')"));
        assertTrue(impactSql.contains("profile_job.status in ('queued', 'running')"));
        assertTrue(impactSql.contains("smart_import.status = 'draft'"));
        assertTrue(impactSql.contains("catalog_import.status = 'draft'"));
        assertTrue(impactSql.contains("expires_at > current_timestamp"));
        assertTrue(impactSql.contains("dataset.status = 'syncing'"));
        assertTrue(impactSql.contains("active_agent_dataset_bindings"));
        assertTrue(impactSql.contains("join agent_definition agent"));
        assertTrue(impactSql.contains("agent.del_flag = '0'"));
        assertTrue(impactSql.contains("agent.status = 'active'"));
        assertTrue(impactSql.contains("version_fact.status = 'published'"));
        assertTrue(impactSql.contains("agent_agent_version_tool"));
        assertTrue(impactSql.contains("version_fact.runtime_config_json"));
        assertTrue(impactSql.contains("tool_binding.config_json"));
        assertTrue(impactSql.contains("jsonb_array_elements"));
        assertTrue(impactSql.contains("dataset_ref.value #>> '{}' in (dataset.id::text, dataset.dataset_key)"));
        assertTrue(impactSql.contains("iam_permission_profile_entry"));
        assertTrue(impactSql.contains("profile.status = 'published'"));
        assertTrue(impactSql.contains("entry.resource_key = dataset.dataset_key"));
        assertTrue(impactSql.contains("iam_user_permission_override"));
        assertTrue(impactSql.contains("override_rule.resource_key = dataset.dataset_key"));
        assertTrue(impactSql.contains("override_rule.status = 'active'"));
        assertTrue(impactSql.contains("iam_temporary_grant"));
        assertTrue(impactSql.contains("grant_rule.resource_key = dataset.dataset_key"));
        assertTrue(impactSql.contains("grant_rule.revoked_at is null"));
        assertTrue(impactSql.contains("iam_user_permission_binding"));
        assertTrue(impactSql.contains("binding.binding_type = 'snapshot'"));
        assertTrue(impactSql.contains("binding.status = 'active'"));
        assertTrue(impactSql.contains("snapshot_rule.value ->> 'resourcekey' = dataset.dataset_key"));
        assertTrue(lockSql.endsWith("for update"));
    }

    private String sql(String[] fragments) {
        return String.join(" ", fragments)
            .replaceAll("\\s+", " ")
            .strip()
            .toLowerCase(Locale.ROOT);
    }
}
