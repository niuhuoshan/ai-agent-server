package group.aitools.nhs.platform.operations.service;

import group.aitools.nhs.runtime.spi.AgentRuntime;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.operations.web.SystemHealthComponentView;
import group.aitools.nhs.platform.operations.web.SystemHealthOverviewView;
import group.aitools.nhs.platform.operations.web.SystemRuntimeMetricsView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责系统健康状态相关的业务编排与领域规则处理。
 * Collects a bounded, credential-free health snapshot from the local deployment. */
@Service
public class SystemHealthApplicationService {

    static final String HEALTHY = "healthy";
    static final String DEGRADED = "degraded";
    static final String UNAVAILABLE = "unavailable";
    static final String DISABLED = "disabled";

    private final CurrentPrincipalProvider principalProvider;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final ObjectProvider<RedissonClient> redisProvider;
    private final ObjectProvider<AgentRuntime> runtimeProvider;
    private final String applicationName;
    private final Clock clock;

    @Autowired
    public SystemHealthApplicationService(
        CurrentPrincipalProvider principalProvider,
        ObjectProvider<DataSource> dataSourceProvider,
        ObjectProvider<RedissonClient> redisProvider,
        ObjectProvider<AgentRuntime> runtimeProvider,
        @Value("${spring.application.name:nhs}") String applicationName
    ) {
        this(principalProvider, dataSourceProvider, redisProvider, runtimeProvider, applicationName, Clock.systemUTC());
    }

    /**
     * 创建 {@code SystemHealthApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param dataSourceProvider 数据数据源提供方参数
     * @param redisProvider redis提供方参数
     * @param runtimeProvider 运行时提供方参数
     * @param applicationName 名称
     * @param clock {@code clock}参数
     */
    SystemHealthApplicationService(
        CurrentPrincipalProvider principalProvider,
        ObjectProvider<DataSource> dataSourceProvider,
        ObjectProvider<RedissonClient> redisProvider,
        ObjectProvider<AgentRuntime> runtimeProvider,
        String applicationName,
        Clock clock
    ) {
        this.principalProvider = principalProvider;
        this.dataSourceProvider = dataSourceProvider;
        this.redisProvider = redisProvider;
        this.runtimeProvider = runtimeProvider;
        this.applicationName = applicationName == null || applicationName.isBlank()
            ? "nhs" : applicationName.strip();
        this.clock = clock;
    }

    /**
     * 处理{@code overview}并返回对应结果。
     *
     * @return 处理结果
     */
    public SystemHealthOverviewView overview() {
        requireAdministrator();
        List<SystemHealthComponentView> components = new ArrayList<>();
        components.add(application());
        components.add(database());
        components.add(redis());
        components.add(agentRuntime());
        return new SystemHealthOverviewView(
            aggregate(components),
            Instant.now(clock),
            applicationName,
            implementationVersion(),
            runtimeMetrics(),
            List.copyOf(components)
        );
    }

    /**
 * 处理{@code testComponent}并返回对应结果。
 * Runs one bounded infrastructure probe for the System compatibility console. */
    public SystemHealthComponentView testComponent(String rawKey) {
        requireAdministrator();
        String key = rawKey == null ? "" : rawKey.strip();
        return switch (key) {
            case "application" -> application();
            case "database" -> database();
            case "redis" -> redis();
            case "agentRuntime", "agent_runtime" -> agentRuntime();
            default -> throw new ServiceException("未知系统组件：" + key, HttpStatus.BAD_REQUEST);
        };
    }

    /**
     * 处理应用并返回对应结果。
     *
     * @return 处理结果
     */
    private SystemHealthComponentView application() {
        return component(
            "application", "应用服务", HEALTHY, true, "应用进程正在运行", 0L,
            Map.of("name", applicationName)
        );
    }

    /**
     * 处理{@code database}并返回对应结果。
     *
     * @return 处理结果
     */
    private SystemHealthComponentView database() {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        long started = System.nanoTime();
        try {
            DataSource dataSource = dataSourceProvider.getIfAvailable();
            if (dataSource == null) {
                return unavailable("database", "主数据库", true, "未加载数据库连接", started);
            }
            try (Connection connection = dataSource.getConnection()) {
                if (!connection.isValid(2)) {
                    return unavailable("database", "主数据库", true, "数据库连接校验未通过", started);
                }
                DatabaseMetaData metadata = connection.getMetaData();
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("product", safeMetadata(metadata.getDatabaseProductName()));
                details.put("version", safeMetadata(metadata.getDatabaseProductVersion()));
                details.put("readOnly", connection.isReadOnly());
                return component(
                    "database", "主数据库", HEALTHY, true, "数据库连接正常", elapsed(started), details
                );
            }
        } catch (Exception exception) {
            return unavailable("database", "主数据库", true, "数据库连接失败", started);
        }
    }

