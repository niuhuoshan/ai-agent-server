package group.aitools.nhs.migration.nhs;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 表示Nhs迁移Engine相关的领域对象。
 */
final class NhsMigrationEngine {

    private static final Pattern SAFE_KEY = Pattern.compile("[a-z][a-z0-9._-]{0,127}");

    private final Connection source;
    private final String sourceSchema;
    private final MigrationTarget target;
    private final JsonCodec json;
    private final MigrationReport report;
    private final long runId;
    private final long operatorId;
    private final boolean strict;
    private final String migrationType;
    private final Map<String, Long> placeholderSources = new HashMap<>();
    private final Map<String, Long> userAliases = new HashMap<>();
    private final Map<String, Long> conversations = new HashMap<>();
    private final Map<String, Integer> conversationSequences = new HashMap<>();

    /**
     * 创建 {@code NhsMigrationEngine} 实例并初始化所需依赖。
     *
     * @param source 数据源参数
     * @param sourceSchema 数据源Schema参数
     * @param target {@code target}参数
     * @param json {@code json}参数
     * @param report 报表参数
     * @param runId 资源标识
     * @param operatorId 资源标识
     * @param strict {@code strict}参数
     * @param migrationType 业务类型
     */
    NhsMigrationEngine(
        Connection source,
        String sourceSchema,
        MigrationTarget target,
        JsonCodec json,
        MigrationReport report,
        long runId,
        long operatorId,
        boolean strict,
        String migrationType
    ) {
        this.source = source;
        this.sourceSchema = sourceSchema;
        this.target = target;
        this.json = json;
        this.report = report;
        this.runId = runId;
        this.operatorId = operatorId;
        this.strict = strict;
        this.migrationType = migrationType;
    }

