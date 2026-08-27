package group.aitools.nhs.platform.operations.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 提供Log存储Maintenance相关的数据访问能力。
 * PostgreSQL catalog inspection and bounded physical log maintenance. */
@Repository
public class LogStorageMaintenanceRepository {

    public static final int BATCH_SIZE = 1_000;
    public static final int MAX_ROWS_PER_TABLE = 50_000;
    public static final int FUTURE_MONTHS = 2;

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final Pattern PARTITION_UPPER_BOUND = Pattern.compile(
        "\\bTO\\s*\\(\\s*'([^']+)'(?:\\s*::[^)]*)?\\s*\\)", Pattern.CASE_INSENSITIVE
    );
    private static final Map<String, String> TABLES = Map.of(
        "agent_audit_event", "平台审计事件",
        "agent_execution_event", "执行 Trace 事件"
    );
    private static final String CATALOG_SQL = """
        SELECT COALESCE(child_namespace.nspname, n.nspname) AS schema_name,
               parent.relname AS table_name,
               parent.relkind = 'p' AS partitioned,
               child.relname AS child_name,
               CASE WHEN child.oid IS NULL THEN NULL
                    ELSE pg_get_expr(child.relpartbound, child.oid) END AS bound_expression,
               GREATEST(COALESCE(stats.n_live_tup, relation.reltuples, 0), 0)::bigint AS estimated_rows,
               pg_total_relation_size(relation.oid) AS size_bytes,
               pg_get_partkeydef(parent.oid) AS partition_key
        FROM pg_class parent
        JOIN pg_namespace n ON n.oid = parent.relnamespace
        LEFT JOIN pg_inherits inherited ON inherited.inhparent = parent.oid
        LEFT JOIN pg_class child ON child.oid = inherited.inhrelid
        LEFT JOIN pg_namespace child_namespace ON child_namespace.oid = child.relnamespace
        JOIN pg_class relation ON relation.oid = COALESCE(child.oid, parent.oid)
        LEFT JOIN pg_stat_all_tables stats ON stats.relid = relation.oid
        WHERE n.nspname = current_schema()
          AND parent.relname IN (?, ?)
          AND parent.relkind IN ('r', 'p')
        ORDER BY parent.relname, child.relname NULLS FIRST
        """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建 {@code LogStorageMaintenanceRepository} 实例并初始化所需依赖。
     *
     * @param jdbcTemplate jdbc模板参数
     */
    public LogStorageMaintenanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 处理{@code inspect}并返回对应结果。
     *
     * @param cutoffAt {@code cutoffAt}参数
     * @return 处理结果
     */
    @Transactional(readOnly = true)
    public StorageSnapshot inspect(LocalDateTime cutoffAt) {
        jdbcTemplate.execute("SET LOCAL statement_timeout = '5000ms'");
        return inspectCurrentConnection(cutoffAt);
    }

    /**
     * 处理{@code maintain}并返回对应结果。
     *
     * @param cutoffAt {@code cutoffAt}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public MaintenanceOutcome maintain(LocalDateTime cutoffAt) {
        jdbcTemplate.execute("SET LOCAL lock_timeout = '3000ms'");
        jdbcTemplate.execute("SET LOCAL statement_timeout = '30000ms'");
        StorageSnapshot before = inspectCurrentConnection(cutoffAt);
        List<String> createdPartitions = prepareFuturePartitions(before);
        List<TableCleanupResult> results = new ArrayList<>();
        for (TableStorageFact table : before.tables()) {
            results.add(cleanTable(table, cutoffAt));
        }
        return new MaintenanceOutcome(
            List.copyOf(createdPartitions),
            List.copyOf(results),
            results.stream().anyMatch(TableCleanupResult::remainingExpiredRows)
        );
    }

    /**
     * 处理inspect当前Connection并返回对应结果。
     *
     * @param cutoffAt {@code cutoffAt}参数
     * @return 处理结果
     */
    private StorageSnapshot inspectCurrentConnection(LocalDateTime cutoffAt) {
        List<PhysicalRelation> relations = jdbcTemplate.query(
            CATALOG_SQL,
            (resultSet, rowNum) -> new PhysicalRelation(
                resultSet.getString("schema_name"),
                resultSet.getString("table_name"),
                resultSet.getBoolean("partitioned"),
                resultSet.getString("child_name"),
                resultSet.getString("bound_expression"),
                resultSet.getLong("estimated_rows"),
                resultSet.getLong("size_bytes"),
                resultSet.getString("partition_key")
            ),
            "agent_audit_event", "agent_execution_event"
        );
        Map<String, List<PhysicalRelation>> grouped = new LinkedHashMap<>();
        relations.forEach(value -> grouped.computeIfAbsent(value.tableName(), ignored -> new ArrayList<>()).add(value));
        List<TableStorageFact> tables = new ArrayList<>();
        for (String tableName : List.of("agent_audit_event", "agent_execution_event")) {
            List<PhysicalRelation> physical = grouped.get(tableName);
            if (physical == null || physical.isEmpty()) {
                throw new IllegalStateException("日志表不存在: " + tableName);
            }
            tables.add(inspectTable(tableName, physical, cutoffAt));
        }
        return new StorageSnapshot(LocalDateTime.now(), cutoffAt, List.copyOf(tables));
    }

