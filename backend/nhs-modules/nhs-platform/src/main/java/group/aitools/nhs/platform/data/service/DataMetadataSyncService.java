package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.service.DataMetadataPersistenceService.DiscoveredTable;
import group.aitools.nhs.platform.data.web.MetadataSyncView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

/** Synchronizes governed metadata through the JDBC standard metadata API. */
@Service
public class DataMetadataSyncService {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };
    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final DataSourceCatalogService catalogService;
    private final DataCatalogMapper mapper;
    private final ReadOnlyJdbcConnectionFactory connectionFactory;
    private final JdbcMetadataDiscovery metadataDiscovery;
    private final DataMetadataPersistenceService persistenceService;
    private final JsonMapper jsonMapper;

    public DataMetadataSyncService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        DataSourceCatalogService catalogService,
        DataCatalogMapper mapper,
        ReadOnlyJdbcConnectionFactory connectionFactory,
        JdbcMetadataDiscovery metadataDiscovery,
        DataMetadataPersistenceService persistenceService,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.catalogService = catalogService;
        this.mapper = mapper;
        this.connectionFactory = connectionFactory;
        this.metadataDiscovery = metadataDiscovery;
        this.persistenceService = persistenceService;
        this.jsonMapper = jsonMapper;
    }

    public MetadataSyncView synchronize(Long datasetId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataDataset dataset = catalogService.requireDataset(datasetId);
        authorizationEnforcer.requireAllowed(principal, catalogService.datasetContext(dataset, "sync"));
        AgentDataSource source = catalogService.requireSource(dataset.getDataSourceId());
        if (!"active".equals(source.getStatus())) {
            throw conflict("只有活动数据源可以同步元数据");
        }
        if (!"active".equals(dataset.getStatus()) && !"error".equals(dataset.getStatus())) {
            throw conflict("当前数据集状态不能同步元数据");
        }

        LocalDateTime startedAt = LocalDateTime.now();
        if (mapper.markDatasetSyncing(
            datasetId, dataset.getRevisionNo(), principal.id(), startedAt
        ) != 1) {
            throw conflict("数据集正在同步或状态已变化");
        }
        try {
            List<String> schemas = jsonMapper.readValue(dataset.getSchemaNamesJson(), LIST_TYPE);
            List<DiscoveredTable> tables;
            try (Connection connection = connectionFactory.open(source)) {
                tables = metadataDiscovery.discover(connection, source, schemas);
                connectionFactory.rollback(connection, source);
            }
            int columnCount = tables.stream().mapToInt(table -> table.columns().size()).sum();
            LocalDateTime completedAt = LocalDateTime.now();
            persistenceService.applyCurrentSnapshot(
                datasetId, dataset.getRevisionNo(), source.getId(), source.getRevisionNo(),
                tables, principal.id(), completedAt
            );
            return new MetadataSyncView(datasetId, tables.size(), columnCount, completedAt);
        } catch (Exception exception) {
            LocalDateTime failedAt = LocalDateTime.now();
            mapper.finishDatasetSync(
                datasetId, dataset.getRevisionNo(), "error", "元数据同步失败",
                principal.id(), failedAt
            );
            mapper.recordSourceMetadataSync(
                source.getId(), source.getRevisionNo(), "元数据同步失败",
                principal.id(), failedAt
            );
            if (exception instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new ServiceException("元数据同步失败，请检查连接、权限和 Schema 配置", 502);
        }
    }

    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

}
