package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class DataMetadataSyncServiceTest {

    private static final CurrentPrincipal PRINCIPAL = new CurrentPrincipal(
        9L, "metadata-admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );

    private DataCatalogMapper mapper;
    private DataMetadataPersistenceService persistenceService;
    private DataMetadataSyncService service;

    @BeforeEach
    void setUp() throws Exception {
        CurrentPrincipalProvider principalProvider = () -> PRINCIPAL;
        AuthorizationEnforcer authorizationEnforcer = mock(AuthorizationEnforcer.class);
        DataSourceCatalogService catalogService = mock(DataSourceCatalogService.class);
        mapper = mock(DataCatalogMapper.class);
        ReadOnlyJdbcConnectionFactory connectionFactory = mock(ReadOnlyJdbcConnectionFactory.class);
        JdbcMetadataDiscovery metadataDiscovery = mock(JdbcMetadataDiscovery.class);
        persistenceService = mock(DataMetadataPersistenceService.class);

        AgentDataDataset dataset = dataset();
        AgentDataSource source = source();
        when(catalogService.requireDataset(dataset.getId())).thenReturn(dataset);
        when(catalogService.requireSource(source.getId())).thenReturn(source);
        when(mapper.markDatasetSyncing(
            eq(dataset.getId()), eq(dataset.getRevisionNo()), eq(PRINCIPAL.id()), any()
        ))
            .thenReturn(1);
        Connection connection = mock(Connection.class);
        when(connectionFactory.open(source)).thenReturn(connection);
        when(metadataDiscovery.discover(connection, source, List.of("public"))).thenReturn(List.of());

        service = new DataMetadataSyncService(
            principalProvider, authorizationEnforcer, catalogService, mapper,
            connectionFactory, metadataDiscovery, persistenceService, JsonMapper.builder().build()
        );
    }

    @Test
    void carriesFrozenRevisionsFromSyncStartIntoTheAtomicSnapshotCommit() {
        service.synchronize(800L);

        verify(mapper).markDatasetSyncing(eq(800L), eq(3), eq(9L), any());
        verify(persistenceService).applyCurrentSnapshot(
            eq(800L), eq(3), eq(700L), eq(7), eq(List.of()), eq(9L), any()
        );
    }

    @Test
    void staleCommitFailureOnlyAttemptsRevisionGuardedErrorUpdates() {
        doThrow(new ServiceException("数据集配置已变化，本次元数据同步结果已丢弃", 409))
            .when(persistenceService).applyCurrentSnapshot(
                eq(800L), eq(3), eq(700L), eq(7), eq(List.of()), eq(9L), any()
            );
        when(mapper.finishDatasetSync(eq(800L), eq(3), eq("error"), any(), eq(9L), any()))
            .thenReturn(0);
        when(mapper.recordSourceMetadataSync(eq(700L), eq(7), any(), eq(9L), any()))
            .thenReturn(0);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.synchronize(800L));

        assertEquals(409, exception.getCode());
        verify(mapper).finishDatasetSync(eq(800L), eq(3), eq("error"), any(), eq(9L), any());
        verify(mapper).recordSourceMetadataSync(eq(700L), eq(7), any(), eq(9L), any());
    }

    private AgentDataDataset dataset() {
        AgentDataDataset value = new AgentDataDataset();
        value.setId(800L);
        value.setDataSourceId(700L);
        value.setStatus("active");
        value.setRevisionNo(3);
        value.setSchemaNamesJson("[\"public\"]");
        return value;
    }

    private AgentDataSource source() {
        AgentDataSource value = new AgentDataSource();
        value.setId(700L);
        value.setStatus("active");
        value.setRevisionNo(7);
        return value;
    }
}