    /**
     * 处理{@code redis}并返回对应结果。
     *
     * @return 处理结果
     */
    private SystemHealthComponentView redis() {
        long started = System.nanoTime();
        try {
            RedissonClient client = redisProvider.getIfAvailable();
            if (client == null || client.isShutdown() || client.isShuttingDown()) {
                return unavailable("redis", "Redis", true, "Redis 客户端未就绪", started);
            }
            long keyCount = client.getKeys().count();
            return component(
                "redis", "Redis", HEALTHY, true, "Redis 连接正常", elapsed(started),
                Map.of("keyCount", Math.max(0L, keyCount))
            );
        } catch (Exception exception) {
            return unavailable("redis", "Redis", true, "Redis 连接失败", started);
        }
    }

    /**
     * 处理智能体运行时并返回对应结果。
     *
     * @return 处理结果
     */
    private SystemHealthComponentView agentRuntime() {
        long started = System.nanoTime();
        try {
            AgentRuntime runtime = runtimeProvider.getIfAvailable();
            if (runtime == null) {
                return component(
                    "agentRuntime", "Agent 运行时", DISABLED, true,
                    "Agent 运行时未启用", elapsed(started), Map.of()
                );
            }
            return component(
                "agentRuntime", "Agent 运行时", HEALTHY, true,
                "Agent 运行时已加载", elapsed(started),
                Map.of("implementation", runtime.getClass().getSimpleName())
            );
        } catch (Exception exception) {
            return unavailable("agentRuntime", "Agent 运行时", true, "Agent 运行时检查失败", started);
        }
    }

    /**
     * 执行{@code timeMetrics}相关的处理流程。
     *
     * @return 处理结果
     */
    private SystemRuntimeMetricsView runtimeMetrics() {
        java.lang.management.RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        return new SystemRuntimeMetricsView(
            System.getProperty("java.version", "unknown"),
            safeMetadata(runtime.getVmName()),
            Runtime.getRuntime().availableProcessors(),
            Math.max(0L, runtime.getUptime() / 1000L),
            Math.max(0L, heap.getUsed()),
            Math.max(0L, heap.getCommitted()),
            Math.max(0L, heap.getMax()),
            Math.max(0, ManagementFactory.getThreadMXBean().getThreadCount()),
            Double.isFinite(load) && load >= 0D ? load : null
        );
    }

    /**
     * 处理{@code aggregate}并返回对应结果。
     *
     * @param components {@code components}参数
     * @return 处理结果
     */
    private String aggregate(List<SystemHealthComponentView> components) {
        List<SystemHealthComponentView> infrastructure = components.stream()
            .filter(component -> "database".equals(component.key()) || "redis".equals(component.key()))
            .toList();
        if (!infrastructure.isEmpty() && infrastructure.stream()
            .allMatch(component -> UNAVAILABLE.equals(component.status()))) {
            return UNAVAILABLE;
        }
        List<SystemHealthComponentView> critical = components.stream()
            .filter(SystemHealthComponentView::critical)
            .filter(component -> !"application".equals(component.key()))
            .toList();
        long healthy = critical.stream().filter(component -> HEALTHY.equals(component.status())).count();
        if (healthy == critical.size()) {
            return HEALTHY;
        }
        return healthy == 0 ? UNAVAILABLE : DEGRADED;
    }

    /**
     * 校验{@code Administrator}，并在条件不满足时终止处理。
     */
    private void requireAdministrator() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (principal == null || !principal.isHuman() || !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            throw new ServiceException("仅平台管理员可以查看系统运行健康", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param key {@code key}参数
     * @param name 名称
     * @param critical {@code critical}参数
     * @param message 待处理内容
     * @param started {@code started}参数
     * @return 处理结果
     */
    private SystemHealthComponentView unavailable(
        String key,
        String name,
        boolean critical,
        String message,
        long started
    ) {
        return component(key, name, UNAVAILABLE, critical, message, elapsed(started), Map.of());
    }

    /**
     * 处理{@code component}并返回对应结果。
     *
     * @param key {@code key}参数
     * @param name 名称
     * @param status 目标状态
     * @param critical {@code critical}参数
     * @param message 待处理内容
     * @param responseTimeMs {@code responseTimeMs}参数
     * @param details {@code details}参数
     * @return 处理结果
     */
    private SystemHealthComponentView component(
        String key,
        String name,
        String status,
        boolean critical,
        String message,
        long responseTimeMs,
        Map<String, Object> details
    ) {
        return new SystemHealthComponentView(
            key, name, status, critical, message, Math.max(0L, responseTimeMs), Map.copyOf(details)
        );
    }

    /**
     * 处理{@code elapsed}并返回对应结果。
     *
     * @param started {@code started}参数
     * @return 处理结果
     */
    private long elapsed(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    /**
     * 处理implementation版本并返回对应结果。
     *
     * @return 处理结果
     */
    private String implementationVersion() {
        Package source = SystemHealthApplicationService.class.getPackage();
        return source == null ? null : source.getImplementationVersion();
    }

    /**
     * 处理safe元数据并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safeMetadata(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.replace('\0', ' ').strip();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }
}
