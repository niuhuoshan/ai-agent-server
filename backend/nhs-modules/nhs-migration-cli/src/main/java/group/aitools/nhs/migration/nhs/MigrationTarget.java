package group.aitools.nhs.migration.nhs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 表示迁移Target相关的领域对象。
 */
final class MigrationTarget {

    private static final Map<String, TargetTable> TARGET_TABLES = Map.ofEntries(
        Map.entry("user", new TargetTable("sys_user", "user_id")),
        Map.entry("model", new TargetTable("agent_model", "id")),
        Map.entry("connector", new TargetTable("agent_connector", "id")),
        Map.entry("tool", new TargetTable("agent_tool", "id")),
        Map.entry("skill", new TargetTable("agent_skill", "id")),
        Map.entry("skill_version", new TargetTable("agent_skill_version", "id")),
        Map.entry("knowledge_base", new TargetTable("agent_knowledge_base", "id")),
        Map.entry("data_source", new TargetTable("agent_data_source", "id")),
        Map.entry("dataset", new TargetTable("agent_data_dataset", "id")),
        Map.entry("data_table", new TargetTable("agent_data_table", "id")),
        Map.entry("data_column", new TargetTable("agent_data_column", "id")),
        Map.entry("data_metric", new TargetTable("agent_data_metric", "id")),
        Map.entry("data_relation", new TargetTable("agent_data_relation", "id")),
        Map.entry("agent", new TargetTable("agent_definition", "id")),
        Map.entry("agent_version", new TargetTable("agent_definition_version", "id")),
        Map.entry("permission_override", new TargetTable("iam_user_permission_override", "id")),
        Map.entry("report", new TargetTable("agent_report", "id")),
        Map.entry("automation_trigger", new TargetTable("agent_automation_trigger", "id")),
        Map.entry("legacy_execution", new TargetTable("agent_legacy_execution_archive", "id")),
        Map.entry("audit_event", new TargetTable("agent_audit_event", "id"))
    );

    private final Connection connection;
    private final JsonCodec json;
    private final Map<String, Long> nextIds = new HashMap<>();
    private final Map<String, Long> mappings = new HashMap<>();

    /**
     * 创建 {@code MigrationTarget} 实例并初始化所需依赖。
     *
     * @param connection {@code connection}参数
     * @param json {@code json}参数
     */
    MigrationTarget(Connection connection, JsonCodec json) {
        this.connection = connection;
        this.json = json;
    }

    /**
     * 校验{@code Schema}，并在条件不满足时终止处理。
     *
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    void verifySchema() throws SQLException {
        String schema = JdbcSupport.scalar(connection, "SELECT current_schema()", String.class);
        for (String table : List.of(
            "agent_migration_run", "agent_migration_mapping", "agent_migration_entity_stat",
            "agent_migration_issue", "agent_migration_checkpoint"
        )) {
            if (!JdbcSupport.tableExists(connection, schema, table)) {
                throw new IllegalStateException("target schema is missing " + table + "; apply Flyway V31 first");
            }
        }
    }

    /**
     * 处理{@code acquireLock}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    boolean acquireLock() throws SQLException {
        Boolean acquired = JdbcSupport.scalar(
            connection,
            "SELECT pg_try_advisory_lock(hashtext('agent:nhs:migration'))",
            Boolean.class
        );
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 处理{@code releaseLock}相关逻辑。
     */
    void releaseLock() {
        try {
            JdbcSupport.scalar(
                connection,
                "SELECT pg_advisory_unlock(hashtext('agent:nhs:migration'))",
                Boolean.class
            );
        } catch (SQLException ignored) {
            // The database session releases advisory locks when it closes.
        }
    }

