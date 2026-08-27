package group.aitools.nhs.migration.nhs;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 负责{@code NhsVerification}相关的业务编排与领域规则处理。
 */
final class NhsVerificationService {

    private final Connection source;
    private final String sourceSchema;
    private final MigrationTarget target;
    private final JsonCodec json;
    private final MigrationReport report;
    private final long verificationRunId;
    private final long subjectRunId;
    private final Map<String, String> sourceTables = new HashMap<>();
    private String subjectMigrationType;
    private boolean verifyTargetRowHashes;

    /**
     * 创建 {@code NhsVerificationService} 实例并初始化所需依赖。
     *
     * @param source 数据源参数
     * @param sourceSchema 数据源Schema参数
     * @param target {@code target}参数
     * @param json {@code json}参数
     * @param report 报表参数
     * @param verificationRunId 资源标识
     * @param subjectRunId 资源标识
     */
    NhsVerificationService(
        Connection source,
        String sourceSchema,
        MigrationTarget target,
        JsonCodec json,
        MigrationReport report,
        long verificationRunId,
        long subjectRunId
    ) {
        this.source = source;
        this.sourceSchema = sourceSchema;
        this.target = target;
        this.json = json;
        this.report = report;
        this.verificationRunId = verificationRunId;
        this.subjectRunId = subjectRunId;
        NhsSourceCatalog.ENTITIES.forEach(entity -> sourceTables.put(entity.type(), entity.sourceTable()));
    }