    /**
     * 处理{@code migrate}相关逻辑。
     *
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    void migrate() throws SQLException {
        migrateEntity("users", "ai_agent_users", "user", this::user);
        migrateEntity("models", "ai_models", "model", this::model);
        migrateEntity("mcp_servers", "sys_mcp_servers", "connector", this::mcpServer);
        migrateEntity("api_tools", "sys_api_tools", "tool", this::apiTool);
        migrateEntity("mcp_tools", "sys_mcp_tool_cache", "tool", this::mcpTool);
        migrateEntity("skill_publications", "skill_publications", "skill", this::skill);
        migrateEntity("skill_versions", "skill_publication_versions", "skill_version", this::skillVersion);
        migrateEntity("knowledge_bases", "knowledge_base_metadata", "knowledge_base", this::knowledgeBase);
        migrateEntity("data_sources", "meta_db_connection_configs", "data_source", this::dataSource);
        migrateEntity("datasets", "meta_datasets", "dataset", this::dataset);
        migrateEntity("tables", "meta_tables", "data_table", this::dataTable);
        migrateEntity("columns", "meta_columns", "data_column", this::dataColumn);
        migrateEntity("metrics", "meta_metrics", "data_metric", this::dataMetric);
        migrateEntity("relations", "meta_relationships", "data_relation", this::dataRelation);
        migrateEntity("agents", "ai_agents", "agent", this::agent);
        migrateEntity("agent_versions", "ai_agent_versions", "agent_version", this::agentVersion);
        migrateEntity("resource_permissions", "ai_agent_resource_permissions", "permission_override", this::resourcePermission);
        migrateEntity("saved_reports", "portal_saved_reports", "report", this::savedReport);
        migrateEntity("scheduled_tasks", "ai_agent_scheduled_tasks", "automation_trigger", this::scheduledTask);
        migrateEntity("execution_history", "ai_agent_execution_history", "legacy_execution", this::executionHistory);
        migrateEntity("execution_traces", "ai_agent_execution_traces", "legacy_execution", this::executionTrace);
        migrateEntity("access_logs", "ai_agent_access_logs", "audit_event", this::accessLog);
    }

    /**
     * 处理{@code migrateEntity}相关逻辑。
     *
     * @param entityType 业务类型
     * @param sourceTable 数据源Table参数
     * @param targetType 业务类型
     * @param handler {@code handler}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private void migrateEntity(
        String entityType,
        String sourceTable,
        String targetType,
        Handler handler
    ) throws SQLException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (!JdbcSupport.tableExists(source, sourceSchema, sourceTable)) {
            MigrationReport.EntityResult result = new MigrationReport.EntityResult(
                entityType, phase(entityType), "skipped", 0, 0, 0, 0, 0, 0,
                null, null, Map.of("reason", "source table does not exist", "sourceTable", sourceTable)
            );
            report.add(result);
            target.persistResult(runId, result);
            target.commit();
            return;
        }

        Set<String> columns = JdbcSupport.columns(source, sourceSchema, sourceTable);
        List<Map<String, Object>> rows = JdbcSupport.rows(
            source,
            JdbcSupport.qualified(sourceSchema, sourceTable),
            columns.contains("id") ? "id" : null
        );
        List<String> sourceHashes = new ArrayList<>(rows.size());
        List<String> targetHashes = new ArrayList<>(rows.size());
        Counters counters = new Counters("incremental".equals(migrationType) ? 0 : rows.size());

        for (int index = 0; index < rows.size(); index++) {
            RowValues row = new RowValues(rows.get(index));
            String sourceId;
            try {
                sourceId = row.id();
            } catch (RuntimeException exception) {
                sourceId = "row-" + index;
            }
            Map<String, Object> sanitized = json.sanitizeRow(row.map());
            String sourceHash = json.sha256(sanitized);
            MigrationTarget.PriorMapping prior = target.priorMapping(
                entityType, sourceId, targetType
            );
            if ("incremental".equals(migrationType)
                && prior != null && sourceHash.equals(prior.sourceHash())) {
                continue;
            }
            if ("incremental".equals(migrationType)) {
                counters.source++;
            }
            sourceHashes.add(sourceHash);
            Savepoint savepoint = target.savepoint("migrate_" + index);
            try {
                Outcome outcome;
                if (prior != null && sourceHash.equals(prior.sourceHash())) {
                    outcome = "skipped".equals(prior.status())
                        ? Outcome.skip(prior.reason())
                        : new Outcome(targetType, prior.targetId(), false, false, null, prior.targetHash());
                } else if (prior != null && "mapped".equals(prior.status())) {
                    throw new IllegalStateException(
                        "source row changed after an earlier migration; use a reviewed incremental mapping instead of silently overwriting target data"
                    );
                } else {
                    outcome = handler.apply(row, sourceHash);
                }
                if (outcome.skipped()) {
                    counters.skipped++;
                    target.mapping(
                        runId, entityType, sourceId, targetType, null, sourceHash, null,
                        "skipped", outcome.reason()
                    );
                    if (outcome.reason() != null) {
                        issue("warning", "ROW_SKIPPED", entityType, sourceId, outcome.reason());
                    }
                } else {
                    counters.mapped++;
                    if (outcome.inserted()) {
                        counters.inserted++;
                    } else {
                        counters.reused++;
                    }
                    targetHashes.add(outcome.targetHash());
                    target.mapping(
                        runId, entityType, sourceId, outcome.targetType(), outcome.targetId(),
                        sourceHash, outcome.targetHash(), "mapped", null
                    );
                }
                target.release(savepoint);
            } catch (Exception exception) {
                target.rollback(savepoint);
                counters.failed++;
                String message = safeError(exception);
                target.mapping(
                    runId, entityType, sourceId, targetType, null, sourceHash, null,
                    "failed", message
                );
                issue("error", "ROW_MIGRATION_FAILED", entityType, sourceId, message);
            }
        }

        String status = counters.failed == 0 ? "passed" : "failed";
        MigrationReport.EntityResult result = new MigrationReport.EntityResult(
            entityType,
            phase(entityType),
            status,
            counters.source,
            counters.mapped,
            counters.inserted,
            counters.reused,
            counters.skipped,
            counters.failed,
            json.aggregateHash(sourceHashes),
            json.aggregateHash(targetHashes),
            Map.of("sourceTable", sourceTable, "strict", strict, "migrationType", migrationType)
        );
        report.add(result);
        target.persistResult(runId, result);
        target.checkpoint(runId, sourceSchema, entityType, result.sourceHash());
        target.commit();
    }

    /**
     * 处理用户并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome user(RowValues row, String sourceHash) throws SQLException {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String sourceName = required(row.text("user_name"), "user_name");
        String userName = boundedKey(sourceName, 30, sourceHash.substring(0, 8));
        Long existing = target.findId("sys_user", "user_id", "user_name", userName);
        if (existing != null) {
            userAliases.put(row.id(), existing);
            userAliases.put(sourceName, existing);
            return reused("user", existing, Map.of("userName", userName, "existing", true));
        }
        long id = target.nextId("sys_user", "user_id");
        String nickname = truncate(row.text("real_name", sourceName), 30);
        target.insert("sys_user", values(
            "user_id", id,
            "dept_id", null,
            "user_name", userName,
            "nick_name", nickname,
            "user_type", "sys_user",
            "email", "",
            "phone_number", "",
            "gender", "2",
            "password", "",
            "status", "1",
            "del_flag", "0",
            "create_by", operatorId,
            "create_time", row.instant("created_at") == null ? Instant.now() : row.instant("created_at"),
            "remark", truncate("Nhs migrated account; password/API key not copied; enable only after credential reset. "
                + Objects.toString(row.text("remark"), ""), 500)
        ));
        Long commonRole = target.findId("sys_role", "role_id", "role_key", "common");
        if (commonRole != null) {
            target.insert("sys_user_role", values("user_id", id, "role_id", commonRole));
        }
        userAliases.put(row.id(), id);
        userAliases.put(sourceName, id);
        return inserted("user", id, Map.of(
            "userName", userName,
            "status", "disabled_pending_password_reset",
            "sourceRole", row.text("role", "user")
        ));
    }

    /**
     * 处理模型并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome model(RowValues row, String sourceHash) throws SQLException {
        String sourceId = row.id();
        String key = key("nhs.model." + sourceId, sourceHash);
        Long existing = target.findId("agent_model", "id", "model_key", key);
        if (existing != null) {
            return reused("model", existing, Map.of("modelKey", key));
        }
        String provider = "openai".equalsIgnoreCase(row.text("provider")) ? "openai" : "openai-compatible";
        String modelType = switch (row.text("type", "llm").toLowerCase(Locale.ROOT)) {
            case "embedding", "embeddings" -> "embedding";
            case "multimodal", "vision" -> "multimodal";
            case "rerank", "reranker" -> "rerank";
            default -> "chat";
        };
        long id = target.nextId("agent_model", "id");
        Map<String, Object> reasoning = json.linkedMap(
            "thinkingEnable", row.bool("thinking_enable", false),
            "thinkingOnly", row.bool("thinking_only", false),
            "allowDisableThinking", row.bool("allow_disable_thinking", true),
            "reasoningEffort", row.text("reasoning_effort")
        );
        target.insert("agent_model", values(
            "id", id,
            "model_key", key,
            "display_name", truncate(row.text("name", row.text("model_id", "Migrated model")), 128),
            "provider_type", provider,
            "model_name", truncate(required(row.text("model_id"), "model_id"), 255),
            "model_type", modelType,
            "endpoint_url", truncate(row.text("api_base_url"), 512),
            "credential_ref", null,
            "context_size", positiveOrNull(row.integer("context_size", 0)),
            "max_output_tokens", positiveOrNull(row.integer("max_output_tokens", 0)),
            "reasoning_config_json", new MigrationTarget.JsonValue(reasoning),
            "status", "testing",
            "capability_json", new MigrationTarget.JsonValue(Map.of("migrated", true)),
            "create_by", operatorId,
            "create_time", time(row, "created_at"),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(Map.of(
                "legacySource", "nhs",
                "legacyId", sourceId,
                "credentialRotationRequired", true,
                "sourceActive", row.bool("is_active", true)
            ))
        ));
        return inserted("model", id, Map.of(
            "modelKey", key,
            "provider", provider,
            "modelType", modelType,
            "credentialStatus", "manual-entry-required",
            "status", "testing"
        ));
    }

    /**
     * 处理{@code mcpServer}并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome mcpServer(RowValues row, String sourceHash) throws SQLException {
        String sourceId = row.id();
        String connectorKey = key("nhs.mcp." + sourceId, sourceHash);
        Long existing = target.findId("agent_connector", "id", "connector_key", connectorKey);
        if (existing != null) {
            return reused("connector", existing, Map.of("connectorKey", connectorKey));
        }
        boolean hasAuth = row.text("auth_headers") != null;
        String credentialRef = hasAuth ? "env:NHS_MCP_" + environmentName(sourceId) + "_AUTH" : null;
        long id = target.nextId("agent_connector", "id");
        target.insert("agent_connector", values(
            "id", id,
            "connector_key", connectorKey,
            "name", truncate(row.text("server_name", "Migrated MCP"), 128),
            "provider_type", "mcp",
            "endpoint_url", truncate(required(row.text("sse_url"), "sse_url"), 1024),
            "credential_ref", credentialRef,
            "config_json", new MigrationTarget.JsonValue(json.linkedMap(
                "transport", "sse",
                "authType", hasAuth ? "header" : "none",
                "authHeader", hasAuth ? "Authorization" : null,
                "connectTimeoutMs", 5000,
                "requestTimeoutMs", 15000
            )),
            "status", "testing",
            "create_by", operatorId,
            "create_time", time(row, "created_at"),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(Map.of(
                "legacySource", "nhs", "legacyId", sourceId, "credentialRotationRequired", hasAuth
            ))
        ));
        return inserted("connector", id, Map.of(
            "connectorKey", connectorKey, "status", "testing", "credentialRef", credentialRef == null ? "none" : credentialRef
        ));
    }

    /**
     * 处理接口工具并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome apiTool(RowValues row, String sourceHash) throws SQLException {
        String sourceId = row.id();
        String toolKey = key("nhs.api." + sourceId, sourceHash);
        Long existing = target.findId("agent_tool", "id", "tool_key", toolKey);
        if (existing != null) {
            return reused("tool", existing, Map.of("toolKey", toolKey));
        }
        long id = target.nextId("agent_tool", "id");
        Object schema = json.parseLenient(row.raw("parameter_schema"));
        target.insert("agent_tool", values(
            "id", id,
            "tool_key", toolKey,
            "name", truncate(row.text("name", "Migrated API tool"), 128),
            "description", row.text("description"),
            "connector_id", null,
            "tool_type", "api",
            "risk_level", "R2",
            "parameter_schema_json", new MigrationTarget.JsonValue(schema == null ? Map.of("type", "object") : schema),
            "execution_policy_json", new MigrationTarget.JsonValue(Map.of(
                "defaultDecision", "approval_required", "migratedReviewRequired", true
            )),
            "external_name", truncate(row.text("name"), 255),
            "status", "disabled",
            "version_no", 1,
            "create_by", operatorId,
            "create_time", time(row, "created_at"),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(json.linkedMap(
                "legacySource", "nhs",
                "legacyId", sourceId,
                "method", row.text("method", "GET"),
                "urlTemplate", row.text("url_template"),
                "credentialRef", row.text("headers") == null ? null : "env:NHS_API_TOOL_" + environmentName(sourceId) + "_HEADERS",
                "activationRequired", true
            ))
        ));
        return inserted("tool", id, Map.of("toolKey", toolKey, "status", "disabled", "riskLevel", "R2"));
    }

    /**
     * 处理mcp工具并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome mcpTool(RowValues row, String sourceHash) throws SQLException {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String sourceId = row.id();
        String serverSourceId = first(row, "server_id", "mcp_server_id");
        Long connectorId = serverSourceId == null ? null : target.mappedId("mcp_servers", serverSourceId, "connector");
        if (connectorId == null) {
            return Outcome.skip("MCP server mapping is missing");
        }
        String externalName = first(row, "tool_name", "name", "external_name");
        if (externalName == null) {
            return Outcome.skip("MCP tool has no name");
        }
        String toolKey = key("nhs.mcp-tool." + sourceId, sourceHash);
        Long existing = target.findId("agent_tool", "id", "tool_key", toolKey);
        if (existing != null) {
            return reused("tool", existing, Map.of("toolKey", toolKey));
        }
        long id = target.nextId("agent_tool", "id");
        Object schema = json.parseLenient(firstRaw(row, "input_schema", "parameter_schema", "schema_json"));
        target.insert("agent_tool", values(
            "id", id,
            "tool_key", toolKey,
            "name", truncate(externalName, 128),
            "description", row.text("description"),
            "connector_id", connectorId,
            "tool_type", "mcp",
            "risk_level", "R2",
            "parameter_schema_json", new MigrationTarget.JsonValue(schema == null ? Map.of("type", "object") : schema),
            "execution_policy_json", new MigrationTarget.JsonValue(Map.of(
                "defaultDecision", "approval_required", "migratedReviewRequired", true
            )),
            "external_name", truncate(externalName, 255),
            "status", "disabled",
            "version_no", 1,
            "create_by", operatorId,
            "create_time", time(row, "created_at"),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(Map.of(
                "legacySource", "nhs", "legacyId", sourceId, "activationRequired", true
            ))
        ));
        return inserted("tool", id, Map.of("toolKey", toolKey, "connectorId", connectorId, "status", "disabled"));
    }

    /**
     * 处理技能并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome skill(RowValues row, String sourceHash) throws SQLException {
        String sourceId = row.id();
        String skillKey = key("nhs.skill." + sourceId, sourceHash);
        Long existing = target.findId("agent_skill", "id", "skill_key", skillKey);
        if (existing != null) {
            return reused("skill", existing, Map.of("skillKey", skillKey));
        }
        Long ownerId = mappedUser(row.text("source_user_id"));
        long id = target.nextId("agent_skill", "id");
        target.insert("agent_skill", values(
            "id", id,
            "skill_key", skillKey,
            "name", truncate(row.text("name", "Migrated skill"), 128),
            "scope_type", ownerId == null ? "system" : "user",
            "scope_id", ownerId,
            "owner_id", ownerId,
            "status", "disabled",
            "create_by", operatorId,
            "create_time", time(row, "created_at"),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(json.linkedMap(
                "legacySource", "nhs",
                "legacyId", sourceId,
                "description", row.text("description"),
                "sourceStatus", row.text("status"),
                "materializationRequired", true
            ))
        ));
        return inserted("skill", id, Map.of("skillKey", skillKey, "status", "disabled", "materializationRequired", true));
    }

    /**
     * 处理技能版本并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome skillVersion(RowValues row, String sourceHash) throws SQLException {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String publicationId = required(row.text("publication_id"), "publication_id");
        Long skillId = target.mappedId("skill_publications", publicationId, "skill");
        if (skillId == null) {
            return Outcome.skip("skill publication mapping is missing");
        }
        int version = Math.max(1, row.integer("version_number", 1));
        Long existing = JdbcSupport.scalar(target.connection(),
            "SELECT id FROM agent_skill_version WHERE skill_id=? AND version_no=?",
            Long.class, skillId, version);
        if (existing != null) {
            return reused("skill_version", existing, Map.of("skillId", skillId, "version", version));
        }
        String legacyHash = row.text("content_sha256", sourceHash);
        String content = "# Migrated Nhs skill\n\n"
            + "This version is disabled until the original skill bundle is materialized and verified.\n";
        long id = target.nextId("agent_skill_version", "id");
        target.insert("agent_skill_version", values(
            "id", id,
            "skill_id", skillId,
            "version_no", version,
            "content", content,
            "content_hash", json.sha256(content),
            "manifest_json", new MigrationTarget.JsonValue(json.linkedMap(
                "legacyContentHash", legacyHash,
                "legacySnapshotPath", row.text("snapshot_path"),
                "fileCount", row.integer("file_count", 0),
                "totalSize", row.longValue("total_size", 0),
                "materialized", false
            )),
            "runtime_requirements_json", new MigrationTarget.JsonValue(Map.of()),
            "status", "archived",
            "published_at", row.instant("published_at"),
            "created_by", operatorId,
            "created_at", time(row, "submitted_at")
        ));
        return inserted("skill_version", id, Map.of(
            "skillId", skillId, "version", version, "status", "archived", "legacyContentHash", legacyHash
        ));
    }

    /**
     * 处理知识库Base并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome knowledgeBase(RowValues row, String sourceHash) throws SQLException {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String sourceId = row.id();
        String key = key("nhs.knowledge." + sourceId, sourceHash);
        Long existing = target.findId("agent_knowledge_base", "id", "knowledge_key", key);
        if (existing != null) {
            return reused("knowledge_base", existing, Map.of("knowledgeKey", key));
        }
        Long ownerId = mappedUser(row.text("created_by"));
        String visibility = switch (row.text("visibility", "private").toLowerCase(Locale.ROOT)) {
            case "public", "enterprise", "enterprise_shared" -> "enterprise_shared";
            case "team", "restricted" -> "restricted";
            default -> "private";
        };
        String sourceStatus = row.text("status", "active").toLowerCase(Locale.ROOT);
        // RAGFlow is an optional adapter in nhs. A migrated dataset
        // must remain visibly inactive until an adapter is installed and its
        // credentials are configured; marking it active would make the data
        // look runnable while every retrieval call fails with 503.
        String status = switch (sourceStatus) {
            case "deleted", "missing" -> "missing";
            case "disabled" -> "disabled";
            default -> "disabled";
        };
        if ("active".equals(sourceStatus)) {
            issue("warning", "KNOWLEDGE_PROVIDER_CONFIGURATION_REQUIRED", "knowledge_bases", sourceId,
                "RAGFlow knowledge base migrated as disabled until a RAGFlow Provider is configured");
        }
        long id = target.nextId("agent_knowledge_base", "id");
        target.insert("agent_knowledge_base", values(
            "id", id,
            "knowledge_key", key,
            "name", truncate(row.text("name", "Migrated knowledge"), 255),
            "description", row.text("description"),
            "provider_type", "ragflow",
            "connector_id", null,
            "external_id", truncate(row.text("ragflow_dataset_id"), 128),
            "visibility", visibility,
            "status", status,
            "config_json", new MigrationTarget.JsonValue(Map.of(
                "requiresProviderConfiguration", true,
                "providerState", "not_configured",
                "sourceStatus", sourceStatus
            )),
            "owner_id", ownerId,
            "create_by", operatorId,
            "create_time", time(row, "created_at"),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(json.linkedMap(
                "legacySource", "nhs", "legacyId", sourceId, "tags", json.parseLenient(row.raw("tags")),
                "notes", row.text("notes"), "providerState", "not_configured",
                "activationRequired", true
            ))
        ));
        return inserted("knowledge_base", id, Map.of(
            "knowledgeKey", key, "provider", "ragflow", "visibility", visibility, "status", status,
            "providerState", "not_configured"
        ));
    }

    /**
     * 处理数据数据源并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome dataSource(RowValues row, String sourceHash) throws SQLException {
        String sourceId = row.id();
        String sourceKey = key("nhs.datasource." + sourceId, sourceHash);
        Long existing = target.findId("agent_data_source", "id", "source_key", sourceKey);
        if (existing != null) {
            return reused("data_source", existing, Map.of("sourceKey", sourceKey));
        }
        String dbType = normalizeDbType(row.text("db_type", "unknown"));
        int port = row.integer("port", defaultPort(dbType));
        String endpoint = dataSourceEndpoint(
            dbType, required(row.text("host"), "host"), port, row.text("database_name")
        );
        String credentialRef = "env:NHS_DATA_SOURCE_" + environmentName(sourceId) + "_CREDENTIAL";
        long id = target.nextId("agent_data_source", "id");
        target.insert("agent_data_source", values(
            "id", id,
            "source_key", sourceKey,
            "name", truncate(row.text("name", "Migrated data source"), 128),
            "db_type", dbType,
            "endpoint_url", truncate(endpoint, 1024),
            "database_name", truncate(row.text("database_name"), 255),
            "credential_ref", credentialRef,
            "readonly", true,
            "status", "testing",
            "config_json", new MigrationTarget.JsonValue(Map.of("sslMode", "require")),
            "create_by", operatorId,
            "create_time", time(row, "created_at"),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(Map.of(
                "legacySource", "nhs",
                "legacyId", sourceId,
                "credentialRotationRequired", true,
                "sourceUserPresent", row.text("db_user") != null
            ))
        ));
        placeholderSources.putIfAbsent(dbType, id);
        return inserted("data_source", id, Map.of(
            "sourceKey", sourceKey, "dbType", dbType, "status", "testing", "credentialRef", credentialRef
        ));
    }

    /**
     * 处理数据集并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome dataset(RowValues row, String sourceHash) throws SQLException {
        String sourceId = row.id();
        String datasetKey = key("nhs.dataset." + sourceId, sourceHash);
        Long existing = target.findId("agent_data_dataset", "id", "dataset_key", datasetKey);
        if (existing != null) {
            return reused("dataset", existing, Map.of("datasetKey", datasetKey));
        }
        String dbType = normalizeDbType(row.text("data_source", "unknown"));
        long dataSourceId = resolveDataSource(dbType);
        long id = target.nextId("agent_data_dataset", "id");
        String status = row.bool("status", false) ? "active" : "disabled";
        Object rowPolicy = json.parseLenient(row.raw("row_filter_config"));
        target.insert("agent_data_dataset", values(
            "id", id,
            "data_source_id", dataSourceId,
            "dataset_key", datasetKey,
            "name", truncate(row.text("display_name", row.text("name", "Migrated dataset")), 255),
            "description", row.text("description"),
            "status", status,
            "enable_row_policy", row.bool("enable_data_perm", false),
            "row_policy_json", new MigrationTarget.JsonValue(rowPolicy == null ? Map.of() : rowPolicy),
            "external_knowledge_id", truncate(row.text("rag_dataset_id"), 128),
            "owner_id", null,
            "create_by", operatorId,
            "create_time", time(row, "created_at"),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(json.linkedMap(
                "legacySource", "nhs", "legacyId", sourceId, "legacyDataSource", row.text("data_source"), "tags", json.parseLenient(row.raw("tags"))
            ))
        ));
        return inserted("dataset", id, Map.of(
            "datasetKey", datasetKey, "dataSourceId", dataSourceId, "status", status
        ));
    }

    /**
     * 处理数据Table并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome dataTable(RowValues row, String sourceHash) throws SQLException {
        Long datasetId = target.mappedId("datasets", required(row.text("dataset_id"), "dataset_id"), "dataset");
        if (datasetId == null) {
            return Outcome.skip("dataset mapping is missing");
        }
        String tableKey = key("nhs.table." + row.id(), sourceHash);
        Long existing = target.findId("agent_data_table", "id", "table_key", tableKey);
        if (existing != null) {
            return reused("data_table", existing, Map.of("tableKey", tableKey));
        }
        long id = target.nextId("agent_data_table", "id");
        target.insert("agent_data_table", values(
            "id", id,
            "dataset_id", datasetId,
            "table_key", tableKey,
            "physical_name", truncate(required(row.text("physical_name"), "physical_name"), 255),
            "display_name", truncate(row.text("term", row.text("physical_name")), 255),
            "description", row.text("description"),
            "table_type", "table",
            "status", row.bool("status", true) ? "active" : "disabled",
            "synonyms_json", new MigrationTarget.JsonValue(defaultJson(row.raw("synonyms"), List.of())),
            "metadata_json", new MigrationTarget.JsonValue(Map.of("legacySource", "nhs")),
            "create_by", operatorId,
            "create_time", time(row, "created_at"),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(Map.of("legacyId", row.id()))
        ));
        return inserted("data_table", id, Map.of("tableKey", tableKey, "datasetId", datasetId));
    }

    /**
     * 处理数据Column并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome dataColumn(RowValues row, String sourceHash) throws SQLException {
        Long tableId = target.mappedId("tables", required(row.text("table_id"), "table_id"), "data_table");
        if (tableId == null) {
            return Outcome.skip("table mapping is missing");
        }
        String columnKey = key("nhs.column." + row.id(), sourceHash);
        Long existing = JdbcSupport.scalar(target.connection(),
            "SELECT id FROM agent_data_column WHERE table_id=? AND column_key=?",
            Long.class, tableId, columnKey);
        if (existing != null) {
            return reused("data_column", existing, Map.of("columnKey", columnKey));
        }
        long id = target.nextId("agent_data_column", "id");
        target.insert("agent_data_column", values(
            "id", id,
            "table_id", tableId,
            "column_key", columnKey,
            "physical_name", truncate(required(row.text("physical_name"), "physical_name"), 255),
            "display_name", truncate(row.text("term", row.text("physical_name")), 255),
            "data_type", truncate(row.text("type", "unknown"), 128),
            "description", row.text("description"),
            "is_primary", row.bool("is_primary", false),
            "is_sensitive", false,
            "enum_json", new MigrationTarget.JsonValue(defaultJson(row.raw("enums"), Map.of())),
            "synonyms_json", new MigrationTarget.JsonValue(defaultJson(row.raw("synonyms"), List.of())),
            "sample_values_json", new MigrationTarget.JsonValue(defaultJson(row.raw("examples"), List.of())),
            "status", "active",
            "created_at", time(row, "created_at")
        ));
        return inserted("data_column", id, Map.of("columnKey", columnKey, "tableId", tableId));
    }

    /**
     * 处理数据Metric并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome dataMetric(RowValues row, String sourceHash) throws SQLException {
        Long datasetId = target.mappedId("datasets", required(row.text("dataset_id"), "dataset_id"), "dataset");
        if (datasetId == null) {
            return Outcome.skip("dataset mapping is missing");
        }
        String metricKey = key("nhs.metric." + row.id(), sourceHash);
        Long existing = JdbcSupport.scalar(target.connection(),
            "SELECT id FROM agent_data_metric WHERE dataset_id=? AND metric_key=? AND version_no=1",
            Long.class, datasetId, metricKey);
        if (existing != null) {
            return reused("data_metric", existing, Map.of("metricKey", metricKey));
        }
        long id = target.nextId("agent_data_metric", "id");
        target.insert("agent_data_metric", values(
            "id", id,
            "dataset_id", datasetId,
            "metric_key", metricKey,
            "name", truncate(row.text("display_name", row.text("name", "Migrated metric")), 255),
            "description", row.text("description"),
            "calculation_logic", required(row.text("calculation_logic"), "calculation_logic"),
            "unit", truncate(row.text("unit"), 64),
            "status", "active",
            "version_no", 1,
            "created_by", operatorId,
            "created_at", time(row, "created_at")
        ));
        return inserted("data_metric", id, Map.of("metricKey", metricKey, "datasetId", datasetId));
    }

    /**
     * 处理数据Relation并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome dataRelation(RowValues row, String sourceHash) throws SQLException {
        Long sourceTable = target.mappedId("tables", required(row.text("source_table_id"), "source_table_id"), "data_table");
        Long targetTable = target.mappedId("tables", required(row.text("target_table_id"), "target_table_id"), "data_table");
        if (sourceTable == null || targetTable == null) {
            return Outcome.skip("relationship table mapping is missing");
        }
        Long datasetId = JdbcSupport.scalar(target.connection(),
            "SELECT dataset_id FROM agent_data_table WHERE id=?", Long.class, sourceTable);
        if (datasetId == null) {
            return Outcome.skip("relationship dataset mapping is missing");
        }
        long id = target.nextId("agent_data_relation", "id");
        String joinType = switch (row.text("join_type", "left").toLowerCase(Locale.ROOT)) {
            case "inner", "right", "full" -> row.text("join_type").toLowerCase(Locale.ROOT);
            default -> "left";
        };
        target.insert("agent_data_relation", values(
            "id", id,
            "dataset_id", datasetId,
            "source_table_id", sourceTable,
            "target_table_id", targetTable,
            "join_type", joinType,
            "join_condition", required(row.text("join_condition"), "join_condition"),
            "description", row.text("description"),
            "status", "active",
            "created_by", operatorId,
            "created_at", Instant.now()
        ));
        return inserted("data_relation", id, Map.of(
            "datasetId", datasetId, "sourceTableId", sourceTable, "targetTableId", targetTable, "joinType", joinType
        ));
    }

    /**
     * 处理智能体并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome agent(RowValues row, String sourceHash) throws SQLException {
        String sourceId = row.id();
        String agentKey = key("nhs.agent." + sourceId, sourceHash);
        Long existing = target.findId("agent_definition", "id", "agent_key", agentKey);
        if (existing != null) {
            return reused("agent", existing, Map.of("agentKey", agentKey));
        }
        Long ownerId = mappedUser(row.text("created_by"));
        String status = row.bool("is_enabled", true) ? "active" : "disabled";
        long id = target.nextId("agent_definition", "id");
        target.insert("agent_definition", values(
            "id", id,
            "agent_key", agentKey,
            "name", truncate(row.text("display_name", row.text("name", "Migrated agent")), 128),
            "description", row.text("description"),
            "agent_type", truncate(row.text("agent_type", "general").toLowerCase(Locale.ROOT), 32),
            "engine_type", "agentscope_java",
            "avatar_url", truncate(row.text("avatar_url"), 512),
            "is_system", row.bool("is_system", false),
            "is_default", false,
            "status", status,
            "owner_id", ownerId,
            "sort_order", row.integer("sort_order", 0),
            "engine_config_json", new MigrationTarget.JsonValue(Map.of(
                "legacyEngineType", row.text("engine_type", "LOCAL"),
                "migrationReviewRequired", true
            )),
            "create_by", operatorId,
            "create_time", time(row, "created_at"),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(json.linkedMap(
                "legacySource", "nhs", "legacyId", sourceId, "legacyName", row.text("name"), "capabilities", json.parseLenient(row.raw("capabilities"))
            ))
        ));
        return inserted("agent", id, Map.of("agentKey", agentKey, "status", status));
    }

    /**
     * 处理智能体版本并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome agentVersion(RowValues row, String sourceHash) throws SQLException {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String sourceAgentId = required(row.text("agent_id"), "agent_id");
        Long agentId = target.mappedId("agents", sourceAgentId, "agent");
        if (agentId == null) {
            return Outcome.skip("agent mapping is missing");
        }
        int version = Math.max(1, row.integer("version_number", 1));
        Long existing = JdbcSupport.scalar(target.connection(),
            "SELECT id FROM agent_definition_version WHERE agent_id=? AND version_no=?",
            Long.class, agentId, version);
        if (existing != null) {
            return reused("agent_version", existing, Map.of("agentId", agentId, "version", version));
        }
        Map<String, Object> model = findModel(row.text("model_name"));
        Long modelId = model.get("id") instanceof Number number ? number.longValue() : null;
        Map<String, Object> modelSnapshot = new LinkedHashMap<>();
        if (!model.isEmpty()) {
            modelSnapshot.put("provider", model.get("provider_type"));
            modelSnapshot.put("modelName", model.get("model_name"));
            modelSnapshot.put("endpointUrl", model.get("endpoint_url"));
            modelSnapshot.put("credentialRef", model.get("credential_ref"));
            modelSnapshot.put("contextSize", model.get("context_size"));
            modelSnapshot.put("maxOutputTokens", model.get("max_output_tokens"));
            modelSnapshot.put("reasoningConfig", json.parseLenient(model.get("reasoning_config_json")));
        }
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("temperature", row.decimal("temperature") == null ? 0 : row.decimal("temperature"));
        runtime.put("maxIterations", 12);
        runtime.put("modelSnapshot", modelSnapshot);
        runtime.put("migrationReviewRequired", modelId == null);
        Object routingTags = json.parseLenient(row.raw("tools"));
        String sourceStatus = row.text("status", "DRAFT").toUpperCase(Locale.ROOT);
        String status = "PUBLISHED".equals(sourceStatus) && modelId != null ? "published"
            : "ARCHIVED".equals(sourceStatus) ? "archived" : "draft";
        Map<String, Object> contentPayload = json.linkedMap(
            "agentId", agentId,
            "version", version,
            "systemPrompt", row.text("system_prompt", ""),
            "runtime", runtime,
            "welcome", json.parseLenient(row.raw("welcome_config")),
            "routing", routingTags
        );
        String contentHash = json.sha256(contentPayload);
        long id = target.nextId("agent_definition_version", "id");
        String insertStatus = "draft";
        target.insert("agent_definition_version", values(
            "id", id,
            "agent_id", agentId,
            "version_no", version,
            "system_prompt", row.text("system_prompt", ""),
            "model_id", modelId,
            "synthesis_model_id", null,
            "runtime_config_json", new MigrationTarget.JsonValue(runtime),
            "welcome_config_json", new MigrationTarget.JsonValue(defaultJson(row.raw("welcome_config"), Map.of())),
            "routing_tags_json", new MigrationTarget.JsonValue(routingTags == null ? List.of() : routingTags),
            "status", insertStatus,
            "content_hash", contentHash,
            "published_at", "published".equals(status) ? time(row, "created_at") : null,
            "created_by", operatorId,
            "created_at", time(row, "created_at")
        ));
        bindAgentTools(id, row.raw("tools"));
        bindAgentSkills(id, row.raw("skills"));
        if (!insertStatus.equals(status)) {
            JdbcSupport.update(target.connection(),
                "UPDATE agent_definition_version SET status=? WHERE id=? AND status='draft'",
                status, id);
        }
        return inserted("agent_version", id, Map.of(
            "agentId", agentId,
            "version", version,
            "modelId", modelId == null ? 0 : modelId,
            "status", status,
            "contentHash", contentHash
        ));
    }

    /**
     * 处理bind智能体Tools相关逻辑。
     *
     * @param agentVersionId 资源标识
     * @param toolsValue {@code toolsValue}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private void bindAgentTools(long agentVersionId, Object toolsValue) throws SQLException {
        for (String name : stringList(toolsValue)) {
            Long toolId = JdbcSupport.scalar(target.connection(),
                "SELECT id FROM agent_tool WHERE (external_name=? OR name=?) AND del_flag='0' ORDER BY version_no DESC LIMIT 1",
                Long.class, name, name);
            if (toolId == null) {
                issue("warning", "NHS_TOOL_UNRESOLVED", "agent_versions", String.valueOf(agentVersionId),
                    "tool binding was not migrated because the tool name is unresolved: " + truncate(name, 80));
                continue;
            }
            target.insert("agent_agent_version_tool", values(
                "id", target.nextId("agent_agent_version_tool", "id"),
                "agent_version_id", agentVersionId,
                "resource_id", toolId,
                "permission", "use",
                "config_json", new MigrationTarget.JsonValue(Map.of("migrated", true)),
                "created_at", Instant.now()
            ));
        }
    }

    /**
     * 处理bind智能体Skills相关逻辑。
     *
     * @param agentVersionId 资源标识
     * @param skillsValue {@code skillsValue}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private void bindAgentSkills(long agentVersionId, Object skillsValue) throws SQLException {
        for (String sourceSkillId : stringList(skillsValue)) {
            Long skillId = target.mappedId("skill_publications", sourceSkillId, "skill");
            if (skillId == null) {
                issue("warning", "NHS_SKILL_UNRESOLVED", "agent_versions", String.valueOf(agentVersionId),
                    "skill binding was not migrated because the publication is unresolved: " + truncate(sourceSkillId, 80));
                continue;
            }
            target.insert("agent_agent_version_skill", values(
                "id", target.nextId("agent_agent_version_skill", "id"),
                "agent_version_id", agentVersionId,
                "resource_id", skillId,
                "permission", "use",
                "config_json", new MigrationTarget.JsonValue(Map.of("migrated", true)),
                "created_at", Instant.now()
            ));
        }
    }

    /**
     * 处理资源权限并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome resourcePermission(RowValues row, String sourceHash) throws SQLException {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (!row.bool("enabled", true)) {
            return Outcome.skip("disabled Nhs permission is not converted into an explicit deny");
        }
        Long userId = mappedUser(row.text("user_id"));
        if (userId == null) {
            return Outcome.skip("only direct user permissions are migrated; role permissions require administrator review");
        }
        String sourceResourceType = row.text("resource_type", "").toLowerCase(Locale.ROOT);
        String sourceResourceId = row.text("resource_id");
        if (sourceResourceId == null) {
            return Outcome.skip("resource permission has no resource identity");
        }
        ResourceTarget resource = switch (sourceResourceType) {
            case "agent" -> new ResourceTarget("agent", "agents", "agent", "use");
            case "dataset", "data" -> new ResourceTarget("dataset", "datasets", "dataset", "query");
            case "api", "tool" -> new ResourceTarget("tool", "api_tools", "tool", "invoke");
            default -> null;
        };
        if (resource == null) {
            return Outcome.skip("unsupported Nhs resource permission type: " + sourceResourceType);
        }
        Long targetResourceId = target.mappedId(resource.sourceType(), sourceResourceId, resource.targetType());
        if (targetResourceId == null) {
            return Outcome.skip("resource mapping is missing");
        }
        Long existing = JdbcSupport.scalar(target.connection(), """
            SELECT id FROM iam_user_permission_override
             WHERE user_id=? AND resource_type=? AND resource_id=? AND action=? AND status='active'
             LIMIT 1
            """, Long.class, userId, resource.resourceType(), targetResourceId, resource.action());
        if (existing != null) {
            return reused("permission_override", existing, Map.of(
                "userId", userId, "resourceType", resource.resourceType(), "resourceId", targetResourceId
            ));
        }
        long id = target.nextId("iam_user_permission_override", "id");
        target.insert("iam_user_permission_override", values(
            "id", id,
            "user_id", userId,
            "resource_type", resource.resourceType(),
            "resource_id", targetResourceId,
            "resource_key", null,
            "action", resource.action(),
            "effect", "allow",
            "policy_json", new MigrationTarget.JsonValue(Map.of("legacySource", "nhs", "reviewRequired", true)),
            "reason", "Migrated direct Nhs resource permission; requires administrator review",
            "status", "active",
            "expires_at", null,
            "created_by", operatorId,
            "created_at", time(row, "created_at")
        ));
        return inserted("permission_override", id, Map.of(
            "userId", userId,
            "resourceType", resource.resourceType(),
            "resourceId", targetResourceId,
            "action", resource.action(),
            "effect", "allow"
        ));
    }

    /**
     * 保存报表。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome savedReport(RowValues row, String sourceHash) throws SQLException {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String sourceId = row.id();
        String reportKey = key("nhs.report." + sourceId, sourceHash);
        Long existing = target.findId("agent_report", "id", "report_key", reportKey);
        if (existing != null) {
            return reused("report", existing, Map.of("reportKey", reportKey));
        }
        String sourceDatasetId = row.text("dataset_id");
        Long datasetId = sourceDatasetId == null ? null : target.mappedId("datasets", sourceDatasetId, "dataset");
        if (datasetId == null) {
            return Outcome.skip("saved report dataset mapping is missing");
        }
        Long ownerId = mappedUser(row.text("owner_user_id"));
        if (ownerId == null) {
            return Outcome.skip("saved report owner mapping is missing");
        }
        String visibility = switch (row.text("visibility", "private").toLowerCase(Locale.ROOT)) {
            case "public", "enterprise_shared" -> "enterprise_shared";
            case "restricted", "team" -> "restricted";
            default -> "private";
        };
        long id = target.nextId("agent_report", "id");
        target.insert("agent_report", values(
            "id", id,
            "report_key", reportKey,
            "name", truncate(row.text("title", "Migrated report"), 255),
            "dataset_id", datasetId,
            "sql_template", required(first(row, "sql_template", "sql_content"), "sql_content"),
            "params_schema_json", new MigrationTarget.JsonValue(defaultJson(row.raw("params_schema"), Map.of())),
            "visibility", visibility,
            "owner_id", ownerId,
            "status", "disabled",
            "create_by", operatorId,
            "create_time", time(row, "created_at"),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(json.linkedMap(
                "legacySource", "nhs",
                "legacyId", sourceId,
                "description", row.text("description"),
                "originalQuery", row.text("original_query"),
                "activationRequired", true
            ))
        ));
        return inserted("report", id, Map.of(
            "reportKey", reportKey, "datasetId", datasetId, "ownerId", ownerId, "status", "disabled"
        ));
    }

    /**
     * 处理scheduled任务并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome scheduledTask(RowValues row, String sourceHash) throws SQLException {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String sourceId = row.id();
        Long ownerId = mappedUser(row.text("user_id"));
        Long agentId = target.mappedId("agents", required(row.text("agent_id"), "agent_id"), "agent");
        if (ownerId == null || agentId == null) {
            return Outcome.skip("scheduled task owner or Agent mapping is missing");
        }
        Long agentVersionId = JdbcSupport.scalar(target.connection(), """
            SELECT id FROM agent_definition_version
             WHERE agent_id=? AND status IN ('published','draft')
             ORDER BY CASE status WHEN 'published' THEN 0 ELSE 1 END, version_no DESC
             LIMIT 1
            """, Long.class, agentId);
        if (agentVersionId == null) {
            return Outcome.skip("scheduled task Agent has no migrated version");
        }
        String triggerKey = key("nhs.automation." + sourceId, sourceHash);
        Long existing = target.findId("agent_automation_trigger", "id", "trigger_key", triggerKey);
        if (existing != null) {
            return reused("automation_trigger", existing, Map.of("triggerKey", triggerKey));
        }
        long serviceAccountId = automationServiceAccount();
        long taskId = target.nextId("agent_task", "id");
        long taskVersionId = target.nextId("agent_task_version", "id");
        String title = truncate(row.text("name", "Migrated scheduled task"), 255);
        String objective = required(row.text("prompt"), "prompt");
        Map<String, Object> content = json.linkedMap(
            "taskId", taskId,
            "title", title,
            "objective", objective,
            "agentVersionId", agentVersionId,
            "legacyTaskId", sourceId
        );
        String contentHash = json.sha256(content);
        target.insert("agent_task", values(
            "id", taskId,
            "task_key", truncate("nhs-auto-" + sourceId, 64),
            "project_id", null,
            "title", title,
            "objective", objective,
            "background", "Migrated from Nhs scheduled task. Trigger remains paused until machine grants are reviewed.",
            "source_conversation_id", null,
            "context_snapshot_json", new MigrationTarget.JsonValue(Map.of("legacyConversationId", row.text("conversation_id", ""))),
            "visibility", "restricted",
            "category", "operations",
            "orchestration_mode", "single_agent",
            "lifecycle_level", "L3_recurring_task",
            "risk_level", "R2",
            "status", "scheduled",
            "importance", 0,
            "urgency", 0,
            "queue_priority", 0,
            "owner_id", ownerId,
            "current_version_id", taskVersionId,
            "acceptance_mode", "human",
            "acceptance_config_json", new MigrationTarget.JsonValue(Map.of("reviewerId", ownerId)),
            "external_refs_json", new MigrationTarget.JsonValue(Map.of("nhsScheduledTaskId", sourceId)),
            "tags_json", new MigrationTarget.JsonValue(List.of("nhs-migrated", "automation-review-required")),
            "create_by", operatorId,
            "create_time", time(row, "created_at"),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(Map.of("activationRequired", true)),
            "owner_principal_type", "human"
        ));
        target.insert("agent_task_version", values(
            "id", taskVersionId,
            "task_id", taskId,
            "version_no", 1,
            "title", title,
            "objective", objective,
            "agent_version_id", agentVersionId,
            "workflow_version_id", null,
            "context_snapshot_json", new MigrationTarget.JsonValue(Map.of("legacySource", "nhs")),
            "resource_snapshot_json", new MigrationTarget.JsonValue(Map.of()),
            "acceptance_snapshot_json", new MigrationTarget.JsonValue(Map.of("mode", "human", "reviewerId", ownerId)),
            "input_snapshot_json", new MigrationTarget.JsonValue(Map.of("prompt", objective)),
            "content_hash", contentHash,
            "created_by", operatorId,
            "created_at", time(row, "created_at")
        ));
        long triggerId = target.nextId("agent_automation_trigger", "id");
        Object sourceConfig = json.parseLenient(row.raw("config"));
        target.insert("agent_automation_trigger", values(
            "id", triggerId,
            "trigger_key", triggerKey,
            "name", title,
            "trigger_type", "cron",
            "task_version_id", taskVersionId,
            "service_account_id", serviceAccountId,
            "cron_expr", truncate(required(row.text("cron_expr"), "cron_expr"), 128),
            "timezone", "Asia/Shanghai",
            "event_filter_json", new MigrationTarget.JsonValue(Map.of()),
            "idempotency_key_expr", "nhs:" + sourceId + ":${scheduledAt}",
            "status", "paused",
            "last_run_at", row.instant("last_run_at"),
            "next_run_at", row.instant("next_run_at"),
            "config_json", new MigrationTarget.JsonValue(sourceConfig == null ? Map.of() : sourceConfig),
            "create_by", operatorId,
            "create_time", time(row, "created_at"),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(Map.of(
                "legacySource", "nhs", "legacyId", sourceId, "legacyRunCount", row.longValue("run_count", 0), "activationRequired", true
            )),
            "task_id", taskId,
            "task_revision_no", 1,
            "revision_no", 1,
            "misfire_policy", "skip",
            "max_catchup_count", 1,
            "max_attempts", 3,
            "input_template", objective
        ));
        return inserted("automation_trigger", triggerId, Map.of(
            "triggerKey", triggerKey,
            "taskId", taskId,
            "taskVersionId", taskVersionId,
            "serviceAccountId", serviceAccountId,
            "status", "paused"
        ));
    }

    /**
     * 处理自动化Service账户并返回对应结果。
     *
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private long automationServiceAccount() throws SQLException {
        String accountKey = "nhs-migration-automation";
        Long existing = target.findId("agent_service_account", "id", "account_key", accountKey);
        if (existing != null) {
            return existing;
        }
        long id = target.nextId("agent_service_account", "id");
        target.insert("agent_service_account", values(
            "id", id,
            "account_key", accountKey,
            "name", "Nhs migrated automation",
            "description", "Disabled machine identity for migrated schedules; grants must be assigned explicitly before activation.",
            "owner_id", operatorId,
            "status", "disabled",
            "metadata_json", new MigrationTarget.JsonValue(Map.of("legacySource", "nhs", "humanInheritance", false)),
            "create_by", operatorId,
            "create_time", Instant.now(),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(Map.of("activationRequired", true))
        ));
        return id;
    }

    /**
     * 处理执行历史记录并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome executionHistory(RowValues row, String sourceHash) throws SQLException {
        String sourceId = row.id();
        String sourceConversation = row.text("conversation_id", "trace:" + row.text("trace_id", sourceId));
        Long userId = mappedUser(first(row, "user_id", "username"));
        Long agentId = target.mappedId("agents", required(row.text("agent_id"), "agent_id"), "agent");
        Long conversationId = null;
        if (userId != null) {
            conversationId = ensureConversation(sourceConversation, userId, agentId, row);
            appendHistoryMessages(conversationId, agentId, row);
        } else {
            issue("warning", "CONVERSATION_OWNER_UNRESOLVED", "execution_history", sourceId,
                "history was archived but not exposed as a conversation because its owner is unresolved");
        }
        long archiveId = target.nextId("agent_legacy_execution_archive", "id");
        Map<String, Object> payload = json.sanitizeRow(row.map());
        target.insert("agent_legacy_execution_archive", values(
            "id", archiveId,
            "migration_run_id", runId,
            "source_system", "nhs",
            "source_trace_id", truncate(row.text("trace_id"), 128),
            "source_execution_id", truncate(sourceId, 128),
            "source_agent_id", truncate(row.text("agent_id"), 128),
            "source_user_id", truncate(first(row, "user_id", "username"), 128),
            "source_conversation_id", truncate(sourceConversation, 128),
            "source_status", truncate(row.text("status"), 32),
            "started_at", row.instant("created_at"),
            "finished_at", row.instant("created_at"),
            "summary", truncate(row.text("summary"), 4000),
            "payload_json", new MigrationTarget.JsonValue(payload),
            "content_hash", json.sha256(payload),
            "created_at", Instant.now()
        ));
        return inserted("legacy_execution", archiveId, json.linkedMap(
            "archiveId", archiveId,
            "conversationId", conversationId,
            "sourceStatus", row.text("status")
        ));
    }

    /**
     * 校验会话，并在条件不满足时终止处理。
     *
     * @param sourceConversation 数据源会话参数
     * @param userId 资源标识
     * @param agentId 资源标识
     * @param row {@code row}参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Long ensureConversation(
        String sourceConversation,
        long userId,
        Long agentId,
        RowValues row
    ) throws SQLException {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String scopedKey = userId + ":" + sourceConversation;
        Long cached = conversations.get(scopedKey);
        if (cached != null) {
            return cached;
        }
        String sessionKey = boundedKey("nhs:" + userId + ":" + sourceConversation, 128,
            json.sha256(scopedKey).substring(0, 12));
        Long existing = target.findId("agent_conversation", "id", "session_key", sessionKey);
        if (existing != null) {
            conversations.put(scopedKey, existing);
            Integer maximum = JdbcSupport.scalar(target.connection(),
                "SELECT COALESCE(MAX(seq_no),0) FROM agent_conversation_message WHERE conversation_id=?",
                Integer.class, existing);
            conversationSequences.put(scopedKey, maximum == null ? 0 : maximum);
            return existing;
        }
        Long agentVersionId = agentId == null ? null : JdbcSupport.scalar(target.connection(),
            "SELECT id FROM agent_definition_version WHERE agent_id=? ORDER BY version_no DESC LIMIT 1",
            Long.class, agentId);
        long id = target.nextId("agent_conversation", "id");
        target.insert("agent_conversation", values(
            "id", id,
            "user_id", userId,
            "project_id", null,
            "task_id", null,
            "agent_id", agentId,
            "agent_version_id", agentVersionId,
            "title", truncate(row.text("query", "Migrated Nhs conversation"), 255),
            "visibility", "private",
            "status", "archived",
            "session_key", sessionKey,
            "last_message_at", time(row, "created_at"),
            "summary", truncate(row.text("summary"), 4000),
            "metadata_json", new MigrationTarget.JsonValue(Map.of(
                "legacySource", "nhs", "legacyConversationId", sourceConversation, "readOnly", true
            )),
            "create_by", userId,
            "create_time", time(row, "created_at"),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(Map.of("migrationRunId", runId))
        ));
        conversations.put(scopedKey, id);
        conversationSequences.put(scopedKey, 0);
        return id;
    }

    /**
     * 处理append历史记录Messages相关逻辑。
     *
     * @param conversationId 资源标识
     * @param agentId 资源标识
     * @param row {@code row}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private void appendHistoryMessages(Long conversationId, Long agentId, RowValues row) throws SQLException {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String sourceConversation = row.text("conversation_id", "trace:" + row.text("trace_id", row.id()));
        String scopedKey = mappedUser(first(row, "user_id", "username")) + ":" + sourceConversation;
        int sequence = conversationSequences.getOrDefault(scopedKey, 0);
        Long agentVersionId = agentId == null ? null : JdbcSupport.scalar(target.connection(),
            "SELECT id FROM agent_definition_version WHERE agent_id=? ORDER BY version_no DESC LIMIT 1",
            Long.class, agentId);
        Long modelId = row.text("model_config_id") == null ? null
            : target.mappedId("models", row.text("model_config_id"), "model");
        String query = row.text("query");
        if (query != null) {
            target.insert("agent_conversation_message", values(
                "id", target.nextId("agent_conversation_message", "id"),
                "conversation_id", conversationId,
                "seq_no", ++sequence,
                "trace_id", truncate(row.text("trace_id"), 64),
                "role", "user",
                "content", query,
                "content_json", new MigrationTarget.JsonValue(Map.of("legacyReadOnly", true)),
                "status", "completed",
                "created_at", time(row, "created_at")
            ));
        }
        String summary = row.text("summary");
        if (summary != null) {
            String status = "success".equalsIgnoreCase(row.text("status", "success")) ? "completed" : "failed";
            target.insert("agent_conversation_message", values(
                "id", target.nextId("agent_conversation_message", "id"),
                "conversation_id", conversationId,
                "seq_no", ++sequence,
                "trace_id", truncate(row.text("trace_id"), 64),
                "role", "assistant",
                "content", summary,
                "content_json", new MigrationTarget.JsonValue(json.linkedMap(
                    "legacyReadOnly", true,
                    "feedback", row.text("feedback"),
                    "reasoningArchived", row.text("reasoning_content") != null
                )),
                "agent_id", agentId,
                "agent_version_id", agentVersionId,
                "model_id", modelId,
                "status", status,
                "prompt_tokens", Math.max(0, row.integer("prompt_tokens", 0)),
                "completion_tokens", Math.max(0, row.integer("completion_tokens", 0)),
                "total_tokens", Math.max(0, row.integer("total_tokens", 0)),
                "created_at", time(row, "created_at")
            ));
        }
        conversationSequences.put(scopedKey, sequence);
    }

    /**
     * 处理执行链路追踪并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome executionTrace(RowValues row, String sourceHash) throws SQLException {
        String sourceId = row.id();
        Map<String, Object> payload = json.sanitizeRow(row.map());
        long id = target.nextId("agent_legacy_execution_archive", "id");
        target.insert("agent_legacy_execution_archive", values(
            "id", id,
            "migration_run_id", runId,
            "source_system", "nhs",
            "source_trace_id", truncate(row.text("trace_id"), 128),
            "source_execution_id", truncate("trace-step:" + sourceId, 128),
            "source_agent_id", truncate(row.text("agent_name"), 128),
            "source_user_id", null,
            "source_conversation_id", null,
            "source_status", truncate(row.text("status"), 32),
            "started_at", row.instant("created_at"),
            "finished_at", row.instant("created_at"),
            "summary", truncate(first(row, "error_message", "event_type"), 4000),
            "payload_json", new MigrationTarget.JsonValue(payload),
            "content_hash", json.sha256(payload),
            "created_at", Instant.now()
        ));
        return inserted("legacy_execution", id, Map.of(
            "archiveId", id, "traceId", row.text("trace_id", ""), "step", row.integer("step_number", 0)
        ));
    }

    /**
     * 处理{@code accessLog}并返回对应结果。
     *
     * @param row {@code row}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Outcome accessLog(RowValues row, String sourceHash) throws SQLException {
        Long actorId = mappedUser(row.text("user_name"));
        int statusCode = row.integer("status_code", 0);
        String decision = statusCode >= 200 && statusCode < 400 ? "success" : "failure";
        long id = target.nextId("agent_audit_event", "id");
        target.insert("agent_audit_event", values(
            "id", id,
            "trace_id", truncate(row.text("trace_id"), 64),
            "actor_type", actorId == null ? "system" : "user",
            "actor_id", actorId,
            "action", truncate("legacy.http." + row.text("method", "unknown").toLowerCase(Locale.ROOT), 64),
            "resource_type", "legacy_endpoint",
            "resource_id", null,
            "decision", decision,
            "decision_reason", "Migrated redacted Nhs access log",
            "request_summary", truncate(row.text("endpoint"), 4000),
            "result_summary", "HTTP " + statusCode,
            "ip_address", truncate(row.text("client_ip"), 64),
            "metadata_json", new MigrationTarget.JsonValue(json.linkedMap(
                "legacySource", "nhs",
                "legacyId", row.id(),
                "feature", row.text("feature_name"),
                "processTimeMs", row.decimal("process_time_ms"),
                "payloadsRedacted", true
            )),
            "created_at", time(row, "created_at")
        ));
        return inserted("audit_event", id, Map.of(
            "decision", decision, "statusCode", statusCode, "payloadsRedacted", true
        ));
    }

    /**
     * 获取数据数据源。
     *
     * @param dbType 业务类型
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private long resolveDataSource(String dbType) throws SQLException {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Long cached = placeholderSources.get(dbType);
        if (cached != null) {
            return cached;
        }
        Long existing = JdbcSupport.scalar(target.connection(), """
            SELECT id FROM agent_data_source
             WHERE db_type=? AND del_flag='0'
             ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'testing' THEN 1 ELSE 2 END, id
             LIMIT 1
            """, Long.class, dbType);
        if (existing != null) {
            placeholderSources.put(dbType, existing);
            return existing;
        }
        String sourceKey = key("nhs.placeholder." + dbType, json.sha256(dbType));
        long id = target.nextId("agent_data_source", "id");
        target.insert("agent_data_source", values(
            "id", id,
            "source_key", sourceKey,
            "name", truncate("Nhs migrated " + dbType + " source", 128),
            "db_type", dbType,
            "endpoint_url", "jdbc:" + dbType + "://migration.invalid/replace-before-activation",
            "credential_ref", "env:NHS_DATA_SOURCE_" + environmentName(dbType) + "_CREDENTIAL",
            "readonly", true,
            "status", "disabled",
            "config_json", new MigrationTarget.JsonValue(Map.of("placeholder", true)),
            "create_by", operatorId,
            "create_time", Instant.now(),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(Map.of("activationRequired", true, "legacyDataSourceType", dbType))
        ));
        placeholderSources.put(dbType, id);
        issue("warning", "DATA_SOURCE_PLACEHOLDER_CREATED", "datasets", dbType,
            "no unambiguous Nhs connection matched the dataset type; a disabled placeholder was created");
        return id;
    }

    /**
     * 将输入数据转换为ped用户。
     *
     * @param sourceIdentity 数据源身份参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Long mappedUser(String sourceIdentity) throws SQLException {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (sourceIdentity == null || sourceIdentity.isBlank()) {
            return null;
        }
        Long alias = userAliases.get(sourceIdentity.strip());
        if (alias != null) {
            return alias;
        }
        Long mapped = target.mappedId("users", sourceIdentity, "user");
        if (mapped != null) {
            return mapped;
        }
        Long numericMapped = target.mappedId("users", sourceIdentity.strip(), "user");
        if (numericMapped != null) {
            return numericMapped;
        }
        return target.findId("sys_user", "user_id", "user_name", truncate(sourceIdentity.strip(), 30));
    }

    /**
     * 获取模型。
     *
     * @param modelName 名称
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Map<String, Object> findModel(String modelName) throws SQLException {
        if (modelName == null) {
            return Map.of();
        }
        return JdbcSupport.row(target.connection(), """
            SELECT id, provider_type, model_name, endpoint_url, credential_ref,
                   context_size, max_output_tokens, reasoning_config_json
              FROM agent_model
             WHERE (model_name=? OR display_name=?) AND del_flag='0'
             ORDER BY id
             LIMIT 1
            """, modelName, modelName);
    }

    /**
     * 创建并保存{@code ed}。
     *
     * @param targetType 业务类型
     * @param targetId 资源标识
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    private Outcome inserted(String targetType, long targetId, Map<String, Object> payload) {
        return new Outcome(targetType, targetId, true, false, null, json.sha256(payload));
    }

    /**
     * 处理{@code reused}并返回对应结果。
     *
     * @param targetType 业务类型
     * @param targetId 资源标识
     * @param payload {@code payload}参数
     * @return 处理结果
     */
    private Outcome reused(String targetType, long targetId, Map<String, Object> payload) {
        return new Outcome(targetType, targetId, false, false, null, json.sha256(payload));
    }

