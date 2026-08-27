package group.aitools.nhs.migration.nhs;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表示{@code CliArguments}相关的领域对象。
 */
final class CliArguments {

    private static final Set<String> COMMANDS = Set.of("inventory", "migrate", "verify", "memory-import");
    private static final Set<String> ALLOWED = Set.of(
        "source-jdbc-url", "source-user", "source-schema", "source-version",
        "target-jdbc-url", "target-user", "target-version", "run-key", "run-id",
        "migration-type", "report-dir", "operator-id", "strict", "dry-run", "input"
    );

    private final String command;
    private final Map<String, String> values;

    /**
     * 创建 {@code CliArguments} 实例并初始化所需依赖。
     *
     * @param command 命令参数
     * @param values {@code values}参数
     */
    private CliArguments(String command, Map<String, String> values) {
        this.command = command;
        this.values = Map.copyOf(values);
    }

    /**
     * 处理{@code parse}并返回对应结果。
     *
     * @param args {@code args}参数
     * @param environment {@code environment}参数
     * @return 处理结果
     */
    static CliArguments parse(String[] args, Map<String, String> environment) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
            throw new UsageRequested();
        }
        String command = args[0].toLowerCase(Locale.ROOT);
        if (!COMMANDS.contains(command)) {
            throw new IllegalArgumentException("unknown command: " + command);
        }
        Map<String, String> values = defaults(environment);
        for (int index = 1; index < args.length; index++) {
            String argument = args[index];
            if (!argument.startsWith("--") || !argument.contains("=")) {
                throw new IllegalArgumentException("arguments must use --name=value: " + argument);
            }
            int separator = argument.indexOf('=');
            String key = argument.substring(2, separator);
            String value = argument.substring(separator + 1);
            if (key.toLowerCase(Locale.ROOT).contains("password")) {
                throw new IllegalArgumentException("database passwords are accepted only from environment variables");
            }
            if (!ALLOWED.contains(key)) {
                throw new IllegalArgumentException("unsupported argument: --" + key);
            }
            values.put(key, value);
        }
        values.putIfAbsent("migration-type", "verify".equals(command) ? "verify"
            : "memory-import".equals(command) ? "incremental" : "full");
        values.putIfAbsent("run-key", defaultRunKey(command));
        validate(command, values);
        return new CliArguments(command, values);
    }

    /**
     * 处理{@code defaults}并返回对应结果。
     *
     * @param environment {@code environment}参数
     * @return 处理结果
     */
    private static Map<String, String> defaults(Map<String, String> environment) {
        Map<String, String> result = new LinkedHashMap<>();
        putEnvironment(result, "source-jdbc-url", environment, "NHS_SOURCE_JDBC_URL");
        putEnvironment(result, "source-user", environment, "NHS_SOURCE_DB_USER");
        putEnvironment(result, "source-schema", environment, "NHS_SOURCE_DB_SCHEMA");
        putEnvironment(result, "source-version", environment, "NHS_SOURCE_VERSION");
        putEnvironment(result, "target-jdbc-url", environment, "NHS_TARGET_JDBC_URL");
        putEnvironment(result, "target-user", environment, "NHS_TARGET_DB_USER");
        putEnvironment(result, "target-version", environment, "NHS_TARGET_VERSION");
        putEnvironment(result, "report-dir", environment, "NHS_MIGRATION_REPORT_DIR");
        putEnvironment(result, "operator-id", environment, "NHS_MIGRATION_OPERATOR_ID");
        putEnvironment(result, "run-key", environment, "NHS_MIGRATION_RUN_KEY");
        result.putIfAbsent("source-schema", "public");
        result.putIfAbsent("source-version", "unknown");
        result.putIfAbsent("target-version", "6.0.0-phase1");
        result.putIfAbsent("report-dir", "build/migration-reports");
        result.putIfAbsent("operator-id", "1");
        result.putIfAbsent("strict", "true");
        result.putIfAbsent("dry-run", "false");
        return result;
    }

    /**
     * 处理{@code putEnvironment}相关逻辑。
     *
     * @param target {@code target}参数
     * @param key {@code key}参数
     * @param environment {@code environment}参数
     * @param environmentKey {@code environmentKey}参数
     */
    private static void putEnvironment(
        Map<String, String> target,
        String key,
        Map<String, String> environment,
        String environmentKey
    ) {
        String value = environment.get(environmentKey);
        if (value != null && !value.isBlank()) {
            target.put(key, value.strip());
        }
    }

    /**
     * 处理{@code defaultRunKey}并返回对应结果。
     *
     * @param command 命令参数
     * @return 处理结果
     */
    private static String defaultRunKey(String command) {
        return command + "-" + Instant.now().toString().replace(':', '-');
    }

    /**
     * 校验{@code validate}，并在条件不满足时终止处理。
     *
     * @param command 命令参数
     * @param values {@code values}参数
     */
    private static void validate(String command, Map<String, String> values) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (!"memory-import".equals(command)) {
            required(values, "source-jdbc-url");
            required(values, "source-user");
        }
        if (!"inventory".equals(command)) {
            required(values, "target-jdbc-url");
            required(values, "target-user");
        }
        if ("memory-import".equals(command)) {
            required(values, "input");
        }
        String type = values.get("migration-type");
        if (!Set.of("full", "incremental", "verify").contains(type)) {
            throw new IllegalArgumentException("migration-type must be full, incremental or verify");
        }
        parsePositiveLong(values.get("operator-id"), "operator-id");
        if (values.containsKey("run-id")) {
            parsePositiveLong(values.get("run-id"), "run-id");
        }
        parseBoolean(values.get("strict"), "strict");
        parseBoolean(values.get("dry-run"), "dry-run");
        if (values.get("source-schema").length() > 128 || values.get("run-key").length() > 128) {
            throw new IllegalArgumentException("source-schema and run-key must not exceed 128 characters");
        }
    }

    /**
     * 校验{@code d}，并在条件不满足时终止处理。
     *
     * @param values {@code values}参数
     * @param key {@code key}参数
     */
    private static void required(Map<String, String> values, String key) {
        if (values.get(key) == null || values.get(key).isBlank()) {
            throw new IllegalArgumentException("missing --" + key + " or its environment variable");
        }
    }

    /**
     * 处理{@code parsePositiveLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private static long parsePositiveLong(String value, String label) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a positive integer");
        }
    }

    /**
     * 处理{@code parseBoolean}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean parseBoolean(String value, String label) {
        if (!Set.of("true", "false").contains(value)) {
            throw new IllegalArgumentException(label + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * 处理命令并返回对应结果。
     *
     * @return 处理结果
     */
    String command() {
        return command;
    }

    /**
     * 处理数据源Url并返回对应结果。
     *
     * @return 处理结果
     */
    String sourceUrl() {
        return values.get("source-jdbc-url");
    }

    /**
     * 处理数据源用户并返回对应结果。
     *
     * @return 处理结果
     */
    String sourceUser() {
        return values.get("source-user");
    }

    /**
     * 处理数据源Password并返回对应结果。
     *
     * @param environment {@code environment}参数
     * @return 处理结果
     */
    String sourcePassword(Map<String, String> environment) {
        return requiredSecret(environment, "NHS_SOURCE_DB_PASSWORD");
    }

    /**
     * 处理数据源Schema并返回对应结果。
     *
     * @return 处理结果
     */
    String sourceSchema() {
        return values.get("source-schema");
    }

    /**
     * 处理数据源版本并返回对应结果。
     *
     * @return 处理结果
     */
    String sourceVersion() {
        return values.get("source-version");
    }

    /**
     * 处理{@code targetUrl}并返回对应结果。
     *
     * @return 处理结果
     */
    String targetUrl() {
        return values.get("target-jdbc-url");
    }

    /**
     * 处理target用户并返回对应结果。
     *
     * @return 处理结果
     */
    String targetUser() {
        return values.get("target-user");
    }

    /**
     * 处理{@code targetPassword}并返回对应结果。
     *
     * @param environment {@code environment}参数
     * @return 处理结果
     */
    String targetPassword(Map<String, String> environment) {
        return requiredSecret(environment, "NHS_TARGET_DB_PASSWORD");
    }

    /**
     * 处理target版本并返回对应结果。
     *
     * @return 处理结果
     */
    String targetVersion() {
        return values.get("target-version");
    }

    /**
     * 执行{@code Key}相关的处理流程。
     *
     * @return 处理结果
     */
    String runKey() {
        return values.get("run-key");
    }

    /**
     * 执行{@code Id}相关的处理流程。
     *
     * @return 处理结果
     */
    Long runId() {
        return values.containsKey("run-id") ? Long.valueOf(values.get("run-id")) : null;
    }

    /**
     * 处理迁移Type并返回对应结果。
     *
     * @return 处理结果
     */
    String migrationType() {
        return values.get("migration-type");
    }

    /**
     * 处理报表目录并返回对应结果。
     *
     * @return 处理结果
     */
    Path reportDirectory() {
        return Path.of(values.get("report-dir")).toAbsolutePath().normalize();
    }

    /**
     * 处理{@code input}并返回对应结果。
     *
     * @return 处理结果
     */
    Path input() {
        String value = values.get("input");
        return value == null ? null : Path.of(value).toAbsolutePath().normalize();
    }

    /**
     * 处理{@code operatorId}并返回对应结果。
     *
     * @return 处理结果
     */
    long operatorId() {
        return Long.parseLong(values.get("operator-id"));
    }

    /**
     * 处理{@code strict}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    boolean strict() {
        return Boolean.parseBoolean(values.get("strict"));
    }

    /**
     * 处理{@code dryRun}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    boolean dryRun() {
        return Boolean.parseBoolean(values.get("dry-run"));
    }

    /**
     * 校验{@code dSecret}，并在条件不满足时终止处理。
     *
     * @param environment {@code environment}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private static String requiredSecret(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing environment variable " + key);
        }
        return value;
    }

    /**
     * 处理{@code usage}并返回对应结果。
     *
     * @return 处理结果
     */
    static String usage() {
        return """
            Usage:
              java -jar nhs-migration-cli.jar inventory [options]
              java -jar nhs-migration-cli.jar migrate [options]
              java -jar nhs-migration-cli.jar verify --run-id=ID [options]
              java -jar nhs-migration-cli.jar memory-import --input=FILE [options]

            Required environment:
              NHS_SOURCE_JDBC_URL, NHS_SOURCE_DB_USER, NHS_SOURCE_DB_PASSWORD (except memory-import)
              NHS_TARGET_JDBC_URL, NHS_TARGET_DB_USER, NHS_TARGET_DB_PASSWORD (migrate/verify)

            Options use --name=value. Passwords are deliberately rejected on the command line.
              --source-schema=public --source-version=VERSION --target-version=VERSION
              --run-key=KEY --run-id=ID --migration-type=full|incremental|verify
              --report-dir=PATH --operator-id=ID --strict=true|false --dry-run=true|false
              --input=PATH (memory-import)
            """;
    }

    /**
     * 表示{@code UsageRequested}相关的领域对象。
     */
    static final class UsageRequested extends RuntimeException {
    }
}
