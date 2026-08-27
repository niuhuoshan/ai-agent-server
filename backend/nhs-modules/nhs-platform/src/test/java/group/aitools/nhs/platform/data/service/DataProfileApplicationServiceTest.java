package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataProfileJob;
import group.aitools.nhs.platform.data.domain.AgentDataProfileJobTable;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.domain.AgentDataTableProfile;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.mapper.DataGovernanceMapper;
import group.aitools.nhs.platform.data.mapper.DataProfileMapper;
import group.aitools.nhs.platform.data.persistence.row.MetadataChangeRow;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.CreateProfileJobRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class DataProfileApplicationServiceTest {

    private final CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
    private final AuthorizationEnforcer authorizationEnforcer = mock(AuthorizationEnforcer.class);
    private final PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
    private final DataSourceCatalogService catalogService = mock(DataSourceCatalogService.class);
    private final DataCatalogMapper catalogMapper = mock(DataCatalogMapper.class);
    private final DataProfileMapper mapper = mock(DataProfileMapper.class);
    private final DataGovernanceMapper governanceMapper = mock(DataGovernanceMapper.class);
    private DataProfileApplicationService service;
    private AgentDataTable table;
    private AgentDataColumn column;

    @BeforeEach
    void setUp() {
        AtomicLong ids = new AtomicLong(100);
        when(idGenerator.nextId()).thenAnswer(ignored -> ids.incrementAndGet());
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            7L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));
        when(catalogService.requireDataset(1L)).thenReturn(dataset());
        when(catalogService.requireSource(2L)).thenReturn(source());
        when(catalogService.datasetContext(any(), eq("sync")))
            .thenReturn(PermissionContext.active("dataset", 1L, "sync"));
        table = table();
        column = column();
        when(catalogMapper.selectTables(1L)).thenReturn(List.of(table));
        when(catalogMapper.selectColumns(1L)).thenReturn(List.of(column));
        when(mapper.countActiveJobs(1L)).thenReturn(0);
        when(mapper.insertJob(any())).thenReturn(1);
        when(mapper.insertJobTable(any())).thenReturn(1);
        when(governanceMapper.insertChange(any(MetadataChangeRow.class))).thenReturn(1);
        service = new DataProfileApplicationService(
            principalProvider, authorizationEnforcer, idGenerator, catalogService,
            catalogMapper, mapper, governanceMapper, JsonMapper.builder().build()
        );
    }

    @Test
    void createsDurableFullJobAndOnePendingTableFact() {
        when(mapper.selectLatestProfiles(1L)).thenReturn(List.of());

        var result = service.createJob(1L, new CreateProfileJobRequest("full", List.of()));

        assertEquals("queued", result.status());
        assertEquals(1, result.totalTables());
        ArgumentCaptor<AgentDataProfileJob> job = ArgumentCaptor.forClass(AgentDataProfileJob.class);
        verify(mapper).insertJob(job.capture());
        assertEquals(3, job.getValue().getDatasetRevision());
        ArgumentCaptor<AgentDataProfileJobTable> item = ArgumentCaptor.forClass(AgentDataProfileJobTable.class);
        verify(mapper).insertJobTable(item.capture());
        assertEquals("pending", item.getValue().getStatus());
        assertEquals(64, item.getValue().getSourceHash().length());
    }

    @Test
    void incrementalJobCompletesImmediatelyWhenStructureHashIsUnchanged() {
        AgentDataTableProfile profile = new AgentDataTableProfile();
        profile.setTableId(table.getId());
        profile.setSourceHash(DataTableProfiler.structureHash(table, List.of(column)));
        when(mapper.selectLatestProfiles(1L)).thenReturn(List.of(profile));

        var result = service.createJob(1L, new CreateProfileJobRequest("incremental", List.of()));

        assertEquals("done", result.status());
        assertEquals(0, result.totalTables());
        assertEquals(100, result.progressPercent().intValue());
        verify(mapper, never()).insertJobTable(any());
    }

    @Test
    void mapsInternalTerminalStatesToNhsContractVocabulary() {
        assertEquals("done", DataProfileApplicationService.externalJobStatus("succeeded"));
        assertEquals("error", DataProfileApplicationService.externalJobStatus("failed"));
        assertEquals("success", DataProfileApplicationService.externalTableStatus("succeeded"));
    }

    private AgentDataDataset dataset() {
        AgentDataDataset dataset = new AgentDataDataset();
        dataset.setId(1L);
        dataset.setDataSourceId(2L);
        dataset.setDatasetKey("sales");
        dataset.setStatus("active");
        dataset.setRevisionNo(3);
        dataset.setOwnerId(7L);
        return dataset;
    }

    private AgentDataSource source() {
        AgentDataSource source = new AgentDataSource();
        source.setId(2L);
        source.setStatus("active");
        source.setRevisionNo(4);
        return source;
    }

    private AgentDataTable table() {
        AgentDataTable value = new AgentDataTable();
        value.setId(10L);
        value.setDatasetId(1L);
        value.setPhysicalSchema("public");
        value.setPhysicalName("orders");
        value.setTableType("TABLE");
        value.setStatus("active");
        value.setMetadataPresent(true);
        value.setDelFlag("0");
        return value;
    }

    private AgentDataColumn column() {
        AgentDataColumn value = new AgentDataColumn();
        value.setId(11L);
        value.setTableId(10L);
        value.setPhysicalName("id");
        value.setDataType("bigint");
        value.setIsPrimary(true);
        value.setIsSensitive(false);
        value.setStatus("active");
        value.setMetadataPresent(true);
        return value;
    }
}