    /**
     * 处理{@code inspectTable}并返回对应结果。
     *
     * @param tableName 名称
     * @param physical {@code physical}参数
     * @param cutoffAt {@code cutoffAt}参数
     * @return 处理结果
     */
    private TableStorageFact inspectTable(
        String tableName,
        List<PhysicalRelation> physical,
        LocalDateTime cutoffAt
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        boolean partitioned = physical.getFirst().partitioned();
        List<PartitionStorageFact> partitions = new ArrayList<>();
        for (PhysicalRelation relation : physical) {
            if (partitioned && relation.childName() == null) {
                continue;
            }
            String relationName = partitioned ? relation.childName() : relation.tableName();
            RelationTimeRange range = range(relation.schemaName(), relationName);
            long expiredRows = range.oldestAt() != null && range.oldestAt().isBefore(cutoffAt)
                ? countExpired(relation.schemaName(), relationName, cutoffAt) : 0;
            boolean defaultPartition = "DEFAULT".equalsIgnoreCase(relation.boundExpression());
            LocalDateTime upperBound = partitionUpperBound(relation.boundExpression());
            boolean removable = partitioned && !defaultPartition && upperBound != null
                && !upperBound.isAfter(cutoffAt)
                && (range.newestAt() == null || range.newestAt().isBefore(cutoffAt));
            partitions.add(new PartitionStorageFact(
                relation.schemaName(), relationName,
                partitioned ? relation.boundExpression() : "UNPARTITIONED",
                defaultPartition, relation.estimatedRows(), relation.sizeBytes(),
                range.oldestAt(), range.newestAt(), expiredRows, removable
            ));
        }
        partitions.sort(Comparator.comparing(PartitionStorageFact::partitionName));
        return new TableStorageFact(
            tableName,
            TABLES.get(tableName),
            partitioned,
            physical.getFirst().partitionKey(),
            partitions.stream().mapToLong(PartitionStorageFact::estimatedRows).sum(),
            partitions.stream().mapToLong(PartitionStorageFact::sizeBytes).sum(),
            minimum(partitions),
            maximum(partitions),
            partitions.stream().mapToLong(PartitionStorageFact::expiredRows).sum(),
            List.copyOf(partitions)
        );
    }

