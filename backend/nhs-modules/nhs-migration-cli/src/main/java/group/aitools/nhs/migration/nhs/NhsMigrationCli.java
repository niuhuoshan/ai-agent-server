package group.aitools.nhs.migration.nhs;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

/**
 * 表示Nhs迁移Cli相关的领域对象。
 */
public final class NhsMigrationCli {

    /**
     * 创建 {@code NhsMigrationCli} 实例并初始化所需依赖。
     */
    private NhsMigrationCli() {
    }

    /**
     * 处理{@code main}相关逻辑。
     *
     * @param args {@code args}参数
     */
    public static void main(String[] args) {
        int status = run(args, System.getenv());
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * 执行{@code run}相关的处理流程。
     *
     * @param args {@code args}参数
     * @param environment {@code environment}参数
     * @return 处理结果
     */
    static int run(String[] args, Map<String, String> environment) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CliArguments arguments;
        try {
            arguments = CliArguments.parse(args, environment);
        } catch (CliArguments.UsageRequested requested) {
            System.out.print(CliArguments.usage());
            return 0;
        } catch (IllegalArgumentException exception) {
            System.err.println("Configuration error: " + exception.getMessage());
            System.err.print(CliArguments.usage());
            return 2;
        }

        JsonCodec json = new JsonCodec();
        MigrationReport report = new MigrationReport(json, arguments.command(), arguments.runKey());
        try {
            return switch (arguments.command()) {
                case "inventory" -> inventory(arguments, environment, json, report);
                case "migrate" -> migrate(arguments, environment, json, report);
                case "verify" -> verify(arguments, environment, json, report);
                case "memory-import" -> memoryImport(arguments, environment, json, report);
                default -> throw new IllegalStateException("unsupported command");
            };
        } catch (Exception exception) {
            report.issue("fatal", "COMMAND_FAILED", "migration", null, safeMessage(exception));
            report.finish(false);
            try {
                MigrationReport.ReportFiles files = report.write(arguments.reportDirectory());
                System.err.println("Migration failed; report: " + files.markdown());
            } catch (RuntimeException reportFailure) {
                System.err.println("Migration failed and the report could not be written: " + safeMessage(reportFailure));
            }
            System.err.println("Migration error: " + safeMessage(exception));
            return 1;
        }
    }

