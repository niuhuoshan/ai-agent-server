package group.aitools.nhs.platform.identity.service;

import cn.hutool.crypto.digest.BCrypt;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.service.DataCredential;
import group.aitools.nhs.platform.data.service.DataCredentialResolver;
import group.aitools.nhs.platform.data.service.ReadOnlyJdbcConnectionFactory;
import group.aitools.nhs.platform.identity.domain.AgentIdentitySyncConfig;
import group.aitools.nhs.platform.identity.domain.AgentIdentitySyncRun;
import group.aitools.nhs.platform.identity.domain.IdentitySyncExternalUser;
import group.aitools.nhs.platform.identity.domain.IdentitySyncLocalUser;
import group.aitools.nhs.platform.identity.mapper.IdentitySyncMapper;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.ColumnOption;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.ConfigView;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.DataSourceOption;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.ExtraMapping;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.FieldMapping;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.PreviewItem;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.PreviewRequest;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.PreviewView;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.RunItem;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.RunRequest;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.RunView;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.TableOption;
import group.aitools.nhs.platform.identity.web.IdentitySyncContracts.UpdateConfigRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 负责身份Sync相关的业务编排与领域规则处理。
 * Provider-backed preview, execution and retry workflow for local NHS users. */
@Service
public class IdentitySyncApplicationService {

