package group.aitools.nhs.platform.operations.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.operations.web.SystemDiagnosticCheckView;
import group.aitools.nhs.platform.operations.web.SystemDiagnosticsView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责系统Diagnostics相关的业务编排与领域规则处理。
 * Deep, read-only diagnostics for persistence, workers and optional providers. */
@Service
public class SystemDiagnosticsApplicationService {

    private static final String HEALTHY = "healthy";
    private static final String DEGRADED = "degraded";
    private static final String UNAVAILABLE = "unavailable";
    private static final String DISABLED = "disabled";
    private static final Pattern MIGRATION_VERSION = Pattern.compile("V(\\d+)__.+\\.sql");

    private final CurrentPrincipalProvider principalProvider;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final AgentAuditEventMapper auditMapper;
    private final PlatformIdGenerator idGenerator;
    private final boolean sandboxRequired;
    private final String expectedSchemaVersion;
    private final Clock clock;

    @Autowired
    public SystemDiagnosticsApplicationService(
        CurrentPrincipalProvider principalProvider,
        ObjectProvider<DataSource> dataSourceProvider,
        AgentAuditEventMapper auditMapper,
        PlatformIdGenerator idGenerator,
        @Value("${agent.platform.sandbox-required:false}") boolean sandboxRequired
    ) {
        this(
            principalProvider, dataSourceProvider, auditMapper, idGenerator,
            sandboxRequired, resolveExpectedSchemaVersion(), Clock.systemUTC()
        );
    }

    /**
     * 创建 {@code SystemDiagnosticsApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param dataSourceProvider 数据数据源提供方参数
     * @param auditMapper 审计Mapper参数
     * @param idGenerator {@code idGenerator}参数
     * @param sandboxRequired 沙箱Required参数
     * @param expectedSchemaVersion expectedSchema版本参数
     * @param clock {@code clock}参数
     */
    SystemDiagnosticsApplicationService(
        CurrentPrincipalProvider principalProvider,
        ObjectProvider<DataSource> dataSourceProvider,
        AgentAuditEventMapper auditMapper,
        PlatformIdGenerator idGenerator,
        boolean sandboxRequired,
        String expectedSchemaVersion,
        Clock clock
    ) {
        this.principalProvider = principalProvider;
        this.dataSourceProvider = dataSourceProvider;
        this.auditMapper = auditMapper;
        this.idGenerator = idGenerator;
        this.sandboxRequired = sandboxRequired;
        this.expectedSchemaVersion = expectedSchemaVersion;
        this.clock = clock;
    }

