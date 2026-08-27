package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.persistence.row.DatasetDeleteImpactRow;
import group.aitools.nhs.platform.data.web.CreateDataSourceRequest;
import group.aitools.nhs.platform.data.web.CreateDatasetRequest;
import group.aitools.nhs.platform.data.web.DataColumnView;
import group.aitools.nhs.platform.data.web.DataSourceConnectionView;
import group.aitools.nhs.platform.data.web.DataSourceView;
import group.aitools.nhs.platform.data.web.DataTableView;
import group.aitools.nhs.platform.data.web.DatasetDeleteImpactView;
import group.aitools.nhs.platform.data.web.DatasetDeleteImpactView.CategoryView;
import group.aitools.nhs.platform.data.web.DatasetView;
import group.aitools.nhs.platform.data.web.UpdateDataColumnRequest;
import group.aitools.nhs.platform.data.web.UpdateDataSourceRequest;
import group.aitools.nhs.platform.data.web.UpdateDataTableRequest;
import group.aitools.nhs.platform.data.web.UpdateDatasetRequest;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Data source and dataset control plane. */
@Service
public class DataSourceCatalogService {

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final DataCatalogMapper mapper;
    private final DataSourceConfigurationValidator validator;
    private final DataSourceEndpointPolicy endpointPolicy;
    private final ReadOnlyJdbcConnectionFactory connectionFactory;
    private final JsonMapper jsonMapper;