    /**
     * 判断{@code sue}是否满足要求。
     *
     * @param severity {@code severity}参数
     * @param code {@code code}参数
     * @param entity {@code entity}参数
     * @param sourceId 资源标识
     * @param summary {@code summary}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private void issue(String severity, String code, String entity, String sourceId, String summary) throws SQLException {
        MigrationReport.Issue issue = new MigrationReport.Issue(severity, code, entity, sourceId, summary);
        report.issue(severity, code, entity, sourceId, summary);
        target.persistIssue(runId, issue);
    }

    /**
     * 处理{@code phase}并返回对应结果。
     *
     * @param entityType 业务类型
     * @return 处理结果
     */
    private String phase(String entityType) {
        return Set.of("execution_history", "execution_traces").contains(entityType) ? "archive" : "load";
    }

    /**
     * 处理{@code safeError}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String safeError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        message = message.replaceAll("(?i)(password|secret|token|api[_-]?key)\\s*[=:]\\s*[^,;\\s]+", "$1=<redacted>");
        return truncate(message.replace('\n', ' '), 2000);
    }

    /**
     * 处理{@code key}并返回对应结果。
     *
     * @param source 数据源参数
     * @param hash {@code hash}参数
     * @return 处理结果
     */
    private String key(String source, String hash) {
        String normalized = source.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^[^a-z]+", "")
            .replaceAll("-+", "-");
        if (normalized.isEmpty()) {
            normalized = "nhs-" + hash.substring(0, 12);
        }
        if (normalized.length() > 128) {
            normalized = normalized.substring(0, 115) + "-" + hash.substring(0, 12);
        }
        if (!SAFE_KEY.matcher(normalized).matches()) {
            throw new IllegalArgumentException("cannot create safe target key");
        }
        return normalized;
    }