    /**
     * 创建并保存{@code Run}。
     *
     * @param arguments {@code arguments}参数
     * @param manifestHash {@code manifestHash}参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    long createRun(CliArguments arguments, String manifestHash) throws SQLException {
        Long existing = JdbcSupport.scalar(
            connection,
            "SELECT id FROM agent_migration_run WHERE source_system='nhs' AND run_key=?",
            Long.class,
            arguments.runKey()
        );
        if (existing != null) {
            throw new IllegalStateException("migration run-key already exists: " + arguments.runKey());
        }
        long id = nextId("agent_migration_run", "id");
        insert("agent_migration_run", values(
            "id", id,
            "source_system", "nhs",
            "source_version", arguments.sourceVersion(),
            "target_version", arguments.targetVersion(),
            "migration_type", arguments.migrationType(),
            "status", "running",
            "started_at", Instant.now(),
            "operator_id", arguments.operatorId(),
            "run_key", arguments.runKey(),
            "source_schema", arguments.sourceSchema(),
            "source_snapshot_at", Instant.now(),
            "manifest_hash", manifestHash,
            "verification_status", "pending"
        ));
        return id;
    }

    /**
     * 处理{@code finishRun}相关逻辑。
     *
     * @param runId 资源标识
     * @param report 报表参数
     * @param succeeded {@code succeeded}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    void finishRun(long runId, MigrationReport report, boolean succeeded) throws SQLException {
        Map<String, Object> payload = report.payload();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) payload.get("summary");
        JdbcSupport.update(connection, """
            UPDATE agent_migration_run
               SET status=?, finished_at=CURRENT_TIMESTAMP, source_count=?, target_count=?, error_count=?,
                   checksum=?, verification_status=?, report_json=?::jsonb
             WHERE id=?
            """,
            succeeded ? "succeeded" : "failed",
            ((Number) summary.get("sourceCount")).longValue(),
            ((Number) summary.get("mappedCount")).longValue(),
            ((Number) summary.get("errorCount")).longValue(),
            payload.get("reportHash"),
            succeeded ? "passed" : "failed",
            json.write(payload),
            runId
        );
    }

    /**
     * 处理persist结果相关逻辑。
     *
     * @param runId 资源标识
     * @param result 结果参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    void persistResult(long runId, MigrationReport.EntityResult result) throws SQLException {
        long id = nextId("agent_migration_entity_stat", "id");
        insert("agent_migration_entity_stat", values(
            "id", id,
            "migration_run_id", runId,
            "entity_type", result.entityType(),
            "phase", result.phase(),
            "status", result.status(),
            "source_count", result.sourceCount(),
            "mapped_count", result.mappedCount(),
            "inserted_count", result.insertedCount(),
            "reused_count", result.reusedCount(),
            "skipped_count", result.skippedCount(),
            "failed_count", result.failedCount(),
            "source_hash", result.sourceHash(),
            "target_hash", result.targetHash(),
            "detail_json", new JsonValue(result.detail()),
            "started_at", Instant.now(),
            "finished_at", Instant.now()
        ));
    }

    /**
     * 处理{@code persistIssue}相关逻辑。
     *
     * @param runId 资源标识
     * @param issue {@code issue}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    void persistIssue(long runId, MigrationReport.Issue issue) throws SQLException {
        insert("agent_migration_issue", values(
            "id", nextId("agent_migration_issue", "id"),
            "migration_run_id", runId,
            "entity_type", issue.entityType(),
            "source_id", truncate(issue.sourceId(), 128),
            "severity", issue.severity(),
            "issue_code", issue.code(),
            "summary", issue.summary(),
            "detail_json", new JsonValue(Map.of("redacted", true))
        ));
    }

    /**
     * 校验{@code point}，并在条件不满足时终止处理。
     *
     * @param runId 资源标识
     * @param sourceSchema 数据源Schema参数
     * @param entityType 业务类型
     * @param sourceHash 数据源Hash参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    void checkpoint(
        long runId,
        String sourceSchema,
        String entityType,
        String sourceHash
    ) throws SQLException {
        insert("agent_migration_checkpoint", values(
            "id", nextId("agent_migration_checkpoint", "id"),
            "source_system", "nhs",
            "source_schema", sourceSchema,
            "entity_type", entityType,
            "last_source_id", null,
            "last_source_updated_at", null,
            "snapshot_at", Instant.now(),
            "source_hash", sourceHash,
            "migration_run_id", runId,
            "created_at", Instant.now()
        ));
    }

    /**
     * 将输入数据转换为{@code ping}。
     *
     * @param runId 资源标识
     * @param sourceType 业务类型
     * @param sourceId 资源标识
     * @param targetType 业务类型
     * @param targetId 资源标识
     * @param sourceHash 数据源Hash参数
     * @param targetHash {@code targetHash}参数
     * @param status 目标状态
     * @param error {@code error}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    void mapping(
        long runId,
        String sourceType,
        String sourceId,
        String targetType,
        Long targetId,
        String sourceHash,
        String targetHash,
        String status,
        String error
    ) throws SQLException {
        String persistedTargetHash = targetHash;
        if (targetId != null && "mapped".equals(status)) {
            String currentHash = currentTargetHash(targetType, targetId);
            if (currentHash != null) {
                persistedTargetHash = currentHash;
            }
        }
        insert("agent_migration_mapping", values(
            "id", nextId("agent_migration_mapping", "id"),
            "migration_run_id", runId,
            "source_type", sourceType,
            "source_id", truncate(sourceId, 128),
            "target_type", targetType,
            "target_id", targetId,
            "source_hash", sourceHash,
            "target_hash", persistedTargetHash,
            "status", status,
            "error_message", error
        ));
        if (targetId != null && "mapped".equals(status)) {
            mappings.put(mappingKey(sourceType, sourceId, targetType), targetId);
        }
    }

    /**
     * 将输入数据转换为{@code pedId}。
     *
     * @param sourceType 业务类型
     * @param sourceId 资源标识
     * @param targetType 业务类型
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    Long mappedId(String sourceType, String sourceId, String targetType) throws SQLException {
        String key = mappingKey(sourceType, sourceId, targetType);
        Long cached = mappings.get(key);
        if (cached != null) {
            return cached;
        }
        Long found = JdbcSupport.scalar(connection, """
            SELECT m.target_id
              FROM agent_migration_mapping m
              JOIN agent_migration_run r ON r.id=m.migration_run_id
             WHERE r.source_system='nhs'
               AND m.source_type=? AND m.source_id=? AND m.target_type=?
               AND m.status='mapped' AND m.target_id IS NOT NULL
             ORDER BY r.created_at DESC
             LIMIT 1
            """, Long.class, sourceType, truncate(sourceId, 128), targetType);
        if (found != null) {
            mappings.put(key, found);
        }
        return found;
    }

    /**
     * 处理{@code priorMapping}并返回对应结果。
     *
     * @param sourceType 业务类型
     * @param sourceId 资源标识
     * @param targetType 业务类型
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    PriorMapping priorMapping(String sourceType, String sourceId, String targetType) throws SQLException {
        Map<String, Object> row = JdbcSupport.row(connection, """
            SELECT m.target_id, m.source_hash, m.target_hash, m.status, m.error_message
              FROM agent_migration_mapping m
              JOIN agent_migration_run r ON r.id=m.migration_run_id
             WHERE r.source_system='nhs'
               AND m.source_type=? AND m.source_id=? AND m.target_type=?
               AND r.status='succeeded' AND m.status IN ('mapped', 'skipped')
             ORDER BY r.created_at DESC, m.id DESC
             LIMIT 1
            """, sourceType, truncate(sourceId, 128), targetType);
        if (row.isEmpty()) {
            return null;
        }
        return new PriorMapping(
            row.get("target_id") instanceof Number number ? number.longValue() : null,
            String.valueOf(row.get("source_hash")),
            row.get("target_hash") == null ? null : String.valueOf(row.get("target_hash")),
            String.valueOf(row.get("status")),
            row.get("error_message") == null ? null : String.valueOf(row.get("error_message"))
        );
    }

    /**
     * 获取{@code Id}。
     *
     * @param table {@code table}参数
     * @param idColumn {@code idColumn}参数
     * @param keyColumn {@code keyColumn}参数
     * @param keyValue {@code keyValue}参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    Long findId(String table, String idColumn, String keyColumn, Object keyValue) throws SQLException {
        String sql = "SELECT " + JdbcSupport.quoteIdentifier(idColumn)
            + " FROM " + JdbcSupport.quoteIdentifier(table)
            + " WHERE " + JdbcSupport.quoteIdentifier(keyColumn) + "=? LIMIT 1";
        return JdbcSupport.scalar(connection, sql, Long.class, keyValue);
    }

    /**
     * 处理{@code nextId}并返回对应结果。
     *
     * @param table {@code table}参数
     * @param idColumn {@code idColumn}参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    long nextId(String table, String idColumn) throws SQLException {
        Long current = nextIds.get(table);
        if (current == null) {
            String sql = "SELECT COALESCE(MAX(" + JdbcSupport.quoteIdentifier(idColumn) + "), 0) + 1 FROM "
                + JdbcSupport.quoteIdentifier(table);
            current = JdbcSupport.scalar(connection, sql, Long.class);
        }
        nextIds.put(table, current + 1);
        return current;
    }

    /**
     * 创建并保存{@code insert}。
     *
     * @param table {@code table}参数
     * @param values {@code values}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    void insert(String table, Map<String, Object> values) throws SQLException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        StringJoiner columns = new StringJoiner(", ");
        StringJoiner placeholders = new StringJoiner(", ");
        values.forEach((key, value) -> {
            columns.add(JdbcSupport.quoteIdentifier(key));
            placeholders.add(value instanceof JsonValue ? "?::jsonb" : "?");
        });
        String sql = "INSERT INTO " + JdbcSupport.quoteIdentifier(table)
            + " (" + columns + ") VALUES (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (Object value : values.values()) {
                if (value instanceof JsonValue jsonValue) {
                    statement.setString(index++, json.write(jsonValue.value()));
                } else if (value instanceof Instant instant) {
                    statement.setTimestamp(index++, Timestamp.from(instant));
                } else if (value == null) {
                    statement.setNull(index++, Types.NULL);
                } else {
                    statement.setObject(index++, value);
                }
            }
            statement.executeUpdate();
        }
    }

    /**
     * 保存{@code point}。
     *
     * @param name 名称
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    Savepoint savepoint(String name) throws SQLException {
        return connection.setSavepoint(name);
    }

    /**
     * 处理{@code rollback}相关逻辑。
     *
     * @param savepoint {@code savepoint}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    void rollback(Savepoint savepoint) throws SQLException {
        connection.rollback(savepoint);
    }

    /**
     * 处理{@code release}相关逻辑。
     *
     * @param savepoint {@code savepoint}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    void release(Savepoint savepoint) throws SQLException {
        connection.releaseSavepoint(savepoint);
    }

    /**
     * 处理{@code commit}相关逻辑。
     *
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    void commit() throws SQLException {
        connection.commit();
    }

    /**
     * 处理{@code rollback}相关逻辑。
     */
    void rollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure remains the useful diagnostic.
        }
    }

    /**
     * 处理{@code connection}并返回对应结果。
     *
     * @return 处理结果
     */
    Connection connection() {
        return connection;
    }

    /**
     * 处理当前TargetHash并返回对应结果。
     *
     * @param targetType 业务类型
     * @param targetId 资源标识
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    String currentTargetHash(String targetType, long targetId) throws SQLException {
        TargetTable table = TARGET_TABLES.get(targetType);
        if (table == null) {
            return null;
        }
        Map<String, Object> row = JdbcSupport.row(
            connection,
            "SELECT * FROM " + JdbcSupport.quoteIdentifier(table.table())
                + " WHERE " + JdbcSupport.quoteIdentifier(table.idColumn()) + "=?",
            targetId
        );
        if (row.isEmpty()) {
            return null;
        }
        return json.sha256(json.sanitizeRow(row));
    }

    /**
     * 处理{@code targetTable}并返回对应结果。
     *
     * @param targetType 业务类型
     * @return 处理结果
     */
    static TargetTable targetTable(String targetType) {
        return TARGET_TABLES.get(targetType);
    }

    /**
     * 将输入数据转换为{@code pingKey}。
     *
     * @param sourceType 业务类型
     * @param sourceId 资源标识
     * @param targetType 业务类型
     * @return 处理结果
     */
    private String mappingKey(String sourceType, String sourceId, String targetType) {
        return sourceType + '\u0000' + sourceId + '\u0000' + targetType;
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
     * 封装{@code JsonValue}相关的不可变数据。
     */
    record JsonValue(Object value) {
    }

    /**
     * 封装{@code PriorMapping}相关的不可变数据。
     */
    record PriorMapping(Long targetId, String sourceHash, String targetHash, String status, String reason) {
    }

    /**
     * 封装{@code TargetTable}相关的不可变数据。
     */
    record TargetTable(String table, String idColumn) {
    }
}