    public DataSourceCatalogService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        DataCatalogMapper mapper,
        DataSourceConfigurationValidator validator,
        DataSourceEndpointPolicy endpointPolicy,
        ReadOnlyJdbcConnectionFactory connectionFactory,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.validator = validator;
        this.endpointPolicy = endpointPolicy;
        this.connectionFactory = connectionFactory;
        this.jsonMapper = jsonMapper;
    }

    public List<DataSourceView> listSources(int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        require(principal, "data_source", null, "list", ResourceState.ACTIVE, Set.of());
        return mapper.selectSources(limit).stream().map(source -> DataSourceView.from(source, jsonMapper)).toList();
    }

    public DataSourceView getSource(Long sourceId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataSource source = requireSource(sourceId);
        require(principal, "data_source", sourceId, "view", state(source.getStatus()), Set.of());
        return DataSourceView.from(source, jsonMapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public DataSourceView createSource(CreateDataSourceRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        require(principal, "data_source", null, "create", ResourceState.ACTIVE, Set.of());
        String dbType = validator.dbType(request.dbType());
        var target = endpointPolicy.normalize(dbType, request.endpointUrl(), request.databaseName());
        Map<String, Object> config = validator.config(dbType, request.config());
        LocalDateTime now = LocalDateTime.now();
        AgentDataSource source = new AgentDataSource();
        source.setId(idGenerator.nextId());
        source.setSourceKey(request.sourceKey().strip());
        source.setName(request.name().strip());
        source.setDbType(dbType);
        source.setEndpointUrl(target.normalizedEndpoint());
        source.setDatabaseName(target.database());
        source.setCredentialRef(validator.credentialReference(request.credentialRef()));
        source.setReadonly(true);
        source.setStatus(request.status());
        source.setConfigJson(jsonMapper.writeValueAsString(config));
        source.setRevisionNo(1);
        source.setConnectionTimeoutMs(request.connectionTimeoutMs());
        source.setStatementTimeoutMs(request.statementTimeoutMs());
        source.setMaxRows(request.maxRows());
        source.setMaxResultBytes(request.maxResultBytes());
        source.setCreateBy(principal.id());
        source.setCreateTime(now);
        source.setDelFlag("0");
        if (mapper.insertSource(source) != 1) {
            throw conflict("数据源创建失败");
        }
        return DataSourceView.from(source, jsonMapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public DataSourceView updateSource(Long sourceId, UpdateDataSourceRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataSource current = requireSource(sourceId);
        require(principal, "data_source", sourceId, "update", state(current.getStatus()), Set.of());
        String dbType = validator.dbType(request.dbType());
        var target = endpointPolicy.normalize(dbType, request.endpointUrl(), request.databaseName());
        String credentialRef = request.credentialRef() == null || request.credentialRef().isBlank()
            ? current.getCredentialRef() : validator.credentialReference(request.credentialRef());
        String configJson = jsonMapper.writeValueAsString(validator.config(dbType, request.config()));
        boolean connectionChanged = !Objects.equals(current.getDbType(), dbType)
            || !Objects.equals(current.getEndpointUrl(), target.normalizedEndpoint())
            || !Objects.equals(current.getDatabaseName(), target.database())
            || !Objects.equals(current.getCredentialRef(), credentialRef)
            || !Objects.equals(current.getConfigJson(), configJson);
        current.setName(request.name().strip());
        current.setDbType(dbType);
        current.setEndpointUrl(target.normalizedEndpoint());
        current.setDatabaseName(target.database());
        current.setCredentialRef(credentialRef);
        current.setConfigJson(configJson);
        current.setStatus(request.status());
        current.setConnectionTimeoutMs(request.connectionTimeoutMs());
        current.setStatementTimeoutMs(request.statementTimeoutMs());
        current.setMaxRows(request.maxRows());
        current.setMaxResultBytes(request.maxResultBytes());
        current.setRevisionNo(request.revisionNo());
        current.setUpdateBy(principal.id());
        current.setUpdateTime(LocalDateTime.now());
        if (mapper.updateSource(current) != 1) {
            throw conflict("数据源已被其他操作修改，请刷新后重试");
        }
        if (connectionChanged) {
            mapper.invalidateSourceDatasets(sourceId, principal.id(), current.getUpdateTime());
            mapper.invalidateSourceTables(sourceId, principal.id(), current.getUpdateTime());
        }
        return DataSourceView.from(requireSource(sourceId), jsonMapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteSource(Long sourceId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataSource source = requireSource(sourceId);
        require(principal, "data_source", sourceId, "delete", state(source.getStatus()), Set.of());
        if (mapper.softDeleteSource(sourceId, principal.id(), LocalDateTime.now()) != 1) {
            throw conflict("数据源仍有活动数据集，不能删除");
        }
    }

    public DataSourceConnectionView testConnection(Long sourceId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataSource source = requireSource(sourceId);
        require(principal, "data_source", sourceId, "operate", state(source.getStatus()), Set.of());
        Instant started = Instant.now();
        LocalDateTime testedAt = LocalDateTime.now();
        try (Connection connection = connectionFactory.open(source);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(seconds(source.getStatementTimeoutMs()));
            try (ResultSet result = statement.executeQuery(connectionFactory.validationQuery(source))) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new IllegalStateException("unexpected connectivity result");
                }
            }
            connectionFactory.rollback(connection, source);
            long elapsed = Duration.between(started, Instant.now()).toMillis();
            mapper.recordConnectionTest(sourceId, "success", null, principal.id(), testedAt);
            return new DataSourceConnectionView(true, "连接成功", elapsed, testedAt);
        } catch (Exception exception) {
            long elapsed = Duration.between(started, Instant.now()).toMillis();
            String message = connectionFailureMessage(exception);
            mapper.recordConnectionTest(sourceId, "failed", message, principal.id(), testedAt);
            return new DataSourceConnectionView(false, message, elapsed, testedAt);
        }
    }

    public List<DatasetView> listDatasets(int limit) {
        return listDatasets(principalProvider.currentPrincipal(), limit);
    }

    /** Resolves dataset visibility for an already-authenticated background runtime principal. */
    public List<DatasetView> listDatasets(CurrentPrincipal principal, int limit) {
        List<DatasetView> result = new ArrayList<>();
        for (AgentDataDataset dataset : mapper.selectDatasets(limit)) {
            AuthorizationDecision decision = authorizationEnforcer.decide(
                principal, datasetContext(principal, dataset, "view")
            );
            if (decision.allowed()) {
                result.add(DatasetView.from(dataset, jsonMapper));
            }
        }
        return List.copyOf(result);
    }

    public DatasetView getDataset(Long datasetId) {
        return getDataset(principalProvider.currentPrincipal(), datasetId);
    }

    /** Resolves a dataset for an already-authenticated background runtime principal. */
    public DatasetView getDataset(CurrentPrincipal principal, Long datasetId) {
        AgentDataDataset dataset = requireDataset(datasetId);
        authorizationEnforcer.requireAllowed(principal, datasetContext(principal, dataset, "view"));
        return DatasetView.from(dataset, jsonMapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public DatasetView createDataset(CreateDatasetRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataSource source = requireSource(request.dataSourceId());
        require(principal, "data_source", source.getId(), "manage", state(source.getStatus()), Set.of());
        List<String> schemas = validator.schemas(
            source.getDbType(), source.getDatabaseName(), request.schemaNames()
        );
        LocalDateTime now = LocalDateTime.now();
        AgentDataDataset dataset = new AgentDataDataset();
        dataset.setId(idGenerator.nextId());
        dataset.setDataSourceId(source.getId());
        dataset.setDatasetKey(request.datasetKey().strip());
        dataset.setName(request.name().strip());
        dataset.setDescription(trimToNull(request.description()));
        dataset.setStatus(request.status());
        dataset.setEnableRowPolicy(false);
        dataset.setRowPolicyJson("{}");
        dataset.setSchemaNamesJson(jsonMapper.writeValueAsString(schemas));
        dataset.setRevisionNo(1);
        dataset.setOwnerId(principal.id());
        dataset.setCreateBy(principal.id());
        dataset.setCreateTime(now);
        dataset.setDelFlag("0");
        if (mapper.insertDataset(dataset) != 1) {
            throw conflict("数据集创建失败");
        }
        return DatasetView.from(dataset, jsonMapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public DatasetView updateDataset(Long datasetId, UpdateDatasetRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataDataset dataset = requireDataset(datasetId);
        authorizationEnforcer.requireAllowed(principal, datasetContext(dataset, "update"));
        AgentDataSource source = requireSource(dataset.getDataSourceId());
        String schemaNamesJson = jsonMapper.writeValueAsString(validator.schemas(
            source.getDbType(), source.getDatabaseName(), request.schemaNames()
        ));
        boolean schemasChanged = !dataset.getSchemaNamesJson().equals(schemaNamesJson);
        dataset.setName(request.name().strip());
        dataset.setDescription(trimToNull(request.description()));
        dataset.setStatus(request.status());
        dataset.setSchemaNamesJson(schemaNamesJson);
        dataset.setRevisionNo(request.revisionNo());
        dataset.setUpdateBy(principal.id());
        dataset.setUpdateTime(LocalDateTime.now());
        if (mapper.updateDataset(dataset) != 1) {
            throw conflict("数据集已被其他操作修改，请刷新后重试");
        }
        if (schemasChanged) {
            LocalDateTime now = LocalDateTime.now();
            mapper.markDatasetMetadataStale(datasetId, principal.id(), now);
            mapper.deactivateMissingTables(datasetId, List.of(), principal.id(), now);
        }
        return DatasetView.from(requireDataset(datasetId), jsonMapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDataset(Long datasetId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataDataset current = requireDataset(datasetId);
        authorizationEnforcer.requireAllowed(principal, datasetContext(principal, current, "delete"));
        AgentDataDataset locked = mapper.lockDatasetForDelete(datasetId);
        if (locked == null) {
            throw notFound("数据集不存在");
        }
        authorizationEnforcer.requireAllowed(principal, datasetContext(principal, locked, "delete"));
        DatasetDeleteImpactView impact = impactView(datasetId, mapper.selectDatasetDeleteImpact(datasetId));
        if (!impact.deletable()) {
            throw conflict("数据集仍被活动任务、Agent 或权限规则引用，不能删除");
        }
        if (mapper.softDeleteDataset(datasetId, principal.id(), LocalDateTime.now()) != 1) {
            throw conflict("数据集删除失败");
        }
    }

    public DatasetDeleteImpactView datasetDeleteImpact(Long datasetId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataDataset dataset = requireDataset(datasetId);
        authorizationEnforcer.requireAllowed(principal, datasetContext(principal, dataset, "delete"));
        return impactView(datasetId, mapper.selectDatasetDeleteImpact(datasetId));
    }

    public List<DataTableView> metadata(Long datasetId) {
        return metadata(principalProvider.currentPrincipal(), datasetId);
    }

    /** Reads dataset metadata for an already-authenticated background runtime principal. */
    public List<DataTableView> metadata(CurrentPrincipal principal, Long datasetId) {
        AgentDataDataset dataset = requireDataset(datasetId);
        authorizationEnforcer.requireAllowed(principal, datasetContext(principal, dataset, "read"));
        Map<Long, List<DataColumnView>> columns = new LinkedHashMap<>();
        for (AgentDataColumn column : mapper.selectColumns(datasetId)) {
            columns.computeIfAbsent(column.getTableId(), ignored -> new ArrayList<>()).add(DataColumnView.from(column));
        }
        return mapper.selectTables(datasetId).stream()
            .map(table -> DataTableView.from(table, columns.getOrDefault(table.getId(), List.of())))
            .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateTable(Long datasetId, Long tableId, UpdateDataTableRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataDataset dataset = requireDataset(datasetId);
        authorizationEnforcer.requireAllowed(principal, datasetContext(dataset, "update"));
        if (mapper.updateTableGovernance(
            datasetId, tableId, request.displayName().strip(), trimToNull(request.description()),
            request.status(), principal.id(), LocalDateTime.now()
        ) != 1) {
            throw notFound("数据表不存在");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateColumn(Long datasetId, Long columnId, UpdateDataColumnRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataDataset dataset = requireDataset(datasetId);
        authorizationEnforcer.requireAllowed(principal, datasetContext(dataset, "update"));
        if (mapper.updateColumnGovernance(
            datasetId, columnId, request.displayName().strip(), trimToNull(request.description()),
            request.sensitive(), request.status(), LocalDateTime.now()
        ) != 1) {
            throw notFound("数据列不存在");
        }
    }

    AgentDataSource requireSource(Long sourceId) {
        AgentDataSource source = mapper.selectSource(sourceId);
        if (source == null) {
            throw notFound("数据源不存在");
        }
        return source;
    }

    AgentDataDataset requireDataset(Long datasetId) {
        AgentDataDataset dataset = mapper.selectDataset(datasetId);
        if (dataset == null) {
            throw notFound("数据集不存在");
        }
        return dataset;
    }

    PermissionContext datasetContext(AgentDataDataset dataset, String action) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        return datasetContext(principal, dataset, action);
    }

    private PermissionContext datasetContext(
        CurrentPrincipal principal,
        AgentDataDataset dataset,
        String action
    ) {
        Set<BusinessRelation> relations = principal.id().equals(dataset.getOwnerId())
            ? Set.of(BusinessRelation.OWNER) : Set.of();
        return new PermissionContext(
            "dataset", dataset.getId(), dataset.getDatasetKey(), action,
            state(dataset.getStatus()), true, relations, null
        );
    }

    private void require(
        CurrentPrincipal principal,
        String resourceType,
        Long resourceId,
        String action,
        ResourceState state,
        Set<BusinessRelation> relations
    ) {
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            resourceType, resourceId, null, action, state, true, relations, null
        ));
    }

    private ResourceState state(String status) {
        return ResourceState.ACTIVE;
    }

    private int seconds(int milliseconds) {
        return Math.max(1, (milliseconds + 999) / 1000);
    }

    private String connectionFailureMessage(Exception exception) {
        if (exception instanceof ServiceException serviceException
            && serviceException.getMessage() != null && !serviceException.getMessage().isBlank()) {
            return "连接失败：" + serviceException.getMessage();
        }
        return "连接失败，请检查 Endpoint、网络、驱动和凭证配置";
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private DatasetDeleteImpactView impactView(Long datasetId, DatasetDeleteImpactRow impact) {
        if (impact == null) {
            throw notFound("数据集不存在");
        }
        List<CategoryView> categories = List.of(
            new CategoryView("active_task_bindings", count(impact.getActiveTaskBindings())),
            new CategoryView("active_reports", count(impact.getActiveReports())),
            new CategoryView("running_data_queries", count(impact.getRunningDataQueries())),
            new CategoryView("running_profile_jobs", count(impact.getRunningProfileJobs())),
            new CategoryView("draft_smart_imports", count(impact.getDraftSmartImports())),
            new CategoryView("draft_catalog_imports", count(impact.getDraftCatalogImports())),
            new CategoryView("running_metadata_syncs", count(impact.getRunningMetadataSyncs())),
            new CategoryView("active_agent_dataset_bindings", count(impact.getActiveAgentDatasetBindings())),
            new CategoryView(
                "active_permission_profile_references", count(impact.getActivePermissionProfileReferences())
            ),
            new CategoryView(
                "active_permission_override_references", count(impact.getActivePermissionOverrideReferences())
            ),
            new CategoryView(
                "active_temporary_grant_references", count(impact.getActiveTemporaryGrantReferences())
            ),
            new CategoryView(
                "active_permission_snapshot_references", count(impact.getActivePermissionSnapshotReferences())
            )
        );
        long blockingTotal = categories.stream().mapToLong(CategoryView::count).sum();
        return new DatasetDeleteImpactView(datasetId, categories, blockingTotal, blockingTotal == 0);
    }

    private long count(Long value) {
        return value == null ? 0 : value;
    }

    private ServiceException notFound(String message) {
        return new ServiceException(message, HttpStatus.NOT_FOUND);
    }

    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }
}