    /**
     * 处理{@code boundedKey}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param limit 数量上限
     * @param suffix {@code suffix}参数
     * @return 处理结果
     */
    private String boundedKey(String value, int limit, String suffix) {
        String normalized = value.replaceAll("[\\p{Cntrl}]", "").strip();
        if (normalized.length() <= limit) {
            return normalized;
        }
        int prefix = Math.max(1, limit - suffix.length() - 1);
        return normalized.substring(0, prefix) + "-" + suffix;
    }

    /**
     * 处理{@code environmentName}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String environmentName(String value) {
        String normalized = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        if (normalized.isEmpty() || !Character.isLetter(normalized.charAt(0))) {
            normalized = "ID_" + normalized;
        }
        return truncate(normalized, 80);
    }

    /**
     * 处理{@code normalizeDbType}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeDbType(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "postgres", "postgresql", "pg" -> "postgresql";
            case "mysql", "mariadb" -> "mysql";
            case "clickhouse" -> "clickhouse";
            case "oracle" -> "oracle";
            case "sqlserver", "mssql" -> "sqlserver";
            default -> "unknown";
        };
    }

    /**
     * 处理{@code defaultPort}并返回对应结果。
     *
     * @param dbType 业务类型
     * @return 处理结果
     */
    private int defaultPort(String dbType) {
        return switch (dbType) {
            case "postgresql" -> 5432;
            case "mysql" -> 3306;
            case "clickhouse" -> 8123;
            case "oracle" -> 1521;
            case "sqlserver" -> 1433;
            default -> 0;
        };
    }

