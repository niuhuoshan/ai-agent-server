package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataCatalogImportItem;
import group.aitools.nhs.platform.data.domain.AgentDataCatalogImportPreview;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.mapper.DataCatalogImportMapper;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.mapper.DataGovernanceMapper;
import group.aitools.nhs.platform.data.persistence.row.MetadataChangeRow;
import group.aitools.nhs.platform.data.web.MetadataCatalogImportContracts.CreateMetadataImportPreviewRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class DataCatalogImportServiceTest {

    @Test
    void ddlPreviewPersistsOnlyHashAndValidatedProposal() {
        CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
        AuthorizationEnforcer authorizationEnforcer = mock(AuthorizationEnforcer.class);
        PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        DataSourceCatalogService catalogService = mock(DataSourceCatalogService.class);
        DataCatalogMapper catalogMapper = mock(DataCatalogMapper.class);
        DataCatalogImportMapper mapper = mock(DataCatalogImportMapper.class);
        DataGovernanceMapper governanceMapper = mock(DataGovernanceMapper.class);
        JsonMapper jsonMapper = JsonMapper.builder().build();
        MetadataYamlCodec codec = new MetadataYamlCodec();
        AtomicLong ids = new AtomicLong(100);
        when(idGenerator.nextId()).thenAnswer(ignored -> ids.incrementAndGet());
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            7L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));
        AgentDataDataset dataset = new AgentDataDataset();
        dataset.setId(1L);
        dataset.setDatasetKey("sales");
        dataset.setName("销售数据");
        dataset.setSchemaNamesJson("[\"public\"]");
        dataset.setRevisionNo(3);
        when(catalogService.requireDataset(1L)).thenReturn(dataset);
        when(catalogService.datasetContext(any(), eq("update")))
            .thenReturn(PermissionContext.active("dataset", 1L, "update"));
        when(catalogMapper.selectTables(1L)).thenReturn(List.of());
        when(catalogMapper.selectColumns(1L)).thenReturn(List.of());
        when(governanceMapper.selectLatestMetrics(1L)).thenReturn(List.of());
        when(governanceMapper.selectRelationships(1L)).thenReturn(List.of());
        when(mapper.insertPreview(any())).thenReturn(1);
        List<AgentDataCatalogImportItem> inserted = new ArrayList<>();
        doAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            return 1;
        }).when(mapper).insertItem(any());
        when(mapper.selectItems(any())).thenAnswer(ignored -> List.copyOf(inserted));
        when(governanceMapper.insertChange(any(MetadataChangeRow.class))).thenReturn(1);
        DataCatalogImportService service = new DataCatalogImportService(
            principalProvider, authorizationEnforcer, idGenerator, catalogService, catalogMapper,
            mapper, governanceMapper, new MetadataDdlParser(codec), codec, jsonMapper
        );
        String ddl = "CREATE TABLE public.orders (id BIGINT PRIMARY KEY)";

        var result = service.createPreview(
            1L, new CreateMetadataImportPreviewRequest("ddl", ddl)
        );

        assertEquals(1, result.items().size());
        assertEquals("table", result.items().getFirst().itemType());
        assertEquals("public.orders", result.items().getFirst().resourceKey());
        assertTrue(result.diagnostics().stream()
            .anyMatch(diagnostic -> "declared_table".equals(diagnostic.code())));
        ArgumentCaptor<AgentDataCatalogImportPreview> preview =
            ArgumentCaptor.forClass(AgentDataCatalogImportPreview.class);
        verify(mapper).insertPreview(preview.capture());
        assertNotEquals(ddl, preview.getValue().getSourceHash());
        assertFalse(inserted.getFirst().getProposedJson().contains("CREATE TABLE"));
    }

    @Test
    void rejectsTablesOutsideTheDatasetSchemaAllowlistBeforePersistence() {
        CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
        AuthorizationEnforcer authorizationEnforcer = mock(AuthorizationEnforcer.class);
        PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        DataSourceCatalogService catalogService = mock(DataSourceCatalogService.class);
        DataCatalogMapper catalogMapper = mock(DataCatalogMapper.class);
        DataCatalogImportMapper mapper = mock(DataCatalogImportMapper.class);
        DataGovernanceMapper governanceMapper = mock(DataGovernanceMapper.class);
        JsonMapper jsonMapper = JsonMapper.builder().build();
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            7L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));
        AgentDataDataset dataset = new AgentDataDataset();
        dataset.setId(1L);
        dataset.setDatasetKey("sales");
        dataset.setName("销售数据");
        dataset.setSchemaNamesJson("[\"public\"]");
        dataset.setRevisionNo(3);
        when(catalogService.requireDataset(1L)).thenReturn(dataset);
        when(catalogService.datasetContext(any(), eq("update")))
            .thenReturn(PermissionContext.active("dataset", 1L, "update"));
        DataCatalogImportService service = new DataCatalogImportService(
            principalProvider, authorizationEnforcer, idGenerator, catalogService, catalogMapper,
            mapper, governanceMapper, new MetadataDdlParser(new MetadataYamlCodec()),
            new MetadataYamlCodec(), jsonMapper
        );

        ServiceException error = assertThrows(ServiceException.class, () -> service.createPreview(
            1L, new CreateMetadataImportPreviewRequest(
                "ddl", "CREATE TABLE private.orders (id BIGINT PRIMARY KEY)"
            )
        ));

        assertEquals(400, error.getCode());
        verify(mapper, never()).insertPreview(any());
    }
}
