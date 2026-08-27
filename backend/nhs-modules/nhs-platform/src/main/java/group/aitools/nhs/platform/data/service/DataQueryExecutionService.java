package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataQuery;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.service.ReadOnlySqlValidator.ValidatedSql;
import group.aitools.nhs.platform.data.web.DataQueryRequest;
import group.aitools.nhs.platform.data.web.DataQueryResultView;
import group.aitools.nhs.platform.data.web.DataQueryValidationView;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Validates, re-authorizes and executes bounded read-only dataset queries. */
@Service
public class DataQueryExecutionService {

    private static final int MAX_STORED_RESULT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_STORED_RESULT_ROWS = 10_000;

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final TaskQueryService taskQueryService;
    private final PlatformIdGenerator idGenerator;
    private final DataCatalogMapper mapper;
    private final DataSourceCatalogService catalogService;
    private final ReadOnlySqlValidator sqlValidator;
    private final DatasetRowPolicySqlRewriter rowPolicyRewriter;
    private final ReadOnlyJdbcConnectionFactory connectionFactory;
    private final JsonMapper jsonMapper;

    public DataQueryExecutionService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        TaskQueryService taskQueryService,
        PlatformIdGenerator idGenerator,
        DataCatalogMapper mapper,
        DataSourceCatalogService catalogService,
        ReadOnlySqlValidator sqlValidator,
        ReadOnlyJdbcConnectionFactory connectionFactory,
        JsonMapper jsonMapper
    ) {
        this(
            principalProvider, authorizationEnforcer, taskQueryService, idGenerator, mapper,
            catalogService, sqlValidator, new DatasetRowPolicySqlRewriter(jsonMapper, sqlValidator),
            connectionFactory, jsonMapper
        );
    }

    @Autowired
    public DataQueryExecutionService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        TaskQueryService taskQueryService,
        PlatformIdGenerator idGenerator,
        DataCatalogMapper mapper,
        DataSourceCatalogService catalogService,
        ReadOnlySqlValidator sqlValidator,
        DatasetRowPolicySqlRewriter rowPolicyRewriter,
        ReadOnlyJdbcConnectionFactory connectionFactory,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.taskQueryService = taskQueryService;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.catalogService = catalogService;
        this.sqlValidator = sqlValidator;
        this.rowPolicyRewriter = rowPolicyRewriter;
        this.connectionFactory = connectionFactory;
        this.jsonMapper = jsonMapper;
    }

    public DataQueryValidationView validate(DataQueryRequest request) {
        return validateInternal(request, principalProvider.currentPrincipal());
    }

    /** Validates for an explicitly resolved human principal without changing the login context. */
    public DataQueryValidationView validateForPrincipal(CurrentPrincipal principal, DataQueryRequest request) {
        if (principal == null || !principal.isHuman()) {
            throw new SecurityException("SQL权限校验必须绑定有效用户主体");
        }
        return validateInternal(request, principal);
    }

    private DataQueryValidationView validateInternal(DataQueryRequest request, CurrentPrincipal principal) {
        PreparedAccess access = authorize(
            request.datasetId(), request.taskId(), principal, true, true
        );
        List<AgentDataTable> tables = mapper.selectTables(access.dataset().getId());
        List<AgentDataColumn> columns = mapper.selectColumns(access.dataset().getId());
        ValidatedSql validated = applyRowPolicy(
            access, sqlValidator.validate(request.sql(), tables, columns), tables, columns
        );
        return validationView(access, validated);
    }

    public DataQueryResultView execute(DataQueryRequest request) {
        return executeInternal(request, principalProvider.currentPrincipal(), true, true, null);
    }

    /** Executes an interactive query while retaining the caller-provided audit trace. */
    public DataQueryResultView executeWithTrace(DataQueryRequest request, String traceId) {
        return executeInternal(
            request, principalProvider.currentPrincipal(), true, true, requireTraceId(traceId)
        );
    }

    /** Executes a governed query for a persisted scheduler principal without HTTP identity. */
    public DataQueryResultView executeWithTrace(
        CurrentPrincipal principal,
        DataQueryRequest request,
        String traceId
    ) {
        if (principal == null || !principal.isHuman()) {
            throw new SecurityException("后台数据查询必须绑定有效用户主体");
        }
        return executeInternal(request, principal, false, false, requireTraceId(traceId));
    }

    /** Rechecks current interactive query access before exposing a stored result snapshot. */
    public void requireInteractiveQueryAccess(Long datasetId) {
        authorize(datasetId, null, principalProvider.currentPrincipal(), true, true);
    }

    /** Rechecks a dataset against an explicitly resolved human scheduler principal. */
    public void requireQueryAccess(CurrentPrincipal principal, Long datasetId) {
        if (principal == null || !principal.isHuman()) {
            throw new SecurityException("数据集权限校验必须绑定有效用户主体");
        }
        authorize(datasetId, null, principal, false, false);
    }

    /** Executes a user-owned background operation without depending on an HTTP login context. */
    public DataQueryResultView executeBackground(CurrentPrincipal principal, DataQueryRequest request) {
        if (principal == null || !principal.isHuman()) {
            throw new SecurityException("后台数据查询必须绑定有效用户主体");
        }
        return executeInternal(request, principal, false, false, null);
    }

    /** Executes for a principal frozen into a formal runtime request without reading HTTP identity. */
    public DataQueryResultView executeRuntime(
        CurrentPrincipal principal,
        DataQueryRequest request,
        String traceId
    ) {
        if (request.taskId() == null || request.runId() == null) {
            throw new SecurityException("运行时数据查询必须绑定任务和运行记录");
        }
        return executeInternal(request, principal, false, false, requireTraceId(traceId));
    }

    /** Executes a taskless conversation/embed query after its frozen SQL tool was verified. */
    public DataQueryResultView executeSessionRuntime(
        CurrentPrincipal principal,
        DataQueryRequest request,
        String traceId
    ) {
        if (request.taskId() != null || request.runId() != null || request.conversationId() == null) {
            throw new SecurityException("会话数据查询必须绑定会话且不能绑定任务运行记录");
        }
        return executeInternal(request, principal, false, false, requireTraceId(traceId));
    }

    private DataQueryResultView executeInternal(
        DataQueryRequest request,
        CurrentPrincipal principal,
        boolean userInterfaceOperation,
        boolean requireTaskView,
        String traceId
    ) {
        PreparedAccess access = authorize(
            request.datasetId(), request.taskId(), principal, userInterfaceOperation, requireTaskView
        );
        AgentDataQuery query = queryFact(request, access, traceId);
        mapper.insertQuery(query);

        ValidatedSql validated;
        try {
            List<AgentDataTable> tables = mapper.selectTables(access.dataset().getId());
            List<AgentDataColumn> columns = mapper.selectColumns(access.dataset().getId());
            validated = applyRowPolicy(
                access, sqlValidator.validate(request.sql(), tables, columns), tables, columns
            );
            query.setSqlText(validated.sql());
            query.setSqlHash(validated.sqlHash());
            query.setSqlPlanJson(planJson(validated));
            query.setPermissionSummaryJson(permissionJson(access));
            query.setStartedAt(LocalDateTime.now());
            if (mapper.markQueryRunning(query) != 1) {
                throw conflict("查询状态已变化");
            }
        } catch (RuntimeException exception) {
            reject(query, exception);
            throw exception;
        }

        Instant started = Instant.now();
        try {
            reauthorize(access, request.taskId(), userInterfaceOperation, requireTaskView);
            ValidatedSql currentValidation = sqlValidator.validate(
                validated.sql(), mapper.selectTables(access.dataset().getId()),
                mapper.selectColumns(access.dataset().getId())
            );
            ExecutionRows result = executeJdbc(access.source(), currentValidation);
            LocalDateTime finishedAt = LocalDateTime.now();
            query.setRowCount(result.rowCount());
            query.setResultBytes(result.bytes());
            query.setResultTruncated(result.truncated());
            query.setFinishedAt(finishedAt);
            if (result.rows().size() > MAX_STORED_RESULT_ROWS
                || result.bytes() > MAX_STORED_RESULT_BYTES) {
                throw new ServiceException("查询结果超过可导出快照上限", 413);
            }
            String columnsJson = jsonMapper.writeValueAsString(result.columns());
            String rowsJson = jsonMapper.writeValueAsString(result.rows());
            String resultHash = ContentHashing.sha256(columnsJson + "\0" + rowsJson);
            if (mapper.completeQueryWithResult(query, columnsJson, rowsJson, resultHash) != 1) {
                throw conflict("查询完成状态写入失败");
            }
            return new DataQueryResultView(
                query.getId(), result.columns(), result.rows(), result.rowCount(),
                result.bytes(), result.truncated(), Duration.between(started, Instant.now()).toMillis(), resultHash
            );
        } catch (Exception exception) {
            fail(query, exception);
            if (exception instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new ServiceException("只读查询执行失败，请检查 SQL 与数据源状态", 502);
        }
    }

    private PreparedAccess authorize(
        Long datasetId,
        Long taskId,
        CurrentPrincipal principal,
        boolean userInterfaceOperation,
        boolean requireTaskView
    ) {
        AgentDataDataset dataset = catalogService.requireDataset(datasetId);
        AgentDataSource source = catalogService.requireSource(dataset.getDataSourceId());
        if (!"active".equals(dataset.getStatus()) || !"active".equals(source.getStatus())) {
            throw conflict("数据源和数据集必须处于活动状态");
        }
        if (taskId != null) {
            if (requireTaskView) {
                taskQueryService.get(taskId);
            }
            if (mapper.countTaskDatasetQueryBinding(taskId, datasetId) < 1) {
                throw new ServiceException("任务没有冻结当前数据集查询权限", HttpStatus.FORBIDDEN);
            }
        }
        Set<BusinessRelation> relations = principal.isHuman() && principal.id().equals(dataset.getOwnerId())
            ? Set.of(BusinessRelation.OWNER) : Set.of();
        AuthorizationDecision decision = authorizationEnforcer.requireAllowed(
            principal,
            new PermissionContext(
                "dataset", datasetId, dataset.getDatasetKey(), "query", ResourceState.ACTIVE,
                userInterfaceOperation, relations, taskId
            )
        );
        return new PreparedAccess(principal, source, dataset, decision);
    }

    private void reauthorize(
        PreparedAccess expected,
        Long taskId,
        boolean userInterfaceOperation,
        boolean requireTaskView
    ) {
        PreparedAccess current = authorize(
            expected.dataset().getId(), taskId, expected.principal(),
            userInterfaceOperation, requireTaskView
        );
        if (!expected.source().getRevisionNo().equals(current.source().getRevisionNo())
            || !expected.dataset().getRevisionNo().equals(current.dataset().getRevisionNo())) {
            throw conflict("数据源或数据集配置已变化，请重新生成查询");
        }
    }

    private AgentDataQuery queryFact(
        DataQueryRequest request,
        PreparedAccess access,
        String traceId
    ) {
        AgentDataQuery query = new AgentDataQuery();
        query.setId(idGenerator.nextId());
        query.setTaskId(request.taskId());
        query.setRunId(request.runId());
        query.setConversationId(request.conversationId());
        query.setTraceId(traceId);
        query.setDataSourceId(access.source().getId());
        query.setDatasetId(access.dataset().getId());
        query.setDataSourceRevision(access.source().getRevisionNo());
        query.setDatasetRevision(access.dataset().getRevisionNo());
        query.setUserQuery(request.userQuery().strip());
        query.setSqlText(request.sql().strip());
        query.setSqlHash(ContentHashing.sha256(request.sql().strip()));
        query.setStatus("planning");
        query.setCreatedBy(access.principal().id());
        query.setCreatedAt(LocalDateTime.now());
        return query;
    }

    private String requireTraceId(String traceId) {
        if (traceId == null || traceId.isBlank() || traceId.length() > 64
            || !traceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
            throw new SecurityException("运行时数据查询必须绑定有效Trace");
        }
        return traceId;
    }

    private ExecutionRows executeJdbc(AgentDataSource source, ValidatedSql validated) throws Exception {
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        long bytes;
        boolean truncated = false;
        long rowCount;
        try (Connection connection = connectionFactory.open(source);
             Statement controls = connection.createStatement();
             Statement statement = connection.createStatement()) {
            connectionFactory.prepareQuerySession(controls, source);
            statement.setQueryTimeout(seconds(source.getStatementTimeoutMs()));
            statement.setFetchSize(Math.min(source.getMaxRows() + 1, 500));
            statement.setMaxRows(source.getMaxRows() + 1);
            try (ResultSet result = statement.executeQuery(validated.sql())) {
                ResultSetMetaData metadata = result.getMetaData();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    columns.add(metadata.getColumnLabel(index));
                }
                bytes = jsonMapper.writeValueAsBytes(columns).length;
                while (result.next()) {
                    if (rows.size() >= source.getMaxRows()) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = new ArrayList<>(columns.size());
                    for (int index = 1; index <= columns.size(); index++) {
                        row.add(resultValue(result.getObject(index)));
                    }
                    List<Object> immutable = Collections.unmodifiableList(new ArrayList<>(row));
                    bytes += jsonMapper.writeValueAsBytes(immutable).length;
                    if (bytes > source.getMaxResultBytes()) {
                        throw new ServiceException("查询结果超过数据源字节上限", 413);
                    }
                    rows.add(immutable);
                }
            }
            rowCount = rows.size();
            if (truncated) {
                rowCount = exactRowCount(connection, source, validated.sql());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("columns", columns);
            payload.put("rows", rows);
            bytes = jsonMapper.writeValueAsBytes(payload).length;
            if (bytes > source.getMaxResultBytes()) {
                throw new ServiceException("查询结果超过数据源字节上限", 413);
            }
            connectionFactory.rollback(connection, source);
        }
        return new ExecutionRows(List.copyOf(columns), List.copyOf(rows), rowCount, bytes, truncated);
    }

    private long exactRowCount(Connection connection, AgentDataSource source, String sql) throws Exception {
        String countSql = "SELECT COUNT(1) FROM (" + sql + ") agent_server_count";
        try (Statement countStatement = connection.createStatement()) {
            countStatement.setQueryTimeout(seconds(source.getStatementTimeoutMs()));
            try (ResultSet result = countStatement.executeQuery(countSql)) {
                if (!result.next()) {
                    throw new ServiceException("查询总数未返回结果", 502);
                }
                Object value = result.getObject(1);
                if (!(value instanceof Number number) || number.longValue() < 0) {
                    throw new ServiceException("查询总数返回值无效", 502);
                }
                return number.longValue();
            }
        }
    }

    private Object resultValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
            || value instanceof Byte || value instanceof Short || value instanceof Integer
            || value instanceof Long || value instanceof Float || value instanceof Double
            || value instanceof BigDecimal || value instanceof BigInteger) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof TemporalAccessor || value instanceof java.util.Date
            || value instanceof UUID) {
            return value.toString();
        }
        String text = String.valueOf(value);
        if (text.getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
            throw new ServiceException("查询包含超过 256KB 的单个字段", 413);
        }
        return text;
    }

    private DataQueryValidationView validationView(PreparedAccess access, ValidatedSql validated) {
        return new DataQueryValidationView(
            access.dataset().getId(), validated.tables(), validated.columns(), validated.sqlHash(),
            access.source().getMaxRows(), access.source().getStatementTimeoutMs(),
            access.source().getMaxResultBytes()
        );
    }

    private String planJson(ValidatedSql validated) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("tables", validated.tables());
        plan.put("columns", validated.columns());
        plan.put("validator", "jsqlparser-5.2");
        return jsonMapper.writeValueAsString(plan);
    }

    private String permissionJson(PreparedAccess access) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("principalId", access.principal().id());
        summary.put("principalType", access.principal().type().name().toLowerCase());
        summary.put("effect", access.decision().effect().name().toLowerCase());
        summary.put("reasonCode", access.decision().reasonCode());
        summary.put("rowPolicyApplied", Boolean.TRUE.equals(access.dataset().getEnableRowPolicy()));
        return jsonMapper.writeValueAsString(summary);
    }

    private ValidatedSql applyRowPolicy(
        PreparedAccess access,
        ValidatedSql validated,
        List<AgentDataTable> tables,
        List<AgentDataColumn> columns
    ) {
        return rowPolicyRewriter.apply(
            access.dataset(), access.principal(), tables, columns, validated
        );
    }

    private void reject(AgentDataQuery query, RuntimeException exception) {
        query.setStatus("rejected");
        query.setErrorSummary(errorSummary(exception, "SQL 校验失败"));
        query.setFinishedAt(LocalDateTime.now());
        mapper.markQueryFailed(query);
    }

    private void fail(AgentDataQuery query, Exception exception) {
        query.setStatus("failed");
        query.setErrorSummary(errorSummary(exception, "查询执行失败"));
        query.setFinishedAt(LocalDateTime.now());
        mapper.markQueryFailed(query);
    }

    private String errorSummary(Exception exception, String fallback) {
        if (exception instanceof ServiceException serviceException
            && serviceException.getMessage() != null && serviceException.getMessage().length() <= 512) {
            return serviceException.getMessage();
        }
        return fallback;
    }

    private int seconds(int milliseconds) {
        return Math.max(1, (milliseconds + 999) / 1000);
    }

    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    private record PreparedAccess(
        CurrentPrincipal principal,
        AgentDataSource source,
        AgentDataDataset dataset,
        AuthorizationDecision decision
    ) {
    }

    private record ExecutionRows(
        List<String> columns,
        List<List<Object>> rows,
        long rowCount,
        long bytes,
        boolean truncated
    ) {
    }
}