    /**
     * 处理数据数据源Endpoint并返回对应结果。
     *
     * @param dbType 业务类型
     * @param host {@code host}参数
     * @param port {@code port}参数
     * @param database {@code database}参数
     * @return 处理结果
     */
    private String dataSourceEndpoint(String dbType, String host, int port, String database) {
        // The platform stores the host endpoint separately from the database name.
        // PostgreSQL runtime validation therefore expects postgresql://host[:port],
        // while legacy Nhs rows were previously emitted with a JDBC prefix/path.
        if ("postgresql".equals(dbType)) {
            return "postgresql://" + host + (port > 0 ? ":" + port : "");
        }
        String encodedDatabase = database == null ? "" : "/" + URLEncoder.encode(database, StandardCharsets.UTF_8);
        return "jdbc:" + dbType + "://" + host + (port > 0 ? ":" + port : "") + encodedDatabase;
    }

    /**
     * 处理{@code defaultJson}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private Object defaultJson(Object raw, Object fallback) {
        Object parsed = json.parseLenient(raw);
        return parsed == null ? fallback : parsed;
    }

    /**
     * 处理{@code stringList}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 符合条件的数据集合
     */
    private List<String> stringList(Object raw) {
        Object parsed = json.parseLenient(raw);
        if (parsed instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).map(String::strip).filter(value -> !value.isEmpty()).toList();
        }
        if (parsed instanceof String text && !text.isBlank()) {
            return List.of(text.strip());
        }
        return List.of();
    }

    /**
     * 处理{@code time}并返回对应结果。
     *
     * @param row {@code row}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private Instant time(RowValues row, String key) {
        Instant value = row.instant(key);
        return value == null ? Instant.now() : value;
    }

    /**
     * 处理{@code positiveOrNull}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Integer positiveOrNull(int value) {
        return value > 0 ? value : null;
    }

    /**
     * 处理{@code first}并返回对应结果。
     *
     * @param row {@code row}参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private String first(RowValues row, String... keys) {
        for (String key : keys) {
            String value = row.text(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 处理{@code firstRaw}并返回对应结果。
     *
     * @param row {@code row}参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private Object firstRaw(RowValues row, String... keys) {
        for (String key : keys) {
            Object value = row.raw(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 校验{@code d}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    /**
     * 处理{@code truncate}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }

    /**
     * 处理{@code values}并返回对应结果。
     *
     * @param entries {@code entries}参数
     * @return 处理结果
     */
    private Map<String, Object> values(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return result;
    }

    /**
     * 定义{@code Handler}相关的处理能力契约。
     */
    @FunctionalInterface
    private interface Handler {
        /**
         * 处理{@code apply}并返回对应结果。
         *
         * @param row {@code row}参数
         * @param sourceHash 数据源Hash参数
         * @return 处理结果
         * @throws Exception 当处理过程无法正常完成时抛出
         */
        Outcome apply(RowValues row, String sourceHash) throws Exception;
    }

    /**
     * 封装{@code Outcome}相关的不可变数据。
     */
    private record Outcome(
        String targetType,
        Long targetId,
        boolean inserted,
        boolean skipped,
        String reason,
        String targetHash
    ) {
        /**
         * 处理{@code skip}并返回对应结果。
         *
         * @param reason {@code reason}参数
         * @return 处理结果
         */
        static Outcome skip(String reason) {
            return new Outcome(null, null, false, true, reason, null);
        }
    }

    /**
     * 封装资源Target相关的不可变数据。
     */
    private record ResourceTarget(
        String resourceType,
        String sourceType,
        String targetType,
        String action
    ) {
    }

    /**
     * 表示{@code Counters}相关的领域对象。
     */
    private static final class Counters {
        private long source;
        private long mapped;
        private long inserted;
        private long reused;
        private long skipped;
        private long failed;

        /**
         * 创建 {@code Counters} 实例并初始化所需依赖。
         *
         * @param source 数据源参数
         */
        private Counters(long source) {
            this.source = source;
        }
    }
}