    /**
     * 处理{@code inventory}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @param environment {@code environment}参数
     * @param json {@code json}参数
     * @param report 报表参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private static int inventory(
        CliArguments arguments,
        Map<String, String> environment,
        JsonCodec json,
        MigrationReport report
    ) throws SQLException {
        try (Connection source = connect(
            arguments.sourceUrl(), arguments.sourceUser(), arguments.sourcePassword(environment)
        )) {
            source.setReadOnly(true);
            new NhsInventoryService(json).inventory(source, arguments.sourceSchema(), report, arguments.strict());
        }
        report.finish(report.canPass() || !arguments.strict());
        MigrationReport.ReportFiles files = report.write(arguments.reportDirectory());
        System.out.println("Inventory report: " + files.markdown());
        return report.passed() || !arguments.strict() ? 0 : 3;
    }

    /**
     * 处理{@code migrate}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @param environment {@code environment}参数
     * @param json {@code json}参数
     * @param report 报表参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private static int migrate(
        CliArguments arguments,
        Map<String, String> environment,
        JsonCodec json,
        MigrationReport report
    ) throws SQLException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try (Connection source = connect(
                 arguments.sourceUrl(), arguments.sourceUser(), arguments.sourcePassword(environment)
             );
             Connection targetConnection = connect(
                 arguments.targetUrl(), arguments.targetUser(), arguments.targetPassword(environment)
             )) {
            source.setReadOnly(true);
            targetConnection.setAutoCommit(false);
            MigrationTarget target = new MigrationTarget(targetConnection, json);
            target.verifySchema();
            if (arguments.dryRun()) {
                new NhsInventoryService(json).inventory(source, arguments.sourceSchema(), report, arguments.strict());
                report.issue("info", "DRY_RUN", "migration", null, "target schema was checked but no target data was changed");
                report.finish(report.canPass() || !arguments.strict());
                MigrationReport.ReportFiles files = report.write(arguments.reportDirectory());
                System.out.println("Dry-run report: " + files.markdown());
                return report.passed() || !arguments.strict() ? 0 : 3;
            }
            if (!target.acquireLock()) {
                throw new IllegalStateException("another Nhs migration holds the target advisory lock");
            }
            long runId = 0;
            try {
                MigrationReport inventory = new MigrationReport(json, "inventory", arguments.runKey() + "-manifest");
                new NhsInventoryService(json).inventory(source, arguments.sourceSchema(), inventory, arguments.strict());
                inventory.finish(inventory.canPass() || !arguments.strict());
                String manifestHash = String.valueOf(inventory.payload().get("reportHash"));
                runId = target.createRun(arguments, manifestHash);
                target.commit();
                report.runId(runId);
                NhsMigrationEngine engine = new NhsMigrationEngine(
                    source, arguments.sourceSchema(), target, json, report, runId,
                    arguments.operatorId(), arguments.strict(), arguments.migrationType()
                );
                engine.migrate();
                boolean succeeded = report.canPass() || !arguments.strict();
                report.finish(succeeded);
                target.finishRun(runId, report, succeeded);
                target.commit();
                MigrationReport.ReportFiles files = report.write(arguments.reportDirectory());
                System.out.println("Migration run " + runId + " report: " + files.markdown());
                return succeeded ? 0 : 3;
            } catch (Exception exception) {
                target.rollback();
                report.issue("fatal", "MIGRATION_ABORTED", "migration", null, safeMessage(exception));
                report.finish(false);
                if (runId > 0) {
                    try {
                        target.finishRun(runId, report, false);
                        target.commit();
                    } catch (Exception finishFailure) {
                        target.rollback();
                    }
                }
                throw exception;
            } finally {
                target.releaseLock();
            }
        }
    }

    /**
     * 校验{@code verify}，并在条件不满足时终止处理。
     *
     * @param arguments {@code arguments}参数
     * @param environment {@code environment}参数
     * @param json {@code json}参数
     * @param report 报表参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private static int verify(
        CliArguments arguments,
        Map<String, String> environment,
        JsonCodec json,
        MigrationReport report
    ) throws SQLException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (arguments.runId() == null) {
            throw new IllegalArgumentException("verify requires --run-id=ID");
        }
        try (Connection source = connect(
                 arguments.sourceUrl(), arguments.sourceUser(), arguments.sourcePassword(environment)
             );
             Connection targetConnection = connect(
                 arguments.targetUrl(), arguments.targetUser(), arguments.targetPassword(environment)
             )) {
            source.setReadOnly(true);
            targetConnection.setAutoCommit(false);
            MigrationTarget target = new MigrationTarget(targetConnection, json);
            target.verifySchema();
            if (!target.acquireLock()) {
                throw new IllegalStateException("another Nhs migration holds the target advisory lock");
            }
            long verificationRunId = 0;
            try {
                verificationRunId = target.createRun(arguments, json.sha256(Map.of("subjectRunId", arguments.runId())));
                target.commit();
                report.runId(verificationRunId);
                boolean passed = new NhsVerificationService(
                    source, arguments.sourceSchema(), target, json, report,
                    verificationRunId, arguments.runId()
                ).verify();
                report.finish(passed);
                target.finishRun(verificationRunId, report, passed);
                target.commit();
                MigrationReport.ReportFiles files = report.write(arguments.reportDirectory());
                System.out.println("Verification run " + verificationRunId + " report: " + files.markdown());
                return passed ? 0 : 3;
            } catch (Exception exception) {
                target.rollback();
                report.issue("fatal", "VERIFICATION_ABORTED", "verification", null, safeMessage(exception));
                report.finish(false);
                if (verificationRunId > 0) {
                    try {
                        target.finishRun(verificationRunId, report, false);
                        target.commit();
                    } catch (Exception finishFailure) {
                        target.rollback();
                    }
                }
                throw exception;
            } finally {
                target.releaseLock();
            }
        }
    }

    /**
     * 处理记忆导入并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @param environment {@code environment}参数
     * @param json {@code json}参数
     * @param report 报表参数
     * @return 处理结果
     * @throws Exception 当处理过程无法正常完成时抛出
     */
    private static int memoryImport(
        CliArguments arguments,
        Map<String, String> environment,
        JsonCodec json,
        MigrationReport report
    ) throws Exception {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try (Connection targetConnection = connect(
            arguments.targetUrl(), arguments.targetUser(), arguments.targetPassword(environment)
        )) {
            targetConnection.setAutoCommit(false);
            MigrationTarget target = new MigrationTarget(targetConnection, json);
            target.verifySchema();
            if (!target.acquireLock()) {
                throw new IllegalStateException("another Nhs migration holds the target advisory lock");
            }
            long runId = 0;
            try {
                String manifestHash = json.sha256(Map.of(
                    "input", arguments.input().getFileName().toString(),
                    "size", java.nio.file.Files.size(arguments.input()),
                    "modified", java.nio.file.Files.getLastModifiedTime(arguments.input()).toMillis()
                ));
                runId = target.createRun(arguments, manifestHash);
                target.commit();
                report.runId(runId);
                boolean succeeded = new NhsMemoryImportService(
                    arguments.input(), target, json, report, runId
                ).importRecords();
                report.finish(succeeded);
                target.finishRun(runId, report, succeeded);
                target.commit();
                MigrationReport.ReportFiles files = report.write(arguments.reportDirectory());
                System.out.println("Memory import run " + runId + " report: " + files.markdown());
                return succeeded ? 0 : 3;
            } catch (Exception exception) {
                target.rollback();
                report.issue("fatal", "MEMORY_IMPORT_ABORTED", "memory", null, safeMessage(exception));
                report.finish(false);
                if (runId > 0) {
                    try {
                        target.finishRun(runId, report, false);
                        target.commit();
                    } catch (Exception finishFailure) {
                        target.rollback();
                    }
                }
                throw exception;
            } finally {
                target.releaseLock();
            }
        }
    }

    /**
     * 处理{@code connect}并返回对应结果。
     *
     * @param url {@code url}参数
     * @param user 用户参数
     * @param password {@code password}参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private static Connection connect(String url, String user, String password) throws SQLException {
        Connection connection = DriverManager.getConnection(url, user, password);
        connection.setNetworkTimeout(Runnable::run, 30_000);
        return connection;
    }

    /**
     * 处理safe消息并返回对应结果。
     *
     * @param throwable {@code throwable}参数
     * @return 处理结果
     */
    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        if (value == null || value.isBlank()) {
            value = throwable.getClass().getSimpleName();
        }
        value = value.replaceAll("(?i)(password|secret|token|api[_-]?key)\\s*[=:]\\s*[^,;\\s]+", "$1=<redacted>");
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