    private static final int MAX_PROVIDER_USERS = 5000;
    private static final int MAX_PREVIEW_USERS = 1000;
    private static final int MAX_TABLES = 1000;
    private static final int MAX_COLUMNS = 500;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,127}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{2,30}");
    private static final Pattern CREDENTIAL_REFERENCE = Pattern.compile("env:[A-Z][A-Z0-9_]{0,127}");
    private static final Set<String> BLOCKED_HTTP_HEADERS = Set.of(
        "authorization", "proxy-authorization", "cookie", "host", "content-length", "connection"
    );
    private static final TypeReference<List<ExtraMapping>> EXTRA_MAPPING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<RunItem>> RUN_ITEM_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final IdentitySyncMapper mapper;
    private final DataCatalogMapper dataCatalogMapper;
    private final ReadOnlyJdbcConnectionFactory connectionFactory;
    private final DataCredentialResolver credentialResolver;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final boolean allowInsecureHttp;

    /**
     * 创建 {@code IdentitySyncApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param mapper {@code mapper}参数
     * @param dataCatalogMapper 数据目录Mapper参数
     * @param connectionFactory {@code connectionFactory}参数
     * @param credentialResolver 凭据Resolver参数
     * @param idGenerator {@code idGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param allowInsecureHttp {@code allowInsecureHttp}参数
     */
    public IdentitySyncApplicationService(
        CurrentPrincipalProvider principalProvider,
        IdentitySyncMapper mapper,
        DataCatalogMapper dataCatalogMapper,
        ReadOnlyJdbcConnectionFactory connectionFactory,
        DataCredentialResolver credentialResolver,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper,
        @Value("${agent.platform.identity-sync.allow-http:false}") boolean allowInsecureHttp
    ) {
        this.principalProvider = principalProvider;
        this.mapper = mapper;
        this.dataCatalogMapper = dataCatalogMapper;
        this.connectionFactory = connectionFactory;
        this.credentialResolver = credentialResolver;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
        this.allowInsecureHttp = allowInsecureHttp;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /**
     * 处理{@code config}并返回对应结果。
     *
     * @return 处理结果
     */
    public ConfigView config() {
        requireAdministrator();
        return configView(requireConfig());
    }

    /**
     * 更新{@code Config}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ConfigView updateConfig(UpdateConfigRequest request) {
        CurrentPrincipal principal = requireAdministrator();
        AgentIdentitySyncConfig current = requireConfig();
        if (request.expectedRevision() != null
            && !Objects.equals(current.getRevisionNo(), request.expectedRevision())) {
            throw conflict("身份同步配置已被其他管理员更新，请刷新后重试");
        }
        AgentIdentitySyncConfig next = normalize(request, current, principal.id(), LocalDateTime.now());
        validateConfig(next, request.enabled());
        if (mapper.updateConfig(next) != 1) {
            throw conflict("身份同步配置已被其他管理员更新，请刷新后重试");
        }
        return configView(requireConfig());
    }

    /**
     * 处理数据Sources并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    public List<DataSourceOption> dataSources() {
        requireAdministrator();
        return dataCatalogMapper.selectSources(500).stream()
            .map(source -> new DataSourceOption(
                source.getId(), source.getName(), source.getDbType(),
                source.getDatabaseName(), source.getStatus()
            ))
            .toList();
    }

    /**
     * 处理{@code tables}并返回对应结果。
     *
     * @param dataSourceId 资源标识
     * @return 符合条件的数据集合
     */
    public List<TableOption> tables(Long dataSourceId) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        requireAdministrator();
        AgentDataSource source = requireSource(dataSourceId);
        try (Connection connection = connectionFactory.open(source)) {
            DatabaseMetaData metadata = connection.getMetaData();
            List<TableOption> result = new ArrayList<>();
            try (ResultSet rows = metadata.getTables(
                null, null, "%", new String[]{"TABLE", "VIEW"}
            )) {
                while (rows.next() && result.size() < MAX_TABLES) {
                    String schema = trimToNull(rows.getString("TABLE_SCHEM"));
                    String name = rows.getString("TABLE_NAME");
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    String qualified = schema == null ? name : schema + "." + name;
                    result.add(new TableOption(schema, name, qualified, rows.getString("TABLE_TYPE")));
                }
            }
            return List.copyOf(result);
        } catch (ServiceException exception) {
            throw providerFailure("database", exception);
        } catch (Exception exception) {
            throw unavailable("database", "无法发现身份源数据表，请检查网络、凭证和只读权限");
        }
    }

    /**
     * 处理{@code columns}并返回对应结果。
     *
     * @param dataSourceId 资源标识
     * @param tableName 名称
     * @return 符合条件的数据集合
     */
    public List<ColumnOption> columns(Long dataSourceId, String tableName) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        requireAdministrator();
        AgentDataSource source = requireSource(dataSourceId);
        QualifiedTable table = qualifiedTable(tableName);
        try (Connection connection = connectionFactory.open(source)) {
            Map<String, String> samples = sampleRow(connection, source, tableName);
            DatabaseMetaData metadata = connection.getMetaData();
            List<ColumnOption> result = new ArrayList<>();
            try (ResultSet rows = metadata.getColumns(
                null, table.schema(), table.table(), "%"
            )) {
                while (rows.next() && result.size() < MAX_COLUMNS) {
                    String name = rows.getString("COLUMN_NAME");
                    result.add(new ColumnOption(
                        name,
                        Objects.toString(rows.getString("TYPE_NAME"), "unknown"),
                        rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        samples.get(name)
                    ));
                }
            }
            return List.copyOf(result);
        } catch (ServiceException exception) {
            throw providerFailure("database", exception);
        } catch (Exception exception) {
            throw unavailable("database", "无法发现身份源字段，请检查表名和只读权限");
        }
    }

    /**
     * 处理{@code preview}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public PreviewView preview(PreviewRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = requireAdministrator();
        AgentIdentitySyncConfig config = request == null || request.config() == null
            ? requireConfig() : transientConfig(request.config(), principal.id());
        validateConfig(config, true);
        try {
            List<IdentitySyncExternalUser> users = fetchUsers(config);
            List<String> names = users.stream().map(IdentitySyncExternalUser::userName)
                .filter(Objects::nonNull).distinct().toList();
            Map<String, IdentitySyncLocalUser> localUsers = localUsers(names);
            List<PreviewItem> items = new ArrayList<>();
            int creates = 0;
            int updates = 0;
            for (IdentitySyncExternalUser user : users.stream().limit(MAX_PREVIEW_USERS).toList()) {
                boolean valid = validUsername(user.userName());
                boolean existing = valid && localUsers.containsKey(user.userName());
                String action = valid ? (existing ? "update" : "create") : "invalid";
                if ("create".equals(action)) {
                    creates++;
                } else if ("update".equals(action)) {
                    updates++;
                }
                items.add(new PreviewItem(
                    user.userName(), user.displayName(), user.email(), user.phoneNumber(),
                    user.remark(), user.status(), user.extraData(), existing, action
                ));
            }
            LocalDateTime now = LocalDateTime.now();
            mapper.recordPreview(now, null);
            return new PreviewView(
                config.getProviderType(), config.getRevisionNo(), users.size(), creates, updates,
                List.copyOf(items), now
            );
        } catch (ServiceException exception) {
            mapper.recordPreview(LocalDateTime.now(), bounded(exception.getMessage(), 2000));
            throw exception;
        } catch (RuntimeException exception) {
            mapper.recordPreview(LocalDateTime.now(), "身份源预览失败");
            throw unavailable(config.getProviderType(), "身份源预览失败，请检查Provider配置");
        }
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public RunView execute(RunRequest request) {
        CurrentPrincipal principal = requireAdministrator();
        AgentIdentitySyncConfig config = request == null || request.config() == null
            ? requireConfig() : transientConfig(request.config(), principal.id());
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw unavailable(config.getProviderType(), "身份同步当前未启用");
        }
        validateConfig(config, true);
        List<String> names = request == null ? List.of() : normalizedNames(request.userNames());
        return executeInternal(principal.id(), config, names, null, null);
    }

    /**
     * 处理{@code retry}并返回对应结果。
     *
     * @param runId 资源标识
     * @return 处理结果
     */
    public RunView retry(Long runId) {
        CurrentPrincipal principal = requireAdministrator();
        AgentIdentitySyncRun previous = requireRun(runId);
        if (!Set.of("partial", "failed", "unavailable").contains(previous.getStatus())) {
            throw conflict("只有部分成功、失败或不可用的同步运行允许重试");
        }
        AgentIdentitySyncConfig config = requireConfig();
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw unavailable(config.getProviderType(), "身份同步当前未启用");
        }
        validateConfig(config, true);
        List<IdentitySyncExternalUser> snapshot = failedSnapshot(previous);
        List<String> requestedNames = snapshot.isEmpty()
            ? stringList(previous.getRequestedNamesJson())
            : snapshot.stream().map(IdentitySyncExternalUser::userName).toList();
        return executeInternal(
            principal.id(), config, requestedNames, previous.getId(), snapshot.isEmpty() ? null : snapshot
        );
    }

    /**
     * 执行{@code s}相关的处理流程。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<RunView> runs(int limit) {
        requireAdministrator();
        return mapper.selectRuns(Math.max(1, Math.min(limit, 200))).stream().map(this::runView).toList();
    }

    /**
     * 执行{@code run}相关的处理流程。
     *
     * @param runId 资源标识
     * @return 处理结果
     */
    public RunView run(Long runId) {
        requireAdministrator();
        return runView(requireRun(runId));
    }

    /**
 * 执行{@code Scheduled}相关的处理流程。
 * Runs a configured preset from the scheduler without depending on an HTTP login context. */
    public void runScheduled(LocalDateTime now) {
        AgentIdentitySyncConfig config = requireConfig();
        if (!Boolean.TRUE.equals(config.getEnabled()) || "off".equals(config.getSchedule())
            || config.getUpdateBy() == null || config.getUpdateBy() <= 0
            || mapper.countActiveRuns() > 0 || !scheduleDue(config, now)) {
            return;
        }
        validateConfig(config, true);
        executeInternal(config.getUpdateBy(), config, List.of(), null, null);
    }

    /**
     * 执行{@code Internal}相关的处理流程。
     *
     * @param actorId 资源标识
     * @param config {@code config}参数
     * @param requestedNames 名称
     * @param retryOfRunId 资源标识
     * @param retrySnapshot retry快照参数
     * @return 处理结果
     */
    private RunView executeInternal(
        Long actorId,
        AgentIdentitySyncConfig config,
        List<String> requestedNames,
        Long retryOfRunId,
        List<IdentitySyncExternalUser> retrySnapshot
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        AgentIdentitySyncRun run = new AgentIdentitySyncRun();
        run.setId(idGenerator.nextId());
        run.setRetryOfRunId(retryOfRunId);
        run.setProviderType(config.getProviderType());
        run.setConfigRevision(config.getRevisionNo());
        run.setStatus("running");
        run.setRequestedNamesJson(jsonMapper.writeValueAsString(requestedNames));
        run.setItemsJson("[]");
        run.setDiscoveredCount(0);
        run.setSelectedCount(0);
        run.setCreatedCount(0);
        run.setUpdatedCount(0);
        run.setSkippedCount(0);
        run.setFailedCount(0);
        run.setRequestedBy(actorId);
        run.setStartedAt(LocalDateTime.now());
        mapper.insertRun(run);

        try {
            List<IdentitySyncExternalUser> discovered = retrySnapshot == null
                ? fetchUsers(config) : List.copyOf(retrySnapshot);
            Set<String> requested = new LinkedHashSet<>(requestedNames);
            List<IdentitySyncExternalUser> selected = requested.isEmpty()
                ? discovered
                : discovered.stream().filter(user -> requested.contains(user.userName())).toList();
            List<RunItem> items = new ArrayList<>();
            int created = 0;
            int updated = 0;
            int skipped = 0;
            int failed = 0;

            if (!requested.isEmpty()) {
                Set<String> found = selected.stream().map(IdentitySyncExternalUser::userName)
                    .collect(java.util.stream.Collectors.toSet());
                for (String missing : requested) {
                    if (!found.contains(missing)) {
                        skipped++;
                        items.add(new RunItem(
                            missing, null, null, null, null, null, Map.of(),
                            "skipped", null, "身份源中不存在该用户"
                        ));
                    }
                }
            }

            Long roleId = optionalRole(config.getDefaultRoleKey());
            for (IdentitySyncExternalUser user : selected) {
                try {
                    AppliedUser applied = applyUser(user, roleId, actorId);
                    if ("created".equals(applied.result())) {
                        created++;
                    } else {
                        updated++;
                    }
                    items.add(runItem(user, applied.result(), applied.userId(), null));
                } catch (RuntimeException exception) {
                    failed++;
                    items.add(runItem(user, "failed", null, safeItemError(exception)));
                }
            }

            String status = failed == 0 ? "succeeded" : (created + updated > 0 ? "partial" : "failed");
            finishRun(
                run, status, items, discovered.size(), selected.size() + skipped,
                created, updated, skipped, failed, failed == 0 ? null : "部分用户同步失败"
            );
            return runView(run);
        } catch (ServiceException exception) {
            String status = Integer.valueOf(503).equals(exception.getCode()) ? "unavailable" : "failed";
            finishRun(run, status, List.of(), 0, 0, 0, 0, 0, 0, bounded(exception.getMessage(), 2000));
            throw new ServiceException(
                exception.getMessage() + "（运行ID：" + run.getId() + "）",
                exception.getCode() == null ? 503 : exception.getCode()
            );
        } catch (RuntimeException exception) {
            finishRun(run, "failed", List.of(), 0, 0, 0, 0, 0, 0, "身份同步执行失败");
            throw new ServiceException("身份同步执行失败（运行ID：" + run.getId() + "）", 500);
        }
    }

    /**
     * 处理{@code finishRun}相关逻辑。
     *
     * @param run {@code run}参数
     * @param status 目标状态
     * @param items {@code items}参数
     * @param discovered {@code discovered}参数
     * @param selected {@code selected}参数
     * @param created {@code created}参数
     * @param updated {@code updated}参数
     * @param skipped {@code skipped}参数
     * @param failed {@code failed}参数
     * @param error {@code error}参数
     */
    private void finishRun(
        AgentIdentitySyncRun run,
        String status,
        List<RunItem> items,
        int discovered,
        int selected,
        int created,
        int updated,
        int skipped,
        int failed,
        String error
    ) {
        LocalDateTime now = LocalDateTime.now();
        run.setStatus(status);
        run.setItemsJson(jsonMapper.writeValueAsString(items));
        run.setDiscoveredCount(discovered);
        run.setSelectedCount(selected);
        run.setCreatedCount(created);
        run.setUpdatedCount(updated);
        run.setSkippedCount(skipped);
        run.setFailedCount(failed);
        run.setErrorSummary(error);
        run.setFinishedAt(now);
        mapper.finishRun(run);
        mapper.recordRun(now, status, error);
    }

    /**
     * 处理apply用户并返回对应结果。
     *
     * @param external {@code external}参数
     * @param roleId 资源标识
     * @param actorId 资源标识
     * @return 处理结果
     */
    private AppliedUser applyUser(IdentitySyncExternalUser external, Long roleId, Long actorId) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (!validUsername(external.userName())) {
            throw badRequest("用户名必须为2-30位字母、数字或._-");
        }
        IdentitySyncLocalUser existing = mapper.selectLocalUser(external.userName());
        LocalDateTime now = LocalDateTime.now();
        String nickName = bounded(defaultText(external.displayName(), external.userName()), 30);
        String email = bounded(trimToEmpty(external.email()), 50);
        String phone = bounded(trimToEmpty(external.phoneNumber()), 11);
        String status = localStatus(external.status(), existing == null ? "0" : existing.getStatus());
        String remark = bounded(syncRemark(external.remark()), 500);
        if (existing != null) {
            mapper.updateLocalUser(
                existing.getUserId(), nickName, email, phone, status, remark, actorId, now
            );
            if (roleId != null) {
                mapper.insertUserRole(existing.getUserId(), roleId);
            }
            return new AppliedUser(existing.getUserId(), "updated");
        }

        Long userId = idGenerator.nextId();
        String inaccessiblePassword = BCrypt.hashpw(UUID.randomUUID() + ":identity-provider-only");
        try {
            mapper.insertLocalUser(
                userId, external.userName(), nickName, email, phone, inaccessiblePassword,
                status, remark, actorId, now
            );
            if (roleId != null) {
                mapper.insertUserRole(userId, roleId);
            }
            return new AppliedUser(userId, "created");
        } catch (RuntimeException exception) {
            IdentitySyncLocalUser concurrent = mapper.selectLocalUser(external.userName());
            if (concurrent == null) {
                throw exception;
            }
            mapper.updateLocalUser(
                concurrent.getUserId(), nickName, email, phone, status, remark, actorId, now
            );
            if (roleId != null) {
                mapper.insertUserRole(concurrent.getUserId(), roleId);
            }
            return new AppliedUser(concurrent.getUserId(), "updated");
        }
    }

    /**
     * 处理{@code fetchUsers}并返回对应结果。
     *
     * @param config {@code config}参数
     * @return 符合条件的数据集合
     */
    private List<IdentitySyncExternalUser> fetchUsers(AgentIdentitySyncConfig config) {
        return switch (config.getProviderType()) {
            case "database" -> fetchDatabaseUsers(config);
            case "http_json" -> fetchHttpUsers(config);
            default -> throw badRequest("不支持的身份源类型");
        };
    }

    /**
     * 处理{@code fetchDatabaseUsers}并返回对应结果。
     *
     * @param config {@code config}参数
     * @return 符合条件的数据集合
     */
    private List<IdentitySyncExternalUser> fetchDatabaseUsers(AgentIdentitySyncConfig config) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        AgentDataSource source = requireSource(config.getDataSourceId());
        String sql = selectSql(config, source.getDbType());
        try (Connection connection = connectionFactory.open(source);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(seconds(source.getStatementTimeoutMs()));
            statement.setFetchSize(Math.min(MAX_PROVIDER_USERS, 500));
            statement.setMaxRows(MAX_PROVIDER_USERS + 1);
            try (ResultSet rows = statement.executeQuery(sql)) {
                List<IdentitySyncExternalUser> users = new ArrayList<>();
                ResultSetMetaData metadata = rows.getMetaData();
                while (rows.next()) {
                    if (users.size() >= MAX_PROVIDER_USERS) {
                        break;
                    }
                    Map<String, Object> raw = row(metadata, rows);
                    IdentitySyncExternalUser user = normalize(raw, config);
                    if (user.userName() != null && !user.userName().isBlank()) {
                        users.add(user);
                    }
                }
                return deduplicate(users);
            }
        } catch (ServiceException exception) {
            throw providerFailure("database", exception);
        } catch (Exception exception) {
            throw unavailable("database", "身份源数据库不可用，请检查网络、凭证、表名和只读权限");
        }
    }

    /**
     * 处理{@code fetchHttpUsers}并返回对应结果。
     *
     * @param config {@code config}参数
     * @return 符合条件的数据集合
     */
    private List<IdentitySyncExternalUser> fetchHttpUsers(AgentIdentitySyncConfig config) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        URI uri = providerUri(config.getEndpointUrl());
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json");
        for (Map.Entry<String, String> header : stringMap(config.getRequestHeadersJson()).entrySet()) {
            String name = safeHeaderName(header.getKey());
            String value = safeHeaderValue(header.getValue());
            request.header(name, value);
        }
        applyProviderCredential(request, config);
        if ("POST".equals(config.getRequestMethod())) {
            request.header("Content-Type", "application/json");
            request.POST(HttpRequest.BodyPublishers.ofString(config.getRequestBodyJson(), StandardCharsets.UTF_8));
        } else {
            request.GET();
        }
        try {
            HttpResponse<String> response = httpClient.send(
                request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw unavailable("http_json", "身份源认证失败，请检查凭证引用");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ServiceException("身份源返回HTTP " + response.statusCode(), 502);
            }
            Object root = jsonMapper.readValue(response.body(), Object.class);
            Object items = resolveItems(root, config.getResponseItemsPath());
            if (!(items instanceof List<?> list)) {
                throw new ServiceException("身份源响应中未找到用户数组", 502);
            }
            List<IdentitySyncExternalUser> users = new ArrayList<>();
            for (Object value : list) {
                if (users.size() >= MAX_PROVIDER_USERS) {
                    break;
                }
                if (value instanceof Map<?, ?> source) {
                    Map<String, Object> raw = new LinkedHashMap<>();
                    source.forEach((key, item) -> raw.put(String.valueOf(key), item));
                    IdentitySyncExternalUser user = normalize(raw, config);
                    if (user.userName() != null && !user.userName().isBlank()) {
                        users.add(user);
                    }
                }
            }
            return deduplicate(users);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("http_json", "身份源请求被中断");
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable("http_json", "身份源HTTP Provider不可用，请检查地址、网络和凭证");
        }
    }

    /**
     * 获取{@code Sql}。
     *
     * @param config {@code config}参数
     * @param dbType 业务类型
     * @return 处理结果
     */
    private String selectSql(AgentIdentitySyncConfig config, String dbType) {
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        columns.add(config.getUsernameColumn());
        addColumn(columns, config.getDisplayNameColumn());
        addColumn(columns, config.getEmailColumn());
        addColumn(columns, config.getPhoneColumn());
        addColumn(columns, config.getRemarkColumn());
        addColumn(columns, config.getStatusColumn());
        for (ExtraMapping mapping : extraMappings(config.getExtraMappingsJson())) {
            addColumn(columns, mapping.sourceColumn());
        }
        String selected = columns.stream().map(column -> quoteIdentifier(column, dbType))
            .collect(java.util.stream.Collectors.joining(", "));
        return "SELECT " + selected + " FROM " + quoteQualified(config.getTableName(), dbType);
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param config {@code config}参数
     * @return 处理结果
     */
    private IdentitySyncExternalUser normalize(Map<String, Object> raw, AgentIdentitySyncConfig config) {
        Map<String, Object> extras = new LinkedHashMap<>();
        for (ExtraMapping mapping : extraMappings(config.getExtraMappingsJson())) {
            extras.put(mapping.key(), jsonSafe(raw.get(mapping.sourceColumn())));
        }
        return new IdentitySyncExternalUser(
            text(raw.get(config.getUsernameColumn())),
            text(raw.get(config.getDisplayNameColumn())),
            text(raw.get(config.getEmailColumn())),
            text(raw.get(config.getPhoneColumn())),
            text(raw.get(config.getRemarkColumn())),
            text(raw.get(config.getStatusColumn())),
            Collections.unmodifiableMap(new LinkedHashMap<>(extras))
        );
    }

    /**
     * 处理{@code deduplicate}并返回对应结果。
     *
     * @param users {@code users}参数
     * @return 符合条件的数据集合
     */
    private List<IdentitySyncExternalUser> deduplicate(List<IdentitySyncExternalUser> users) {
        Map<String, IdentitySyncExternalUser> unique = new LinkedHashMap<>();
        for (IdentitySyncExternalUser user : users) {
            unique.putIfAbsent(user.userName(), user);
        }
        return List.copyOf(unique.values());
    }

    /**
     * 处理{@code localUsers}并返回对应结果。
     *
     * @param names 名称
     * @return 处理结果
     */
    private Map<String, IdentitySyncLocalUser> localUsers(List<String> names) {
        if (names.isEmpty()) {
            return Map.of();
        }
        Map<String, IdentitySyncLocalUser> result = new LinkedHashMap<>();
        for (List<String> batch : batches(names, 500)) {
            for (IdentitySyncLocalUser user : mapper.selectLocalUsers(batch)) {
                result.put(user.getUserName(), user);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /**
     * 处理{@code batches}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param size 数量上限
     * @return 符合条件的数据集合
     */
    private <T> List<List<T>> batches(List<T> values, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int offset = 0; offset < values.size(); offset += size) {
            result.add(values.subList(offset, Math.min(values.size(), offset + size)));
        }
        return result;
    }

    /**
     * 处理{@code transientConfig}并返回对应结果。
     *
     * @param request 请求参数
     * @param actorId 资源标识
     * @return 处理结果
     */
    private AgentIdentitySyncConfig transientConfig(UpdateConfigRequest request, Long actorId) {
        AgentIdentitySyncConfig current = requireConfig();
        AgentIdentitySyncConfig config = normalize(request, current, actorId, LocalDateTime.now());
        config.setRevisionNo(request.expectedRevision() == null ? current.getRevisionNo() : request.expectedRevision());
        return config;
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param request 请求参数
     * @param current 当前参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    private AgentIdentitySyncConfig normalize(
        UpdateConfigRequest request,
        AgentIdentitySyncConfig current,
        Long actorId,
        LocalDateTime now
    ) {
        AgentIdentitySyncConfig value = new AgentIdentitySyncConfig();
        value.setId(1L);
        value.setEnabled(Boolean.TRUE.equals(request.enabled()));
        value.setProviderType(defaultText(request.providerType(), current.getProviderType()));
        value.setDataSourceId(request.dataSourceId());
        value.setEndpointUrl(trimToNull(request.endpointUrl()));
        value.setCredentialRef(trimToNull(request.credentialRef()));
        value.setAuthType(defaultText(request.authType(), current.getAuthType()));
        value.setCredentialHeader(trimToNull(request.credentialHeader()));
        value.setRequestMethod(defaultText(request.requestMethod(), current.getRequestMethod()));
        value.setRequestHeadersJson(jsonMapper.writeValueAsString(safeHeaders(request.requestHeaders())));
        value.setRequestBodyJson(jsonMapper.writeValueAsString(request.requestBody() == null ? Map.of() : request.requestBody()));
        value.setResponseItemsPath(trimToNull(request.responseItemsPath()));
        value.setTableName(trimToNull(request.tableName()));
        value.setUsernameColumn(request.fieldMapping().userName().strip());
        value.setDisplayNameColumn(trimToNull(request.fieldMapping().displayName()));
        value.setEmailColumn(trimToNull(request.fieldMapping().email()));
        value.setPhoneColumn(trimToNull(request.fieldMapping().phoneNumber()));
        value.setRemarkColumn(trimToNull(request.fieldMapping().remark()));
        value.setStatusColumn(trimToNull(request.fieldMapping().status()));
        value.setExtraMappingsJson(jsonMapper.writeValueAsString(normalizeExtraMappings(request.extraMappings())));
        value.setDefaultRoleKey(request.defaultRoleKey() == null
            ? current.getDefaultRoleKey() : trimToNull(request.defaultRoleKey()));
        value.setSchedule(request.schedule());
        value.setRevisionNo(current.getRevisionNo());
        value.setUpdateBy(actorId);
        value.setUpdateTime(now);
        return value;
    }

    /**
     * 校验{@code Config}，并在条件不满足时终止处理。
     *
     * @param config {@code config}参数
     * @param requireProvider require提供方参数
     */
    private void validateConfig(AgentIdentitySyncConfig config, boolean requireProvider) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        validateIdentifier(config.getUsernameColumn(), "用户名字段");
        validateOptionalIdentifier(config.getDisplayNameColumn(), "姓名字段");
        validateOptionalIdentifier(config.getEmailColumn(), "邮箱字段");
        validateOptionalIdentifier(config.getPhoneColumn(), "手机号字段");
        validateOptionalIdentifier(config.getRemarkColumn(), "备注字段");
        validateOptionalIdentifier(config.getStatusColumn(), "状态字段");
        for (ExtraMapping mapping : extraMappings(config.getExtraMappingsJson())) {
            validateIdentifier(mapping.sourceColumn(), "扩展字段");
        }
        if (!requireProvider && !Boolean.TRUE.equals(config.getEnabled())) {
            return;
        }
        if ("database".equals(config.getProviderType())) {
            if (config.getDataSourceId() == null || config.getTableName() == null) {
                throw badRequest("database身份源必须配置数据源和用户表");
            }
            requireSource(config.getDataSourceId());
            qualifiedTable(config.getTableName());
        } else if ("http_json".equals(config.getProviderType())) {
            providerUri(config.getEndpointUrl());
            if (!"none".equals(config.getAuthType())) {
                if (config.getCredentialRef() == null
                    || !CREDENTIAL_REFERENCE.matcher(config.getCredentialRef()).matches()) {
                    throw badRequest("HTTP身份源凭证必须使用env:NAME引用");
                }
            }
            if ("header".equals(config.getAuthType())) {
                safeHeaderName(config.getCredentialHeader());
            }
        } else {
            throw badRequest("不支持的身份源类型");
        }
        optionalRole(config.getDefaultRoleKey());
    }

    /**
     * 校验{@code Config}，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private AgentIdentitySyncConfig requireConfig() {
        AgentIdentitySyncConfig config = mapper.selectConfig();
        if (config == null) {
            throw new ServiceException("身份同步配置尚未初始化，请执行V73数据库迁移", 503);
        }
        return config;
    }

    /**
     * 校验{@code Run}，并在条件不满足时终止处理。
     *
     * @param runId 资源标识
     * @return 处理结果
     */
    private AgentIdentitySyncRun requireRun(Long runId) {
        AgentIdentitySyncRun run = mapper.selectRun(runId);
        if (run == null) {
            throw new ServiceException("身份同步运行不存在", HttpStatus.NOT_FOUND);
        }
        return run;
    }

    /**
     * 校验数据源，并在条件不满足时终止处理。
     *
     * @param sourceId 资源标识
     * @return 处理结果
     */
    private AgentDataSource requireSource(Long sourceId) {
        AgentDataSource source = sourceId == null ? null : dataCatalogMapper.selectSource(sourceId);
        if (source == null) {
            throw badRequest("身份源数据源不存在");
        }
        if (!"active".equals(source.getStatus())) {
            throw unavailable("database", "身份源数据源当前不是活动状态");
        }
        return source;
    }

    /**
     * 处理optional角色并返回对应结果。
     *
     * @param roleKey 角色Key参数
     * @return 处理结果
     */
    private Long optionalRole(String roleKey) {
        if (roleKey == null || roleKey.isBlank()) {
            return null;
        }
        Long roleId = mapper.selectRoleIdByKey(roleKey);
        if (roleId == null) {
            throw badRequest("默认角色不存在或已停用：" + roleKey);
        }
        return roleId;
    }

    /**
     * 处理{@code configView}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private ConfigView configView(AgentIdentitySyncConfig value) {
        return new ConfigView(
            Boolean.TRUE.equals(value.getEnabled()), value.getProviderType(), value.getDataSourceId(),
            value.getEndpointUrl(), value.getCredentialRef(), value.getAuthType(), value.getCredentialHeader(),
            value.getRequestMethod(),
            stringMap(value.getRequestHeadersJson()), objectMap(value.getRequestBodyJson()),
            value.getResponseItemsPath(), value.getTableName(),
            new FieldMapping(
                value.getUsernameColumn(), value.getDisplayNameColumn(), value.getEmailColumn(),
                value.getPhoneColumn(), value.getRemarkColumn(), value.getStatusColumn()
            ),
            extraMappings(value.getExtraMappingsJson()), value.getDefaultRoleKey(), value.getSchedule(),
            value.getRevisionNo(), value.getLastPreviewAt(), value.getLastRunAt(),
            value.getLastRunStatus(), value.getLastError(), value.getUpdateTime()
        );
    }

    /**
     * 执行{@code View}相关的处理流程。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    private RunView runView(AgentIdentitySyncRun run) {
        List<RunItem> items = runItems(run.getItemsJson());
        return new RunView(
            run.getId(), run.getRetryOfRunId(), run.getProviderType(), run.getConfigRevision(),
            run.getStatus(), stringList(run.getRequestedNamesJson()), items,
            integer(run.getDiscoveredCount()), integer(run.getSelectedCount()),
            integer(run.getCreatedCount()), integer(run.getUpdatedCount()),
            integer(run.getSkippedCount()), integer(run.getFailedCount()), run.getErrorSummary(),
            run.getRequestedBy(), run.getStartedAt(), run.getFinishedAt(),
            Set.of("partial", "failed", "unavailable").contains(run.getStatus())
        );
    }

    /**
     * 处理failed快照并返回对应结果。
     *
     * @param run {@code run}参数
     * @return 符合条件的数据集合
     */
    private List<IdentitySyncExternalUser> failedSnapshot(AgentIdentitySyncRun run) {
        return runItems(run.getItemsJson()).stream()
            .filter(item -> "failed".equals(item.result()))
            .map(item -> new IdentitySyncExternalUser(
                item.userName(), item.displayName(), item.email(), item.phoneNumber(),
                item.remark(), item.sourceStatus(), item.extraData() == null ? Map.of() : item.extraData()
            ))
            .toList();
    }

    /**
     * 执行{@code Item}相关的处理流程。
     *
     * @param user 用户参数
     * @param result 结果参数
     * @param localUserId 资源标识
     * @param error {@code error}参数
     * @return 处理结果
     */
    private RunItem runItem(IdentitySyncExternalUser user, String result, Long localUserId, String error) {
        return new RunItem(
            user.userName(), user.displayName(), user.email(), user.phoneNumber(), user.remark(),
            user.status(), user.extraData(), result, localUserId, error
        );
    }

    /**
     * 处理{@code normalizeExtraMappings}并返回对应结果。
     *
     * @param mappings {@code mappings}参数
     * @return 符合条件的数据集合
     */
    private List<ExtraMapping> normalizeExtraMappings(List<ExtraMapping> mappings) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        if (mappings.size() > 64) {
            throw badRequest("扩展字段映射不能超过64项");
        }
        Set<String> keys = new LinkedHashSet<>();
        List<ExtraMapping> result = new ArrayList<>();
        for (ExtraMapping mapping : mappings) {
            String key = mapping.key().strip();
            String column = mapping.sourceColumn().strip();
            if (!keys.add(key)) {
                throw badRequest("扩展字段映射键重复：" + key);
            }
            validateIdentifier(column, "扩展字段");
            result.add(new ExtraMapping(key, column));
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code safeHeaders}并返回对应结果。
     *
     * @param headers {@code headers}参数
     * @return 处理结果
     */
    private Map<String, String> safeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        if (headers.size() > 32) {
            throw badRequest("HTTP请求头不能超过32项");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            result.put(safeHeaderName(header.getKey()), safeHeaderValue(header.getValue()));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /**
     * 处理{@code safeHeaderName}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safeHeaderName(String value) {
        String name = value == null ? "" : value.strip();
        if (!name.matches("[A-Za-z][A-Za-z0-9-]{0,63}")
            || BLOCKED_HTTP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            throw badRequest("HTTP请求头名称无效或不允许配置：" + name);
        }
        return name;
    }

    /**
     * 处理{@code safeHeaderValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safeHeaderValue(String value) {
        String text = value == null ? "" : value.strip();
        if (text.length() > 2048 || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
            throw badRequest("HTTP请求头值无效");
        }
        return text;
    }

    /**
     * 处理apply提供方凭据相关逻辑。
     *
     * @param request 请求参数
     * @param config {@code config}参数
     */
    private void applyProviderCredential(HttpRequest.Builder request, AgentIdentitySyncConfig config) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if ("none".equals(config.getAuthType())) {
            return;
        }
        final DataCredential credential;
        try {
            credential = credentialResolver.resolve(config.getCredentialRef());
        } catch (RuntimeException exception) {
            throw unavailable("http_json", "身份源凭证引用未配置或无效");
        }
        if ("basic".equals(config.getAuthType())) {
            String value = credential.username() + ":" + credential.password();
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                value.getBytes(StandardCharsets.UTF_8)
            ));
        } else if ("bearer".equals(config.getAuthType())) {
            request.header("Authorization", "Bearer " + credential.password());
        } else {
            request.header(safeHeaderName(config.getCredentialHeader()), credential.password());
        }
    }

    /**
     * 处理提供方Uri并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private URI providerUri(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("HTTP身份源接口地址不能为空");
        }
        try {
            URI uri = URI.create(value.strip());
            boolean validScheme = "https".equalsIgnoreCase(uri.getScheme())
                || (allowInsecureHttp && "http".equalsIgnoreCase(uri.getScheme()));
            if (!validScheme || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getPort() == 0) {
                throw badRequest("HTTP身份源必须使用有效HTTPS地址");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw badRequest("HTTP身份源地址无效");
        }
    }

    /**
     * 获取{@code Items}。
     *
     * @param root {@code root}参数
     * @param path {@code path}参数
     * @return 处理结果
     */
    private Object resolveItems(Object root, String path) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (path == null || path.isBlank()) {
            if (root instanceof List<?>) {
                return root;
            }
            if (root instanceof Map<?, ?> map) {
                if (map.get("items") instanceof List<?>) {
                    return map.get("items");
                }
                return map.get("data");
            }
            return null;
        }
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    /**
     * 处理{@code sampleRow}并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @param source 数据源参数
     * @param tableName 名称
     * @return 处理结果
     */
    private Map<String, String> sampleRow(Connection connection, AgentDataSource source, String tableName) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Map<String, String> result = new LinkedHashMap<>();
        String sql = "SELECT * FROM " + quoteQualified(tableName, source.getDbType());
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(seconds(source.getStatementTimeoutMs()));
            statement.setMaxRows(1);
            try (ResultSet rows = statement.executeQuery(sql)) {
                if (!rows.next()) {
                    return Map.of();
                }
                ResultSetMetaData metadata = rows.getMetaData();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    String value = text(rows.getObject(index));
                    result.put(metadata.getColumnLabel(index), bounded(value, 80));
                }
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /**
     * 处理{@code row}并返回对应结果。
     *
     * @param metadata 元数据参数
     * @param rows {@code rows}参数
     * @return 处理结果
     * @throws Exception 当处理过程无法正常完成时抛出
     */
    private Map<String, Object> row(ResultSetMetaData metadata, ResultSet rows) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            result.put(metadata.getColumnLabel(index), rows.getObject(index));
        }
        return result;
    }

    /**
     * 处理{@code quoteQualified}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param dbType 业务类型
     * @return 处理结果
     */
    private String quoteQualified(String value, String dbType) {
        QualifiedTable table = qualifiedTable(value);
        return table.schema() == null
            ? quoteIdentifier(table.table(), dbType)
            : quoteIdentifier(table.schema(), dbType) + "." + quoteIdentifier(table.table(), dbType);
    }

    /**
     * 处理{@code quoteIdentifier}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param dbType 业务类型
     * @return 处理结果
     */
    private String quoteIdentifier(String value, String dbType) {
        validateIdentifier(value, "字段或表名");
        return switch (dbType == null ? "" : dbType.toLowerCase(Locale.ROOT)) {
            case "mysql", "clickhouse" -> "`" + value + "`";
            case "sqlserver" -> "[" + value + "]";
            default -> "\"" + value + "\"";
        };
    }

    /**
     * 处理{@code qualifiedTable}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private QualifiedTable qualifiedTable(String value) {
        String text = value == null ? "" : value.strip();
        String[] parts = text.split("\\.", -1);
        if (parts.length == 1) {
            validateIdentifier(parts[0], "表名");
            return new QualifiedTable(null, parts[0]);
        }
        if (parts.length == 2) {
            validateIdentifier(parts[0], "schema");
            validateIdentifier(parts[1], "表名");
            return new QualifiedTable(parts[0], parts[1]);
        }
        throw badRequest("表名只支持table或schema.table格式");
    }

    /**
     * 校验{@code OptionalIdentifier}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     */
    private void validateOptionalIdentifier(String value, String label) {
        if (value != null) {
            validateIdentifier(value, label);
        }
    }

    /**
     * 校验{@code Identifier}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     */
    private void validateIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw badRequest(label + "包含非法字符");
        }
    }

    /**
     * 创建并保存{@code Column}。
     *
     * @param columns {@code columns}参数
     * @param value {@code value}参数
     */
    private void addColumn(Collection<String> columns, String value) {
        if (value != null && !value.isBlank()) {
            columns.add(value);
        }
    }

    /**
     * 处理{@code normalizedNames}并返回对应结果。
     *
     * @param values {@code values}参数
     * @return 符合条件的数据集合
     */
    private List<String> normalizedNames(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(String::strip).filter(value -> !value.isBlank()).distinct().toList();
    }

    /**
     * 处理调度Due并返回对应结果。
     *
     * @param config {@code config}参数
     * @param now {@code now}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean scheduleDue(AgentIdentitySyncConfig config, LocalDateTime now) {
        LocalDateTime last = config.getLastRunAt();
        return switch (config.getSchedule()) {
            case "hourly" -> last == null || last.isBefore(now.minusMinutes(55));
            case "daily" -> now.getHour() == 2
                && (last == null || last.toLocalDate().isBefore(now.toLocalDate()));
            case "weekly" -> now.getDayOfWeek() == DayOfWeek.MONDAY && now.getHour() == 2
                && (last == null || last.isBefore(now.minusDays(6)));
            default -> false;
        };
    }

    /**
     * 处理{@code localStatus}并返回对应结果。
     *
     * @param sourceStatus 目标状态
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private String localStatus(String sourceStatus, String fallback) {
        if (sourceStatus == null || sourceStatus.isBlank()) {
            return fallback == null ? "0" : fallback;
        }
        String normalized = sourceStatus.strip().toLowerCase(Locale.ROOT);
        if (Set.of("0", "true", "active", "enabled", "normal", "正常", "启用").contains(normalized)) {
            return "0";
        }
        if (Set.of("1", "false", "inactive", "disabled", "blocked", "停用", "禁用").contains(normalized)) {
            return "1";
        }
        return fallback == null ? "0" : fallback;
    }

    /**
     * 处理{@code syncRemark}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String syncRemark(String value) {
        String text = trimToNull(value);
        return text == null ? "第三方身份源同步" : "第三方身份源同步: " + text;
    }

    /**
     * 处理{@code jsonSafe}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Object jsonSafe(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return Objects.toString(value);
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = Objects.toString(value).strip();
        return text.isBlank() ? null : text;
    }

    /**
     * 处理{@code defaultText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private String defaultText(String value, String fallback) {
        String normalized = trimToNull(value);
        return normalized == null ? fallback : normalized;
    }

    /**
     * 处理{@code trimToNull}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 处理{@code trimToEmpty}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String trimToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 处理{@code validUsername}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean validUsername(String value) {
        return value != null && USERNAME.matcher(value).matches();
    }

    /**
     * 处理{@code seconds}并返回对应结果。
     *
     * @param milliseconds {@code milliseconds}参数
     * @return 处理结果
     */
    private int seconds(Integer milliseconds) {
        int value = milliseconds == null ? 30_000 : milliseconds;
        return Math.max(1, (value + 999) / 1000);
    }

    /**
     * 处理{@code integer}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private int integer(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private String bounded(String value, int maximum) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\0', ' ').strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    /**
     * 处理{@code safeItemError}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String safeItemError(RuntimeException exception) {
        if (exception instanceof ServiceException serviceException) {
            return bounded(serviceException.getMessage(), 500);
        }
        return "本地用户写入失败";
    }

    /**
     * 处理{@code extraMappings}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<ExtraMapping> extraMappings(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(jsonMapper.readValue(value, EXTRA_MAPPING_LIST));
        } catch (RuntimeException exception) {
            throw badRequest("扩展字段映射不是有效JSON");
        }
    }

    /**
     * 处理{@code stringMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, String> stringMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return Map.copyOf(jsonMapper.readValue(value, STRING_MAP));
        } catch (RuntimeException exception) {
            throw badRequest("HTTP请求头配置不是有效JSON");
        }
    }

    /**
     * 处理{@code objectMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> objectMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return Collections.unmodifiableMap(new LinkedHashMap<>(jsonMapper.readValue(value, OBJECT_MAP)));
        } catch (RuntimeException exception) {
            throw badRequest("HTTP请求体配置不是有效JSON");
        }
    }

    /**
     * 处理{@code stringList}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<String> stringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(jsonMapper.readValue(value, STRING_LIST));
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /**
     * 执行{@code Items}相关的处理流程。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<RunItem> runItems(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(jsonMapper.readValue(value, RUN_ITEM_LIST));
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /**
     * 校验{@code Administrator}，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private CurrentPrincipal requireAdministrator() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (principal == null || !principal.isHuman()
            || !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            throw new ServiceException("仅平台管理员可以管理身份同步", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 处理提供方Failure并返回对应结果。
     *
     * @param provider 提供方参数
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private ServiceException providerFailure(String provider, ServiceException exception) {
        if (Integer.valueOf(HttpStatus.BAD_REQUEST).equals(exception.getCode())) {
            return exception;
        }
        return unavailable(provider, exception.getMessage());
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param provider 提供方参数
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException unavailable(String provider, String message) {
        return new ServiceException("identity_provider_unavailable: " + provider + " (" + message + ")", 503);
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 处理{@code conflict}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    /**
     * 封装{@code QualifiedTable}相关的不可变数据。
     */
    private record QualifiedTable(String schema, String table) {
    }

    /**
     * 封装Applied用户相关的不可变数据。
     */
    private record AppliedUser(Long userId, String result) {
    }
}
