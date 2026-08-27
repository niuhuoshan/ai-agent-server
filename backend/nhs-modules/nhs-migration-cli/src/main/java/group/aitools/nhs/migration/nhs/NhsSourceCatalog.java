package group.aitools.nhs.migration.nhs;

import java.util.List;
import java.util.Set;

/**
 * 表示Nhs数据源目录相关的领域对象。
 */
final class NhsSourceCatalog {

    static final List<Entity> ENTITIES = List.of(
        entity("users", "ai_agent_users", "sys_user", "migrate", "id", "user_name", "status"),
        entity("roles", "ai_agent_roles", "migration evidence", "map", "id", "code", "name"),
        entity("user_roles", "ai_agent_user_role_relations", "sys_user_role", "map", "id", "user_id", "role_id"),
        entity("resource_permissions", "ai_agent_resource_permissions", "iam_user_permission_override", "map", "id", "resource_type", "resource_id"),
        entity("models", "ai_models", "agent_model", "migrate", "id", "name", "model_id", "provider", "type"),
        entity("agents", "ai_agents", "agent_definition", "migrate", "id", "name", "display_name"),
        entity("agent_versions", "ai_agent_versions", "agent_definition_version", "migrate", "id", "agent_id", "version_number", "system_prompt"),
        entity("api_tools", "sys_api_tools", "agent_tool", "migrate_disabled", "id", "name", "method", "url_template"),
        entity("mcp_servers", "sys_mcp_servers", "agent_connector", "migrate_testing", "id", "server_name", "sse_url"),
        entity("mcp_tools", "sys_mcp_tool_cache", "agent_tool", "migrate_disabled", "id"),
        entity("skill_publications", "skill_publications", "agent_skill", "migrate", "id", "name", "status"),
        entity("skill_versions", "skill_publication_versions", "agent_skill_version", "metadata_only", "id", "publication_id", "version_number", "content_sha256"),
        entity("knowledge_bases", "knowledge_base_metadata", "agent_knowledge_base", "migrate", "id", "ragflow_dataset_id", "name"),
        entity("data_sources", "meta_db_connection_configs", "agent_data_source", "migrate_testing", "id", "name", "db_type", "host", "port"),
        entity("datasets", "meta_datasets", "agent_data_dataset", "migrate", "id", "name", "status"),
        entity("tables", "meta_tables", "agent_data_table", "migrate", "id", "dataset_id", "physical_name"),
        entity("columns", "meta_columns", "agent_data_column", "migrate", "id", "table_id", "physical_name"),
        entity("metrics", "meta_metrics", "agent_data_metric", "migrate", "id", "dataset_id", "name"),
        entity("relations", "meta_relationships", "agent_data_relation", "migrate", "id", "source_table_id", "target_table_id"),
        entity("scheduled_tasks", "ai_agent_scheduled_tasks", "agent_task + agent_automation_trigger", "migrate_paused", "id", "name", "user_id", "agent_id", "cron_expr", "prompt"),
        entity("execution_history", "ai_agent_execution_history", "agent_conversation + agent_conversation_message + archive", "archive", "id", "trace_id", "agent_id"),
        entity("execution_traces", "ai_agent_execution_traces", "agent_legacy_execution_archive", "archive", "id", "trace_id", "step_number", "event_type"),
        entity("access_logs", "ai_agent_access_logs", "agent_audit_event", "archive_redacted", "id", "endpoint", "method", "status_code"),
        entity("saved_reports", "portal_saved_reports", "agent_report", "migrate_disabled", "id", "title", "sql_content", "owner_user_id"),
        entity("saved_report_subscriptions", "portal_saved_report_subscriptions", "agent_report_subscription", "migrate_paused", "id", "report_id", "cron_expr"),
        entity("notifications", "portal_notifications", "agent_notification", "archive_optional", "id", "user_id", "title"),
        entity("system_configs", "system_configs", "deployment configuration", "manual_review", "config_key", "config_value"),
        entity("memory_configs", "memory_service_configs", "deployment configuration", "manual_review", "config_key", "config_value")
    );

    /**
     * 创建 {@code NhsSourceCatalog} 实例并初始化所需依赖。
     */
    private NhsSourceCatalog() {
    }

    /**
     * 处理{@code entity}并返回对应结果。
     *
     * @param type 业务类型
     * @param sourceTable 数据源Table参数
     * @param target {@code target}参数
     * @param disposition {@code disposition}参数
     * @param requiredColumns {@code requiredColumns}参数
     * @return 处理结果
     */
    private static Entity entity(
        String type,
        String sourceTable,
        String target,
        String disposition,
        String... requiredColumns
    ) {
        return new Entity(type, sourceTable, target, disposition, Set.of(requiredColumns));
    }

    /**
     * 封装{@code Entity}相关的不可变数据。
     */
    record Entity(
        String type,
        String sourceTable,
        String target,
        String disposition,
        Set<String> requiredColumns
    ) {
    }
}
