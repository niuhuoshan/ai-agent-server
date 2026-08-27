package group.aitools.nhs.migration.nhs;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责{@code NhsInventory}相关的业务编排与领域规则处理。
 */
final class NhsInventoryService {

    private final JsonCodec json;

    /**
     * 创建 {@code NhsInventoryService} 实例并初始化所需依赖。
     *
     * @param json {@code json}参数
     */
    NhsInventoryService(JsonCodec json) {
        this.json = json;
    }

    /**
     * 处理{@code inventory}相关逻辑。
     *
     * @param source 数据源参数
     * @param schema {@code schema}参数
     * @param report 报表参数
     * @param strict {@code strict}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    void inventory(Connection source, String schema, MigrationReport report, boolean strict) throws SQLException {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        for (NhsSourceCatalog.Entity entity : NhsSourceCatalog.ENTITIES) {
            if (!JdbcSupport.tableExists(source, schema, entity.sourceTable())) {
                report.add(MigrationReport.EntityResult.inventory(
                    entity.type(), "skipped", 0, null,
                    Map.of(
                        "sourceTable", entity.sourceTable(),
                        "target", entity.target(),
                        "disposition", entity.disposition(),
                        "reason", "source table does not exist"
                    )
                ));
                if (strict && requiredForPhaseOne(entity.type())) {
                    report.issue("error", "SOURCE_TABLE_MISSING", entity.type(), null,
                        "required source table " + entity.sourceTable() + " is missing");
                }
                continue;
            }
            Set<String> columns = JdbcSupport.columns(source, schema, entity.sourceTable());
            List<String> missing = entity.requiredColumns().stream().filter(column -> !columns.contains(column)).sorted().toList();
            long count = JdbcSupport.count(source, JdbcSupport.qualified(schema, entity.sourceTable()));
            List<Map<String, Object>> rows = JdbcSupport.rows(
                source,
                JdbcSupport.qualified(schema, entity.sourceTable()),
                columns.contains("id") ? "id" : null
            );
            List<String> hashes = new ArrayList<>(rows.size());
            rows.forEach(row -> hashes.add(json.sha256(json.sanitizeRow(row))));
            String hash = json.aggregateHash(hashes);
            String status = missing.isEmpty() ? "passed" : "failed";
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("sourceTable", entity.sourceTable());
            detail.put("target", entity.target());
            detail.put("disposition", entity.disposition());
            detail.put("columns", columns.stream().sorted().toList());
            detail.put("missingRequiredColumns", missing);
            detail.put("credentialHandling", credentialHandling(entity.type()));
            report.add(MigrationReport.EntityResult.inventory(
                entity.type(), status, count, hash, Map.copyOf(detail)
            ));
            if (!missing.isEmpty()) {
                report.issue("error", "SOURCE_COLUMN_MISSING", entity.type(), null,
                    "missing columns: " + String.join(", ", missing));
            }
        }
        report.issue("info", "REDIS_EXPORT_REQUIRED", "conversations", null,
            "Nhs Redis message history and long-term memory require the separate cutover export step; database execution history is archived by this CLI");
    }

    /**
     * 校验{@code dForPhaseOne}，并在条件不满足时终止处理。
     *
     * @param type 业务类型
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean requiredForPhaseOne(String type) {
        return Set.of("users", "models", "agents", "agent_versions").contains(type);
    }

    /**
     * 处理凭据Handling并返回对应结果。
     *
     * @param type 业务类型
     * @return 处理结果
     */
    private String credentialHandling(String type) {
        return switch (type) {
            case "users" -> "passwords and API keys are not copied; newly created accounts are disabled";
            case "models", "mcp_servers", "api_tools", "data_sources" -> "secret values become env:NAME references and the target starts in testing/disabled state";
            default -> "no known secret field is copied; raw source rows are never written to reports";
        };
    }
}