    /**
     * 处理{@code prepareFuturePartitions}并返回对应结果。
     *
     * @param snapshot 快照参数
     * @return 符合条件的数据集合
     */
    private List<String> prepareFuturePartitions(StorageSnapshot snapshot) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<String> created = new ArrayList<>();
        YearMonth current = YearMonth.now();
        for (TableStorageFact table : snapshot.tables()) {
            if (!table.partitioned() || !isSupportedPartitionKey(table.partitionKey())) {
                continue;
            }
            Set<String> existing = new LinkedHashSet<>();
            table.partitions().forEach(value -> existing.add(value.partitionName()));
            for (int offset = 0; offset <= FUTURE_MONTHS; offset++) {
                YearMonth month = current.plusMonths(offset);
                String partitionName = table.tableName() + "_p" + month.format(DateTimeFormatter.ofPattern("yyyyMM"));
                if (existing.contains(partitionName)) {
                    continue;
                }
                LocalDate from = month.atDay(1);
                LocalDate to = month.plusMonths(1).atDay(1);
                String sql = "CREATE TABLE " + quoted(partitionName)
                    + " PARTITION OF " + quoted(table.tableName())
                    + " FOR VALUES FROM ('" + from + " 00:00:00') TO ('" + to + " 00:00:00')";
                jdbcTemplate.execute(sql);
                created.add(partitionName);
            }
        }
        return created;
    }

    /**
     * 处理{@code cleanTable}并返回对应结果。
     *
     * @param table {@code table}参数
     * @param cutoffAt {@code cutoffAt}参数
     * @return 处理结果
     */
    private TableCleanupResult cleanTable(TableStorageFact table, LocalDateTime cutoffAt) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<String> dropped = new ArrayList<>();
        long droppedRows = 0;
        Set<String> removedRelations = new LinkedHashSet<>();
        for (PartitionStorageFact partition : table.partitions()) {
            if (!partition.removableCandidate()) {
                continue;
            }
            String qualified = qualified(partition.schemaName(), partition.partitionName());
            jdbcTemplate.execute("LOCK TABLE " + qualified + " IN ACCESS EXCLUSIVE MODE");
            LocalDateTime newest = range(partition.schemaName(), partition.partitionName()).newestAt();
            LocalDateTime upperBound = partitionUpperBound(partition.boundExpression());
            if (upperBound != null && !upperBound.isAfter(cutoffAt)
                && (newest == null || newest.isBefore(cutoffAt))) {
                long count = countAll(partition.schemaName(), partition.partitionName());
                jdbcTemplate.execute("DROP TABLE " + qualified);
                dropped.add(partition.partitionName());
                droppedRows += count;
                removedRelations.add(partition.partitionName());
            }
        }

        int remainingAllowance = MAX_ROWS_PER_TABLE;
        long deletedRows = 0;
        boolean remaining = false;
        for (PartitionStorageFact partition : table.partitions()) {
            if (removedRelations.contains(partition.partitionName()) || partition.oldestAt() == null
                || !partition.oldestAt().isBefore(cutoffAt)) {
                continue;
            }
            while (remainingAllowance > 0) {
                int limit = Math.min(BATCH_SIZE, remainingAllowance);
                int deleted = deleteBatch(partition.schemaName(), partition.partitionName(), cutoffAt, limit);
                deletedRows += deleted;
                remainingAllowance -= deleted;
                if (deleted < limit) {
                    break;
                }
            }
            if (hasExpired(partition.schemaName(), partition.partitionName(), cutoffAt)) {
                remaining = true;
            }
            if (remainingAllowance == 0) {
                break;
            }
        }
        if (remainingAllowance == 0) {
            remaining = hasExpired(table.tableName(), cutoffAt);
        }
        return new TableCleanupResult(
            table.tableName(), List.copyOf(dropped), droppedRows, deletedRows, remaining
        );
    }

    /**
     * 处理{@code range}并返回对应结果。
     *
     * @param schemaName 名称
     * @param relationName 名称
     * @return 处理结果
     */
    private RelationTimeRange range(String schemaName, String relationName) {
        String sql = "SELECT MIN(created_at), MAX(created_at) FROM " + qualified(schemaName, relationName);
        return jdbcTemplate.queryForObject(sql, (resultSet, rowNum) -> new RelationTimeRange(
            localDateTime(resultSet.getTimestamp(1)), localDateTime(resultSet.getTimestamp(2))
        ));
    }

    /**
     * 处理{@code countExpired}并返回对应结果。
     *
     * @param schemaName 名称
     * @param relationName 名称
     * @param cutoffAt {@code cutoffAt}参数
     * @return 处理结果
     */
    private long countExpired(String schemaName, String relationName, LocalDateTime cutoffAt) {
        Long value = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + qualified(schemaName, relationName) + " WHERE created_at < ?",
            Long.class, Timestamp.valueOf(cutoffAt)
        );
        return value == null ? 0 : value;
    }

    /**
     * 处理{@code countAll}并返回对应结果。
     *
     * @param schemaName 名称
     * @param relationName 名称
     * @return 处理结果
     */
    private long countAll(String schemaName, String relationName) {
        Long value = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + qualified(schemaName, relationName), Long.class
        );
        return value == null ? 0 : value;
    }

    /**
     * 删除{@code Batch}。
     *
     * @param schemaName 名称
     * @param relationName 名称
     * @param cutoffAt {@code cutoffAt}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    private int deleteBatch(
        String schemaName,
        String relationName,
        LocalDateTime cutoffAt,
        int limit
    ) {
        String relation = qualified(schemaName, relationName);
        return jdbcTemplate.update(
            "DELETE FROM " + relation + " WHERE ctid IN (SELECT ctid FROM " + relation
                + " WHERE created_at < ? ORDER BY created_at LIMIT ?)",
            Timestamp.valueOf(cutoffAt), limit
        );
    }

    /**
     * 判断{@code Expired}是否满足要求。
     *
     * @param schemaName 名称
     * @param relationName 名称
     * @param cutoffAt {@code cutoffAt}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean hasExpired(String schemaName, String relationName, LocalDateTime cutoffAt) {
        Boolean value = jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM " + qualified(schemaName, relationName)
                + " WHERE created_at < ? LIMIT 1)",
            Boolean.class, Timestamp.valueOf(cutoffAt)
        );
        return Boolean.TRUE.equals(value);
    }

    /**
     * 判断{@code Expired}是否满足要求。
     *
     * @param tableName 名称
     * @param cutoffAt {@code cutoffAt}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean hasExpired(String tableName, LocalDateTime cutoffAt) {
        return hasExpired("public", tableName, cutoffAt);
    }

    /**
     * 判断{@code SupportedPartitionKey}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    static boolean isSupportedPartitionKey(String value) {
        if (value == null) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).replace("\"", "").replaceAll("\\s+", "")
            .equals("range(created_at)");
    }

    /**
     * 处理{@code partitionUpperBound}并返回对应结果。
     *
     * @param expression {@code expression}参数
     * @return 处理结果
     */
    static LocalDateTime partitionUpperBound(String expression) {
        if (expression == null) {
            return null;
        }
        Matcher matcher = PARTITION_UPPER_BOUND.matcher(expression);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).strip();
        try {
            return value.length() == 10
                ? LocalDate.parse(value).atStartOfDay()
                : LocalDateTime.parse(value.replace(' ', 'T'));
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /**
     * 处理{@code qualified}并返回对应结果。
     *
     * @param schemaName 名称
     * @param relationName 名称
     * @return 处理结果
     */
    private String qualified(String schemaName, String relationName) {
        return quoted(schemaName) + "." + quoted(relationName);
    }

    /**
     * 处理{@code quoted}并返回对应结果。
     *
     * @param identifier {@code identifier}参数
     * @return 处理结果
     */
    private String quoted(String identifier) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalStateException("PostgreSQL 返回了无效的日志关系标识");
        }
        return '"' + identifier + '"';
    }

    /**
     * 处理{@code localDateTime}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    /**
     * 处理{@code minimum}并返回对应结果。
     *
     * @param values {@code values}参数
     * @return 处理结果
     */
    private LocalDateTime minimum(List<PartitionStorageFact> values) {
        return values.stream().map(PartitionStorageFact::oldestAt).filter(java.util.Objects::nonNull)
            .min(LocalDateTime::compareTo).orElse(null);
    }

    /**
     * 处理{@code maximum}并返回对应结果。
     *
     * @param values {@code values}参数
     * @return 处理结果
     */
    private LocalDateTime maximum(List<PartitionStorageFact> values) {
        return values.stream().map(PartitionStorageFact::newestAt).filter(java.util.Objects::nonNull)
            .max(LocalDateTime::compareTo).orElse(null);
    }

    /**
     * 封装{@code PhysicalRelation}相关的不可变数据。
     */
    private record PhysicalRelation(
        String schemaName,
        String tableName,
        boolean partitioned,
        String childName,
        String boundExpression,
        long estimatedRows,
        long sizeBytes,
        String partitionKey
    ) {
    }

    /**
     * 封装{@code RelationTimeRange}相关的不可变数据。
     */
    private record RelationTimeRange(LocalDateTime oldestAt, LocalDateTime newestAt) {
    }

    /**
     * 封装存储快照相关的不可变数据。
     */
    public record StorageSnapshot(
        LocalDateTime checkedAt,
        LocalDateTime cutoffAt,
        List<TableStorageFact> tables
    ) {
    }

    /**
     * 封装Table存储Fact相关的不可变数据。
     */
    public record TableStorageFact(
        String tableName,
        String displayName,
        boolean partitioned,
        String partitionKey,
        long estimatedRows,
        long sizeBytes,
        LocalDateTime oldestAt,
        LocalDateTime newestAt,
        long expiredRows,
        List<PartitionStorageFact> partitions
    ) {
    }

    /**
     * 封装Partition存储Fact相关的不可变数据。
     */
    public record PartitionStorageFact(
        String schemaName,
        String partitionName,
        String boundExpression,
        boolean defaultPartition,
        long estimatedRows,
        long sizeBytes,
        LocalDateTime oldestAt,
        LocalDateTime newestAt,
        long expiredRows,
        boolean removableCandidate
    ) {
    }

    /**
     * 封装{@code MaintenanceOutcome}相关的不可变数据。
     */
    public record MaintenanceOutcome(
        List<String> createdPartitions,
        List<TableCleanupResult> tables,
        boolean remainingExpiredRows
    ) {
        /**
         * 删除{@code dRows}。
         *
         * @return 处理结果
         */
        public long deletedRows() {
            return tables.stream().mapToLong(TableCleanupResult::deletedRows).sum();
        }

        /**
         * 处理{@code droppedRows}并返回对应结果。
         *
         * @return 处理结果
         */
        public long droppedRows() {
            return tables.stream().mapToLong(TableCleanupResult::droppedRows).sum();
        }

        /**
         * 处理{@code droppedPartitions}并返回对应结果。
         *
         * @return 符合条件的数据集合
         */
        public List<String> droppedPartitions() {
            return tables.stream().flatMap(value -> value.droppedPartitions().stream()).toList();
        }
    }

    /**
     * 封装{@code TableCleanup}相关的不可变数据。
     */
    public record TableCleanupResult(
        String tableName,
        List<String> droppedPartitions,
        long droppedRows,
        long deletedRows,
        boolean remainingExpiredRows
    ) {
    }
}