    /**
     * 校验{@code verify}，并在条件不满足时终止处理。
     *
     * @return 判断结果，{@code true} 表示条件成立
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    boolean verify() throws SQLException {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        subjectMigrationType = JdbcSupport.scalar(target.connection(),
            "SELECT migration_type FROM agent_migration_run WHERE id=? AND source_system='nhs'",
            String.class, subjectRunId);
        if (subjectMigrationType == null) {
            throw new IllegalArgumentException("subject migration run does not exist: " + subjectRunId);
        }
        String targetHashAlgorithm = JdbcSupport.scalar(target.connection(),
            "SELECT report_json->>'targetHashAlgorithm' FROM agent_migration_run WHERE id=?",
            String.class, subjectRunId);
        verifyTargetRowHashes = "canonical-row-v1".equals(targetHashAlgorithm);
        List<Map<String, Object>> groups = query("""
            SELECT source_type, target_type,
                   COUNT(*) AS mapping_count,
                   COUNT(*) FILTER (WHERE status='mapped') AS mapped_count,
                   COUNT(*) FILTER (WHERE status='skipped') AS skipped_count,
                   COUNT(*) FILTER (WHERE status='failed') AS failed_count
              FROM agent_migration_mapping
             WHERE migration_run_id=?
             GROUP BY source_type, target_type
             ORDER BY source_type, target_type
            """, subjectRunId);
        boolean passed = true;
        for (Map<String, Object> group : groups) {
            String sourceType = String.valueOf(group.get("source_type"));
            String targetType = String.valueOf(group.get("target_type"));
            long mappingCount = number(group.get("mapping_count"));
            long mappedCount = number(group.get("mapped_count"));
            long skippedCount = number(group.get("skipped_count"));
            long failedCount = number(group.get("failed_count"));
            long sourceCount = sourceCount(sourceType);
            long missingTargets = missingTargets(sourceType, targetType);
            long changedTargets = changedTargets(sourceType, targetType);
            List<String> targetHashes = hashes(sourceType, targetType, "target_hash");
            List<String> sourceHashes = hashes(sourceType, targetType, "source_hash");
            boolean entityPassed = sourceCount == mappingCount && failedCount == 0
                && missingTargets == 0 && changedTargets == 0;
            passed &= entityPassed;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("subjectRunId", subjectRunId);
            detail.put("targetType", targetType);
            detail.put("mappingCount", mappingCount);
            detail.put("missingTargets", missingTargets);
            detail.put("changedTargets", changedTargets);
            detail.put("sourceCountMatches", sourceCount == mappingCount);
            MigrationReport.EntityResult result = new MigrationReport.EntityResult(
                sourceType,
                "verify",
                entityPassed ? "passed" : "failed",
                sourceCount,
                mappedCount,
                0,
                mappedCount,
                skippedCount,
                failedCount + missingTargets + changedTargets,
                json.aggregateHash(sourceHashes),
                json.aggregateHash(targetHashes),
                Map.copyOf(detail)
            );
            report.add(result);
            target.persistResult(verificationRunId, result);
            if (sourceCount != mappingCount) {
                issue("error", "SOURCE_MAPPING_COUNT_MISMATCH", sourceType,
                    "source=" + sourceCount + ", mappings=" + mappingCount);
            }
            if (failedCount > 0) {
                issue("error", "FAILED_MAPPINGS_PRESENT", sourceType, "failed mappings=" + failedCount);
            }
            if (missingTargets > 0) {
                issue("error", "MAPPED_TARGET_MISSING", sourceType, "missing target rows=" + missingTargets);
            }
            if (changedTargets > 0) {
                issue("error", "MAPPED_TARGET_CHANGED", sourceType,
                    "target rows no longer match their migration hashes=" + changedTargets);
            }
        }
        if (groups.isEmpty()) {
            if ("incremental".equals(subjectMigrationType) && incrementalRunHasNoChanges()) {
                MigrationReport.EntityResult result = new MigrationReport.EntityResult(
                    "incremental_noop", "verify", "passed", 0, 0, 0, 0, 0, 0,
                    null, null, Map.of("subjectRunId", subjectRunId, "reason", "no source rows changed")
                );
                report.add(result);
                target.persistResult(verificationRunId, result);
            } else {
                issue("fatal", "NO_MAPPINGS", "migration", "subject run contains no migration mappings");
                passed = false;
            }
        }
        passed &= verifySecurityInvariants();
        JdbcSupport.update(target.connection(),
            "UPDATE agent_migration_run SET verification_status=? WHERE id=?",
            passed ? "passed" : "failed", subjectRunId);
        target.commit();
        return passed;
    }

    /**
     * 校验安全Invariants，并在条件不满足时终止处理。
     *
     * @return 判断结果，{@code true} 表示条件成立
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private boolean verifySecurityInvariants() throws SQLException {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<Invariant> invariants = List.of(
            invariant(
                "MIGRATED_USERS_DISABLED",
                "users",
                """
                    SELECT COUNT(*) FROM agent_migration_mapping m
                    JOIN sys_user u ON u.user_id=m.target_id
                    WHERE m.migration_run_id=? AND m.source_type='users' AND m.status='mapped'
                      AND u.status <> '1'
                    """
            ),
            invariant(
                "MIGRATED_SECRETS_ARE_REFERENCES",
                "credentials",
                """
                    SELECT
                      (SELECT COUNT(*) FROM agent_model
                        WHERE id IN (SELECT target_id FROM agent_migration_mapping WHERE migration_run_id=? AND target_type='model' AND status='mapped')
                          AND (credential_ref IS NULL OR credential_ref !~ '^env:[A-Z][A-Z0-9_]{0,127}$'))
                    + (SELECT COUNT(*) FROM agent_data_source
                        WHERE id IN (SELECT target_id FROM agent_migration_mapping WHERE migration_run_id=? AND target_type='data_source' AND status='mapped')
                          AND (credential_ref IS NULL OR credential_ref !~ '^env:[A-Z][A-Z0-9_]{0,127}$'))
                    """,
                subjectRunId,
                subjectRunId
            ),
            invariant(
                "MIGRATED_AUTOMATIONS_PAUSED",
                "scheduled_tasks",
                """
                    SELECT COUNT(*) FROM agent_automation_trigger
                     WHERE id IN (SELECT target_id FROM agent_migration_mapping WHERE migration_run_id=? AND target_type='automation_trigger' AND status='mapped')
                       AND status <> 'paused'
                    """
            ),
            invariant(
                "MIGRATED_MACHINE_IDENTITY_DISABLED",
                "scheduled_tasks",
                """
                    SELECT COUNT(*) FROM agent_service_account
                     WHERE account_key='nhs-migration-automation' AND status <> 'disabled'
                    """
            ),
            invariant(
                "MIGRATED_CONVERSATIONS_PRIVATE",
                "execution_history",
                """
                    SELECT COUNT(*) FROM agent_conversation
                     WHERE metadata_json->>'legacySource'='nhs' AND visibility <> 'private'
                    """
            ),
            invariant(
                "ARCHIVE_SECRET_FIELDS_REDACTED",
                "execution_history",
                """
                    SELECT COUNT(*) FROM agent_legacy_execution_archive
                     WHERE migration_run_id=?
                       AND payload_json::text ~* '"(password|password_hash|api_key|api_key_encrypted|api_key_hash|access_token|refresh_token|token|secret|credentials?)"[[:space:]]*:'
                    """
            ),
            invariant(
                "NO_MIGRATED_PLATFORM_ADMIN_GRANTS",
                "users",
                """
                    SELECT COUNT(*) FROM sys_user_role ur
                    JOIN sys_role r ON r.role_id=ur.role_id
                    WHERE ur.user_id IN (
                        SELECT target_id FROM agent_migration_mapping
                         WHERE migration_run_id=? AND target_type='user' AND status='mapped'
                    ) AND r.role_key IN ('admin','superadmin','platform_admin')
                    """
            )
        );
        boolean passed = true;
        for (Invariant invariant : invariants) {
            Long violations = JdbcSupport.scalar(
                target.connection(), invariant.sql(), Long.class, invariant.parameters()
            );
            long count = violations == null ? 0 : violations;
            boolean invariantPassed = count == 0;
            passed &= invariantPassed;
            MigrationReport.EntityResult result = new MigrationReport.EntityResult(
                "invariant:" + invariant.code().toLowerCase(Locale.ROOT),
                "verify",
                invariantPassed ? "passed" : "failed",
                1,
                invariantPassed ? 1 : 0,
                0,
                0,
                0,
                count,
                null,
                null,
                Map.of("subjectRunId", subjectRunId, "violations", count, "scope", invariant.scope())
            );
            report.add(result);
            target.persistResult(verificationRunId, result);
            if (!invariantPassed) {
                issue("fatal", invariant.code(), invariant.scope(), "violations=" + count);
            }
        }
        return passed;
    }

    /**
     * 处理数据源Count并返回对应结果。
     *
     * @param sourceType 业务类型
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private long sourceCount(String sourceType) throws SQLException {
        if ("incremental".equals(subjectMigrationType)) {
            Long count = JdbcSupport.scalar(target.connection(), """
                SELECT source_count FROM agent_migration_entity_stat
                 WHERE migration_run_id=? AND entity_type=? AND phase IN ('load','archive')
                 ORDER BY id DESC LIMIT 1
                """, Long.class, subjectRunId, sourceType);
            return count == null ? 0 : count;
        }
        String table = sourceTables.get(sourceType);
        if (table == null || !JdbcSupport.tableExists(source, sourceSchema, table)) {
            return 0;
        }
        return JdbcSupport.count(source, JdbcSupport.qualified(sourceSchema, table));
    }

    /**
     * 处理{@code missingTargets}并返回对应结果。
     *
     * @param sourceType 业务类型
     * @param targetType 业务类型
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private long missingTargets(String sourceType, String targetType) throws SQLException {
        MigrationTarget.TargetTable table = MigrationTarget.targetTable(targetType);
        if (table == null) {
            return JdbcSupport.scalar(target.connection(), """
                SELECT COUNT(*) FROM agent_migration_mapping
                 WHERE migration_run_id=? AND source_type=? AND target_type=?
                   AND status='mapped' AND target_id IS NULL
                """, Long.class, subjectRunId, sourceType, targetType);
        }
        String sql = "SELECT COUNT(*) FROM agent_migration_mapping m "
            + "LEFT JOIN " + JdbcSupport.quoteIdentifier(table.table()) + " t ON t."
            + JdbcSupport.quoteIdentifier(table.idColumn()) + "=m.target_id "
            + "WHERE m.migration_run_id=? AND m.source_type=? AND m.target_type=? "
            + "AND m.status='mapped' AND t." + JdbcSupport.quoteIdentifier(table.idColumn()) + " IS NULL";
        Long count = JdbcSupport.scalar(target.connection(), sql, Long.class, subjectRunId, sourceType, targetType);
        return count == null ? 0 : count;
    }

    /**
     * 更新{@code dTargets}。
     *
     * @param sourceType 业务类型
     * @param targetType 业务类型
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private long changedTargets(String sourceType, String targetType) throws SQLException {
        if (!verifyTargetRowHashes || MigrationTarget.targetTable(targetType) == null) {
            return 0;
        }
        List<Map<String, Object>> rows = query("""
            SELECT target_id, target_hash
              FROM agent_migration_mapping
             WHERE migration_run_id=? AND source_type=? AND target_type=?
               AND status='mapped' AND target_id IS NOT NULL
               AND target_hash IS NOT NULL
            """, subjectRunId, sourceType, targetType);
        long changed = 0;
        for (Map<String, Object> row : rows) {
            long targetId = number(row.get("target_id"));
            String expected = String.valueOf(row.get("target_hash"));
            String actual = target.currentTargetHash(targetType, targetId);
            if (actual != null && !expected.equals(actual)) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * 处理{@code incrementalRunHasNoChanges}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private boolean incrementalRunHasNoChanges() throws SQLException {
        Long failed = JdbcSupport.scalar(target.connection(), """
            SELECT COALESCE(SUM(failed_count), 0)::bigint
              FROM agent_migration_entity_stat
             WHERE migration_run_id=? AND phase IN ('load', 'archive')
            """, Long.class, subjectRunId);
        Long source = JdbcSupport.scalar(target.connection(), """
            SELECT COALESCE(SUM(source_count), 0)::bigint
              FROM agent_migration_entity_stat
             WHERE migration_run_id=? AND phase IN ('load', 'archive')
            """, Long.class, subjectRunId);
        return (failed == null || failed == 0) && (source == null || source == 0);
    }

    /**
     * 判断{@code hes}是否满足要求。
     *
     * @param sourceType 业务类型
     * @param targetType 业务类型
     * @param column {@code column}参数
     * @return 符合条件的数据集合
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private List<String> hashes(String sourceType, String targetType, String column) throws SQLException {
        String sql = "SELECT " + JdbcSupport.quoteIdentifier(column) + " FROM agent_migration_mapping "
            + "WHERE migration_run_id=? AND source_type=? AND target_type=? AND "
            + JdbcSupport.quoteIdentifier(column) + " IS NOT NULL ORDER BY source_id";
        try (var statement = target.connection().prepareStatement(sql)) {
            JdbcSupport.bind(statement, subjectRunId, sourceType, targetType);
            try (var result = statement.executeQuery()) {
                List<String> hashes = new ArrayList<>();
                while (result.next()) {
                    hashes.add(result.getString(1));
                }
                return hashes;
            }
        }
    }

    /**
     * 获取查询。
     *
     * @param sql {@code sql}参数
     * @param parameters {@code parameters}参数
     * @return 符合条件的数据集合
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private List<Map<String, Object>> query(String sql, Object... parameters) throws SQLException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try (var statement = target.connection().prepareStatement(sql)) {
            JdbcSupport.bind(statement, parameters);
            try (var result = statement.executeQuery()) {
                var metadata = result.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        row.put(metadata.getColumnLabel(index).toLowerCase(Locale.ROOT), result.getObject(index));
                    }
                    rows.add(Map.copyOf(row));
                }
                return rows;
            }
        }
    }

    /**
     * 判断{@code sue}是否满足要求。
     *
     * @param severity {@code severity}参数
     * @param code {@code code}参数
     * @param entity {@code entity}参数
     * @param summary {@code summary}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private void issue(String severity, String code, String entity, String summary) throws SQLException {
        MigrationReport.Issue issue = new MigrationReport.Issue(severity, code, entity, null, summary);
        report.issue(severity, code, entity, null, summary);
        target.persistIssue(verificationRunId, issue);
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    /**
     * 处理{@code invariant}并返回对应结果。
     *
     * @param code {@code code}参数
     * @param scope 范围参数
     * @param sql {@code sql}参数
     * @param parameters {@code parameters}参数
     * @return 处理结果
     */
    private Invariant invariant(String code, String scope, String sql, Object... parameters) {
        Object[] effective = parameters.length == 0 && sql.contains("?")
            ? new Object[]{subjectRunId} : parameters;
        return new Invariant(code, scope, sql, effective);
    }

    /**
     * 封装{@code Invariant}相关的不可变数据。
     */
    private record Invariant(String code, String scope, String sql, Object[] parameters) {
    }
}