    /**
     * 处理{@code diagnostics}并返回对应结果。
     *
     * @return 处理结果
     */
    public SystemDiagnosticsView diagnostics() {
        CurrentPrincipal principal = requireAdministrator();
        List<SystemDiagnosticCheckView> checks = new ArrayList<>();
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            checks.add(unavailable("schema", "数据库结构版本", true, "未加载主数据库连接",
                "检查数据源配置并确认 PostgreSQL 已启动"));
            addSkippedDatabaseChecks(checks);
            return finish(principal, checks);
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(2)) {
                checks.add(unavailable("schema", "数据库结构版本", true, "主数据库连接校验失败",
                    "检查 PostgreSQL 网络、账号和连接池配置"));
                addSkippedDatabaseChecks(checks);
                return finish(principal, checks);
            }
            checks.add(schema(connection));
            checks.add(outbox(connection));
            checks.add(leases(connection));
            checks.add(sandbox(connection));
            checks.add(providers(connection));
            checks.add(searchCircuits(connection));
        } catch (Exception exception) {
            checks.clear();
            checks.add(unavailable("schema", "数据库结构版本", true, "深度诊断无法连接主数据库",
                "检查 PostgreSQL 连接和 Flyway 初始化状态"));
            addSkippedDatabaseChecks(checks);
        }
        return finish(principal, checks);
    }

    /**
     * 处理{@code schema}并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @return 处理结果
     */
    private SystemDiagnosticCheckView schema(Connection connection) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String sql = "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return unavailable("schema", "数据库结构版本", true, "Flyway 尚未记录任何迁移",
                    "执行应用内置 Flyway 迁移后再启动业务流量");
            }
            String current = result.getString("version");
            boolean success = result.getBoolean("success");
            Map<String, Object> metrics = metrics("currentVersion", current, "expectedVersion", expectedSchemaVersion);
            if (!success) {
                return check("schema", "数据库结构版本", UNAVAILABLE, true,
                    "最近一次 Flyway 迁移失败", metrics, "修复失败迁移并执行 flyway repair/migrate");
            }
            int comparison = compareVersions(current, expectedSchemaVersion);
            if (comparison < 0) {
                return check("schema", "数据库结构版本", UNAVAILABLE, true,
                    "数据库版本低于当前应用要求", metrics, "执行当前发布包内的全部 Flyway 迁移");
            }
            if (comparison > 0) {
                return check("schema", "数据库结构版本", UNAVAILABLE, true,
                    "数据库版本高于当前应用包", metrics, "使用匹配该数据库版本的应用包，禁止旧应用继续写入");
            }
            return check("schema", "数据库结构版本", HEALTHY, true,
                "Flyway 版本与当前应用包一致", metrics, null);
        } catch (Exception exception) {
            return unavailable("schema", "数据库结构版本", true, "Flyway 版本读取失败",
                "检查 flyway_schema_history 表和数据库查询权限");
        }
    }

    /**
     * 处理{@code outbox}并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @return 处理结果
     */
    private SystemDiagnosticCheckView outbox(Connection connection) {
        String sql = """
            SELECT count(*) FILTER (WHERE status='pending') AS pending,
                   count(*) FILTER (WHERE status='failed') AS failed,
                   count(*) FILTER (WHERE status='pending' AND next_attempt_at <= CURRENT_TIMESTAMP) AS due
              FROM agent_outbox_event
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            long pending = result.getLong("pending");
            long failed = result.getLong("failed");
            long due = result.getLong("due");
            Map<String, Object> metrics = metrics("pending", pending, "failed", failed, "due", due);
            if (due >= 500 || failed >= 100) {
                return check("outbox", "业务事件 Outbox", UNAVAILABLE, true,
                    "Outbox 已严重堆积", metrics, "检查发布 Worker、数据库锁和失败事件，恢复后重放");
            }
            if (due > 0 || failed > 0) {
                return check("outbox", "业务事件 Outbox", DEGRADED, true,
                    "Outbox 存在待重试或失败事件", metrics, "检查事件错误摘要和 Worker 运行状态");
            }
            return check("outbox", "业务事件 Outbox", HEALTHY, true,
                "Outbox 无到期积压", metrics, null);
        } catch (Exception exception) {
            return unavailable("outbox", "业务事件 Outbox", true, "Outbox 状态读取失败",
                "检查 agent_outbox_event 表和数据库权限");
        }
    }

    /**
     * 处理{@code leases}并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @return 处理结果
     */
    private SystemDiagnosticCheckView leases(Connection connection) {
        String sql = """
            SELECT
              (SELECT count(*) FROM agent_task_run
                WHERE status IN ('preparing','running') AND lease_until < CURRENT_TIMESTAMP) AS task_expired,
              (SELECT count(*) FROM agent_sandbox_job
                WHERE status IN ('leased','running') AND lease_until < CURRENT_TIMESTAMP) AS sandbox_expired,
              (SELECT count(*) FROM agent_report_delivery_job
                WHERE status='running' AND lease_until < CURRENT_TIMESTAMP) AS report_expired
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            long task = result.getLong("task_expired");
            long sandbox = result.getLong("sandbox_expired");
            long report = result.getLong("report_expired");
            long total = task + sandbox + report;
            Map<String, Object> metrics = metrics(
                "taskExpired", task, "sandboxExpired", sandbox, "reportExpired", report
            );
            return total == 0
                ? check("leases", "执行租约", HEALTHY, true, "没有过期的活动租约", metrics, null)
                : check("leases", "执行租约", DEGRADED, true, "发现需要回收的过期租约", metrics,
                    "确认调度维护任务正在运行，并检查异常退出的 Worker");
        } catch (Exception exception) {
            return unavailable("leases", "执行租约", true, "执行租约状态读取失败",
                "检查任务、Sandbox 和报表调度表是否已完成迁移");
        }
    }

    /**
     * 处理沙箱并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @return 处理结果
     */
    private SystemDiagnosticCheckView sandbox(Connection connection) {
        String sql = """
            SELECT count(*) FILTER (
                       WHERE status='active' AND heartbeat_expires_at >= CURRENT_TIMESTAMP
                   ) AS active,
                   count(*) FILTER (WHERE status='stale') AS stale
              FROM agent_sandbox_runner
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            long active = result.getLong("active");
            long stale = result.getLong("stale");
            Map<String, Object> metrics = metrics("active", active, "stale", stale);
            if (active == 0) {
                return check("sandbox", "Sandbox Runner", sandboxRequired ? UNAVAILABLE : DISABLED,
                    sandboxRequired, sandboxRequired ? "部署 Profile 要求 Runner，但当前没有健康实例" : "当前没有健康 Runner",
                    metrics, "启动 Runner 并确认心跳、密钥与能力模板配置");
            }
            return check("sandbox", "Sandbox Runner", stale > 0 ? DEGRADED : HEALTHY,
                sandboxRequired, stale > 0 ? "存在失联 Runner" : "Runner 心跳正常", metrics,
                stale > 0 ? "检查失联 Runner 进程并清理过期注册" : null);
        } catch (Exception exception) {
            return unavailable("sandbox", "Sandbox Runner", sandboxRequired, "Runner 状态读取失败",
                "检查 agent_sandbox_runner 表和数据库权限");
        }
    }

    /**
     * 处理{@code providers}并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @return 处理结果
     */
    private SystemDiagnosticCheckView providers(Connection connection) {
        String sql = """
            SELECT provider_type, count(*) AS provider_count
              FROM agent_connector
             WHERE del_flag='0' AND status='active'
             GROUP BY provider_type ORDER BY provider_type
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            Map<String, Object> counts = new LinkedHashMap<>();
            long total = 0;
            while (result.next()) {
                long count = result.getLong("provider_count");
                counts.put(result.getString("provider_type"), count);
                total += count;
            }
            return total == 0
                ? check("providers", "外部 Provider", DISABLED, false, "尚未启用外部 Provider", counts,
                    "按需配置模型、搜索、MCP 或外部智能体 Provider")
                : check("providers", "外部 Provider", HEALTHY, false, "已加载 " + total + " 个活动 Provider", counts, null);
        } catch (Exception exception) {
            return check("providers", "外部 Provider", DEGRADED, false, "Provider 目录读取失败", Map.of(),
                "检查 agent_connector 表和 Provider 配置");
        }
    }

    /**
     * 查询{@code Circuits}列表。
     *
     * @param connection {@code connection}参数
     * @return 处理结果
     */
    private SystemDiagnosticCheckView searchCircuits(Connection connection) {
        String sql = """
            SELECT count(*) FILTER (WHERE circuit_state='open') AS open_count,
                   count(*) FILTER (WHERE circuit_state='half_open') AS half_open_count,
                   count(*) AS tracked_count
              FROM agent_search_provider_state
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            long open = result.getLong("open_count");
            long halfOpen = result.getLong("half_open_count");
            long tracked = result.getLong("tracked_count");
            Map<String, Object> metrics = metrics("tracked", tracked, "open", open, "halfOpen", halfOpen);
            if (tracked == 0) {
                return check("search", "联网搜索熔断", DISABLED, false, "尚无搜索 Provider 运行状态", metrics,
                    "配置搜索 Provider 后可在资源中心执行连接和检索测试");
            }
            return open > 0
                ? check("search", "联网搜索熔断", DEGRADED, false, "存在已打开熔断的搜索 Provider", metrics,
                    "查看 Provider 最近错误并等待或手工执行健康探测")
                : check("search", "联网搜索熔断", HEALTHY, false, "搜索 Provider 未触发熔断", metrics, null);
        } catch (Exception exception) {
            return check("search", "联网搜索熔断", DEGRADED, false, "搜索运行状态读取失败", Map.of(),
                "检查搜索运行表是否已完成迁移");
        }
    }

    /**
     * 处理{@code finish}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param checks {@code checks}参数
     * @return 处理结果
     */
    private SystemDiagnosticsView finish(CurrentPrincipal principal, List<SystemDiagnosticCheckView> checks) {
        String status = aggregate(checks);
        try {
            auditMapper.insertEvent(
                idGenerator.nextId(), "user", principal.id(), "diagnose", "system", null,
                null, "success", "platform_admin", "status=" + status + ", checks=" + checks.size(), LocalDateTime.now(clock)
            );
        } catch (RuntimeException exception) {
            checks.add(unavailable("diagnosticAudit", "诊断审计", true, "深度诊断审计写入失败",
                "恢复主数据库写入能力后重新执行诊断"));
            status = aggregate(checks);
        }
        return new SystemDiagnosticsView(status, Instant.now(clock), List.copyOf(checks));
    }

    /**
     * 处理{@code aggregate}并返回对应结果。
     *
     * @param checks {@code checks}参数
     * @return 处理结果
     */
    private String aggregate(List<SystemDiagnosticCheckView> checks) {
        List<SystemDiagnosticCheckView> required = checks.stream().filter(SystemDiagnosticCheckView::required).toList();
        if (required.stream().anyMatch(check -> UNAVAILABLE.equals(check.status()))) return UNAVAILABLE;
        if (required.stream().anyMatch(check -> DEGRADED.equals(check.status()))) return DEGRADED;
        return HEALTHY;
    }

    /**
     * 创建并保存{@code SkippedDatabaseChecks}。
     *
     * @param checks {@code checks}参数
     */
    private void addSkippedDatabaseChecks(List<SystemDiagnosticCheckView> checks) {
        checks.add(unavailable("outbox", "业务事件 Outbox", true, "主数据库不可用，无法检查 Outbox",
            "恢复数据库后重新执行深度诊断"));
        checks.add(unavailable("leases", "执行租约", true, "主数据库不可用，无法检查执行租约",
            "恢复数据库后重新执行深度诊断"));
        checks.add(check("sandbox", "Sandbox Runner", sandboxRequired ? UNAVAILABLE : DISABLED, sandboxRequired,
            "主数据库不可用，无法检查 Runner", Map.of(), "恢复数据库后重新执行深度诊断"));
        checks.add(check("providers", "外部 Provider", DEGRADED, false, "主数据库不可用，无法检查 Provider", Map.of(),
            "恢复数据库后重新执行深度诊断"));
        checks.add(check("search", "联网搜索熔断", DEGRADED, false, "主数据库不可用，无法检查搜索状态", Map.of(),
            "恢复数据库后重新执行深度诊断"));
    }

    /**
     * 校验{@code Administrator}，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private CurrentPrincipal requireAdministrator() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (principal == null || !principal.isHuman() || !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            throw new ServiceException("仅平台管理员可以执行系统深度诊断", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param key {@code key}参数
     * @param name 名称
     * @param required {@code required}参数
     * @param message 待处理内容
     * @param remediation {@code remediation}参数
     * @return 处理结果
     */
    private SystemDiagnosticCheckView unavailable(
        String key, String name, boolean required, String message, String remediation
    ) {
        return check(key, name, UNAVAILABLE, required, message, Map.of(), remediation);
    }

    /**
     * 校验{@code check}，并在条件不满足时终止处理。
     *
     * @param key {@code key}参数
     * @param name 名称
     * @param status 目标状态
     * @param required {@code required}参数
     * @param message 待处理内容
     * @param metrics {@code metrics}参数
     * @param remediation {@code remediation}参数
     * @return 处理结果
     */
    private SystemDiagnosticCheckView check(
        String key,
        String name,
        String status,
        boolean required,
        String message,
        Map<String, Object> metrics,
        String remediation
    ) {
        return new SystemDiagnosticCheckView(key, name, status, required, message, Map.copyOf(metrics), remediation);
    }

    /**
     * 处理{@code metrics}并返回对应结果。
     *
     * @param values {@code values}参数
     * @return 处理结果
     */
    private Map<String, Object> metrics(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    /**
     * 处理{@code compareVersions}并返回对应结果。
     *
     * @param current 当前参数
     * @param expected {@code expected}参数
     * @return 处理结果
     */
    private int compareVersions(String current, String expected) {
        try {
            return new BigInteger(current).compareTo(new BigInteger(expected));
        } catch (RuntimeException exception) {
            return current == null ? -1 : current.compareTo(expected);
        }
    }

    /**
     * 获取ExpectedSchema版本。
     *
     * @return 处理结果
     */
    static String resolveExpectedSchemaVersion() {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/agent/V*__*.sql");
            BigInteger maximum = BigInteger.ZERO;
            for (Resource resource : resources) {
                Matcher matcher = MIGRATION_VERSION.matcher(resource.getFilename() == null ? "" : resource.getFilename());
                if (matcher.matches()) {
                    BigInteger version = new BigInteger(matcher.group(1));
                    if (version.compareTo(maximum) > 0) maximum = version;
                }
            }
            return maximum.signum() > 0 ? maximum.toString() : "unknown";
        } catch (IOException exception) {
            return "unknown";
        }
    }
}
