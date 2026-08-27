package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataProfileJob;
import group.aitools.nhs.platform.data.domain.AgentDataProfileRelationRecommendation;
import group.aitools.nhs.platform.data.domain.AgentDataSmartImportItem;
import group.aitools.nhs.platform.data.domain.AgentDataSmartImportPreview;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.domain.AgentDataTableProfile;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.mapper.DataGovernanceMapper;
import group.aitools.nhs.platform.data.mapper.DataProfileMapper;
import group.aitools.nhs.platform.data.persistence.row.MetadataChangeRow;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.CreateRelationshipRequest;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.RelationshipView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ApplySmartImportRequest;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.ColumnProfileView;
import group.aitools.nhs.platform.data.web.MetadataProfileContracts.CreateSmartImportPreviewRequest;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class DataSmartImportServiceTest {

    @Test
    void previewCarriesModelTermsDescriptionsAndProfileRevisionIntoTypedProposal() {
        CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
        AuthorizationEnforcer authorizationEnforcer = mock(AuthorizationEnforcer.class);
        PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        DataSourceCatalogService catalogService = mock(DataSourceCatalogService.class);
        DataCatalogMapper catalogMapper = mock(DataCatalogMapper.class);
        DataProfileMapper mapper = mock(DataProfileMapper.class);
        DataGovernanceMapper governanceMapper = mock(DataGovernanceMapper.class);
        DataGovernanceService governanceService = mock(DataGovernanceService.class);
        JsonMapper jsonMapper = JsonMapper.builder().build();
        AtomicLong ids = new AtomicLong(100);
        when(idGenerator.nextId()).thenAnswer(ignored -> ids.incrementAndGet());
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            7L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));
        AgentDataDataset dataset = dataset();
        when(catalogService.requireDataset(1L)).thenReturn(dataset);
        when(catalogService.requireSource(2L)).thenReturn(source());
        when(catalogService.datasetContext(any(), eq("update")))
            .thenReturn(PermissionContext.active("dataset", 1L, "update"));
        AgentDataProfileJob job = job();
        when(mapper.selectLatestSucceededJob(1L)).thenReturn(job);
        AgentDataTable table = table();
        AgentDataColumn column = column();
        AgentDataTableProfile profile = profile(jsonMapper, column);
        when(mapper.selectJobProfiles(1L, 20L)).thenReturn(List.of(profile));
        when(catalogMapper.selectTables(1L)).thenReturn(List.of(table));
        when(catalogMapper.selectColumns(1L)).thenReturn(List.of(column));
        when(mapper.insertPreview(any())).thenReturn(1);
        List<AgentDataSmartImportItem> inserted = new ArrayList<>();
        doAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            return 1;
        }).when(mapper).insertPreviewItem(any());
        when(mapper.selectPreviewItems(anyLong())).thenAnswer(ignored -> List.copyOf(inserted));
        when(mapper.selectJobRecommendations(1L, 20L)).thenReturn(List.of());
        when(governanceMapper.insertChange(any(MetadataChangeRow.class))).thenReturn(1);
        DataSmartImportService service = new DataSmartImportService(
            principalProvider, authorizationEnforcer, idGenerator, catalogService,
            catalogMapper, mapper, governanceMapper, governanceService, jsonMapper
        );

        var result = service.createPreview(
            1L, new CreateSmartImportPreviewRequest(null, List.of(10L))
        );

        assertEquals(1, result.items().size());
        var proposal = result.items().get(0).tableProposal();
        assertNotNull(proposal);
        assertEquals("订单明细", proposal.displayName());
        assertEquals("记录客户订单", proposal.description());
        assertEquals(3, proposal.profileRevision());
        assertEquals(profile.getSourceHash(), proposal.sourceHash());
        assertNotNull(proposal.expected());
        assertEquals("订单ID", proposal.columnUpdates().get(0).displayName());
        assertEquals("订单唯一标识", proposal.columnUpdates().get(0).description());
        assertNotNull(proposal.columnUpdates().get(0).expected());
    }

    @Test
    void changedTableGovernanceSnapshotRejectsApplyBeforeAnyUpdate() {
        TestContext context = new TestContext();
        AgentDataSmartImportItem item = context.createTablePreview();
        context.prepareApply();
        AgentDataTable changed = table();
        changed.setDisplayName("人工修改后的订单表");
        when(context.mapper.selectTableForSmartImport(1L, 10L)).thenReturn(changed);
        clearInvocations(context.catalogMapper);

        ServiceException error = assertThrows(ServiceException.class, () -> context.apply(item));

        assertEquals(409, error.getCode());
        verify(context.catalogMapper, never()).updateTableGovernance(
            anyLong(), anyLong(), any(), any(), any(), anyLong(), any()
        );
    }

    @Test
    void changedColumnGovernanceSnapshotRejectsApplyBeforeAnyUpdate() {
        TestContext context = new TestContext();
        AgentDataSmartImportItem item = context.createTablePreview();
        context.prepareApply();
        AgentDataColumn changed = column();
        changed.setDisplayName("人工修改后的订单编号");
        when(context.mapper.selectColumnsForSmartImport(1L, 10L)).thenReturn(List.of(changed));
        clearInvocations(context.catalogMapper);

        ServiceException error = assertThrows(ServiceException.class, () -> context.apply(item));

        assertEquals(409, error.getCode());
        verify(context.catalogMapper, never()).updateTableGovernance(
            anyLong(), anyLong(), any(), any(), any(), anyLong(), any()
        );
        verify(context.catalogMapper, never()).updateColumnGovernance(
            anyLong(), anyLong(), any(), any(), anyBoolean(), any(), any()
        );
    }

    @Test
    void successfulTableApplyAuditsTableAndColumnBeforeAndAfterStates() {
        TestContext context = new TestContext();
        AgentDataSmartImportItem item = context.createTablePreview();
        context.prepareApply();
        AgentDataTable updatedTable = table();
        updatedTable.setDisplayName("订单明细");
        updatedTable.setDescription("记录客户订单");
        AgentDataColumn updatedColumn = column();
        updatedColumn.setDisplayName("订单ID");
        updatedColumn.setDescription("订单唯一标识");
        when(context.catalogMapper.updateTableGovernance(
            eq(1L), eq(10L), eq("订单明细"), eq("记录客户订单"), eq("active"), eq(7L), any()
        )).thenReturn(1);
        when(context.catalogMapper.selectTable(10L)).thenReturn(updatedTable);
        when(context.catalogMapper.updateColumnGovernance(
            eq(1L), eq(11L), eq("订单ID"), eq("订单唯一标识"), eq(false), eq("active"), any()
        )).thenReturn(1);
        when(context.catalogMapper.selectColumn(11L)).thenReturn(updatedColumn);
        clearInvocations(context.governanceMapper);

        context.apply(item);

        ArgumentCaptor<MetadataChangeRow> changes = ArgumentCaptor.forClass(MetadataChangeRow.class);
        verify(context.governanceMapper, times(3)).insertChange(changes.capture());
        MetadataChangeRow tableChange = changes.getAllValues().stream()
            .filter(change -> "table".equals(change.getResourceType())).findFirst().orElseThrow();
        MetadataChangeRow columnChange = changes.getAllValues().stream()
            .filter(change -> "column".equals(change.getResourceType())).findFirst().orElseThrow();
        assertNotNull(tableChange.getBeforeJson());
        assertNotNull(tableChange.getAfterJson());
        assertNotNull(columnChange.getBeforeJson());
        assertNotNull(columnChange.getAfterJson());
    }

    @Test
    void inactiveSourceStillAllowsHistoricalPreviewButRejectsCreateAndApply() {
        TestContext context = new TestContext();
        context.source.setStatus("inactive");
        AgentDataSmartImportPreview historical = context.historicalPreview();
        when(context.mapper.selectPreview(1L, historical.getId())).thenReturn(historical);
        when(context.mapper.selectPreviewItems(historical.getId())).thenReturn(List.of());

        var result = context.service.preview(1L, historical.getId());

        assertEquals(historical.getId(), result.id());
        verify(context.catalogService, never()).requireSource(anyLong());
        assertEquals(409, assertThrows(ServiceException.class, () -> context.service.createPreview(
            1L, new CreateSmartImportPreviewRequest(null, List.of(10L))
        )).getCode());
        assertEquals(409, assertThrows(ServiceException.class, () -> context.service.apply(
            1L, historical.getId(), new ApplySmartImportRequest(1, List.of(101L))
        )).getCode());
        verify(context.mapper, never()).selectPreviewForUpdate(anyLong(), anyLong());
    }

    @Test
    void relationshipApplyLocksActiveEndpointsAndDelegatesToGovernanceService() {
        TestContext context = new TestContext();
        AgentDataTable targetTable = table(20L, "customers");
        AgentDataColumn targetColumn = column(21L, 20L, "id");
        AgentDataTableProfile targetProfile = profile(
            context.jsonMapper, 31L, targetTable, targetColumn, "客户"
        );
        AgentDataProfileRelationRecommendation recommendation = recommendation();
        when(context.mapper.selectJobProfiles(1L, 20L)).thenReturn(List.of(context.profile, targetProfile));
        when(context.catalogMapper.selectTables(1L)).thenReturn(List.of(context.table, targetTable));
        when(context.catalogMapper.selectColumns(1L)).thenReturn(List.of(context.column, targetColumn));
        when(context.mapper.selectJobRecommendations(1L, 20L)).thenReturn(List.of(recommendation));
        context.service.createPreview(
            1L, new CreateSmartImportPreviewRequest(null, List.of(10L, 20L))
        );
        AgentDataSmartImportItem relationItem = context.items.stream()
            .filter(candidate -> "relationship".equals(candidate.getItemType()))
            .findFirst().orElseThrow();
        context.prepareApply();
        when(context.mapper.selectRecommendationForUpdate(1L, 40L)).thenReturn(recommendation);
        when(context.mapper.selectTableForSmartImport(1L, 10L)).thenReturn(context.table);
        when(context.mapper.selectColumnsForSmartImport(1L, 10L)).thenReturn(List.of(context.column));
        when(context.mapper.selectTableForSmartImport(1L, 20L)).thenReturn(targetTable);
        AgentDataColumn inactiveTargetColumn = column(21L, 20L, "id");
        inactiveTargetColumn.setStatus("inactive");
        when(context.mapper.selectColumnsForSmartImport(1L, 20L))
            .thenReturn(List.of(inactiveTargetColumn));
        when(context.mapper.selectLatestProfile(1L, 20L)).thenReturn(targetProfile);

        ServiceException inactiveEndpoint = assertThrows(
            ServiceException.class, () -> context.apply(relationItem)
        );

        assertEquals(409, inactiveEndpoint.getCode());
        verify(context.governanceService, never()).createRelationship(anyLong(), any());
        when(context.mapper.selectColumnsForSmartImport(1L, 20L)).thenReturn(List.of(targetColumn));
        when(context.governanceService.createRelationship(eq(1L), any())).thenReturn(
            new RelationshipView(
                70L, 1L, 10L, 20L, "public.orders", "public.customers",
                "inner", "orders.id = customers.id", "主键字段名称一致", "active",
                1, 7L, LocalDateTime.now(), null, null
            )
        );
        when(context.mapper.markRecommendationApplied(eq(1L), eq(40L), eq(70L), any()))
            .thenReturn(1);

        context.apply(relationItem);

        ArgumentCaptor<CreateRelationshipRequest> request = ArgumentCaptor.forClass(
            CreateRelationshipRequest.class
        );
        verify(context.governanceService).createRelationship(eq(1L), request.capture());
        assertEquals(10L, request.getValue().sourceTableId());
        assertEquals(20L, request.getValue().targetTableId());
        verify(context.mapper, atLeastOnce()).selectColumnsForSmartImport(1L, 10L);
        verify(context.mapper, atLeastOnce()).selectColumnsForSmartImport(1L, 20L);
    }

    private AgentDataDataset dataset() {
        AgentDataDataset value = new AgentDataDataset();
        value.setId(1L);
        value.setDataSourceId(2L);
        value.setDatasetKey("sales");
        value.setStatus("active");
        value.setRevisionNo(5);
        return value;
    }

    private AgentDataSource source() {
        AgentDataSource value = new AgentDataSource();
        value.setId(2L);
        value.setStatus("active");
        return value;
    }

    private AgentDataProfileJob job() {
        AgentDataProfileJob value = new AgentDataProfileJob();
        value.setId(20L);
        value.setDatasetId(1L);
        value.setStatus("succeeded");
        return value;
    }

    private AgentDataTable table() {
        return table(10L, "orders");
    }

    private AgentDataTable table(Long id, String physicalName) {
        AgentDataTable value = new AgentDataTable();
        value.setId(id);
        value.setDatasetId(1L);
        value.setPhysicalSchema("public");
        value.setPhysicalName(physicalName);
        value.setDisplayName(physicalName);
        value.setTableType("TABLE");
        value.setStatus("active");
        value.setMetadataPresent(true);
        return value;
    }

    private AgentDataColumn column() {
        return column(11L, 10L, "id");
    }

    private AgentDataColumn column(Long id, Long tableId, String physicalName) {
        AgentDataColumn value = new AgentDataColumn();
        value.setId(id);
        value.setTableId(tableId);
        value.setPhysicalName(physicalName);
        value.setDisplayName(physicalName);
        value.setDataType("bigint");
        value.setIsPrimary(true);
        value.setIsSensitive(false);
        value.setStatus("active");
        value.setMetadataPresent(true);
        return value;
    }

    private AgentDataTableProfile profile(JsonMapper jsonMapper, AgentDataColumn column) {
        return profile(jsonMapper, 30L, table(), column, "订单明细");
    }

    private AgentDataTableProfile profile(
        JsonMapper jsonMapper,
        Long profileId,
        AgentDataTable table,
        AgentDataColumn column,
        String term
    ) {
        AgentDataTableProfile value = new AgentDataTableProfile();
        value.setId(profileId);
        value.setDatasetId(1L);
        value.setTableId(table.getId());
        value.setJobId(20L);
        value.setTerm(term);
        value.setDescription("订单明细".equals(term) ? "记录客户订单" : "记录" + term);
        value.setIgnored(false);
        value.setRevisionNo(3);
        value.setConfidenceScore(BigDecimal.valueOf(92));
        value.setCreatedAt(LocalDateTime.now());
        value.setSourceHash(DataTableProfiler.structureHash(table, List.of(column)));
        value.setColumnsProfileJson(jsonMapper.writeValueAsString(List.of(
            new ColumnProfileView(
                column.getId(), column.getPhysicalName(), column.getDisplayName(),
                "订单ID", "订单唯一标识", column.getDataType(), true, false,
                0, 0, List.of()
            )
        )));
        return value;
    }

    private AgentDataProfileRelationRecommendation recommendation() {
        AgentDataProfileRelationRecommendation value = new AgentDataProfileRelationRecommendation();
        value.setId(40L);
        value.setDatasetId(1L);
        value.setProfileJobId(20L);
        value.setSourceTableId(10L);
        value.setSourceColumnId(11L);
        value.setTargetTableId(20L);
        value.setTargetColumnId(21L);
        value.setJoinType("inner");
        value.setJoinCondition("orders.id = customers.id");
        value.setReason("主键字段名称一致");
        value.setStatus("pending");
        return value;
    }

    private final class TestContext {

        private final CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
        private final AuthorizationEnforcer authorizationEnforcer = mock(AuthorizationEnforcer.class);
        private final PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        private final DataSourceCatalogService catalogService = mock(DataSourceCatalogService.class);
        private final DataCatalogMapper catalogMapper = mock(DataCatalogMapper.class);
        private final DataProfileMapper mapper = mock(DataProfileMapper.class);
        private final DataGovernanceMapper governanceMapper = mock(DataGovernanceMapper.class);
        private final DataGovernanceService governanceService = mock(DataGovernanceService.class);
        private final JsonMapper jsonMapper = JsonMapper.builder().build();
        private final AgentDataDataset dataset = dataset();
        private final AgentDataSource source = source();
        private final AgentDataTable table = table();
        private final AgentDataColumn column = column();
        private final AgentDataTableProfile profile = profile(jsonMapper, column);
        private final List<AgentDataSmartImportItem> items = new ArrayList<>();
        private final DataSmartImportService service;
        private AgentDataSmartImportPreview preview;

        private TestContext() {
            AtomicLong ids = new AtomicLong(100);
            when(idGenerator.nextId()).thenAnswer(ignored -> ids.incrementAndGet());
            when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
                7L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
            ));
            when(catalogService.requireDataset(1L)).thenReturn(dataset);
            when(catalogService.requireSource(2L)).thenReturn(source);
            when(catalogService.datasetContext(any(), anyString()))
                .thenReturn(PermissionContext.active("dataset", 1L, "update"));
            when(mapper.selectLatestSucceededJob(1L)).thenReturn(job());
            when(mapper.selectJobProfiles(1L, 20L)).thenReturn(List.of(profile));
            when(catalogMapper.selectTables(1L)).thenReturn(List.of(table));
            when(catalogMapper.selectColumns(1L)).thenReturn(List.of(column));
            doAnswer(invocation -> {
                preview = invocation.getArgument(0);
                return 1;
            }).when(mapper).insertPreview(any());
            doAnswer(invocation -> {
                items.add(invocation.getArgument(0));
                return 1;
            }).when(mapper).insertPreviewItem(any());
            when(mapper.selectPreviewItems(anyLong())).thenAnswer(ignored -> List.copyOf(items));
            when(mapper.selectJobRecommendations(1L, 20L)).thenReturn(List.of());
            when(governanceMapper.insertChange(any(MetadataChangeRow.class))).thenReturn(1);
            service = new DataSmartImportService(
                principalProvider, authorizationEnforcer, idGenerator, catalogService,
                catalogMapper, mapper, governanceMapper, governanceService, jsonMapper
            );
        }

        private AgentDataSmartImportItem createTablePreview() {
            service.createPreview(1L, new CreateSmartImportPreviewRequest(null, List.of(10L)));
            return items.get(0);
        }

        private void prepareApply() {
            when(mapper.selectPreviewForUpdate(1L, preview.getId())).thenReturn(preview);
            when(mapper.selectDatasetForUpdate(1L)).thenReturn(dataset);
            when(mapper.selectPreviewItemsForUpdate(preview.getId())).thenAnswer(
                ignored -> List.copyOf(items)
            );
            when(mapper.selectLatestProfile(1L, 10L)).thenReturn(profile);
            when(mapper.selectTableForSmartImport(1L, 10L)).thenReturn(table);
            when(mapper.selectColumnsForSmartImport(1L, 10L)).thenReturn(List.of(column));
            when(mapper.markPreviewItemApplied(eq(preview.getId()), anyLong(), anyLong(), any()))
                .thenReturn(1);
            when(mapper.advanceDatasetRevision(eq(1L), eq(5), eq(7L), any())).thenReturn(1);
            when(mapper.completePreview(
                eq(1L), eq(preview.getId()), eq(1), eq(7L), any()
            )).thenReturn(1);
            when(mapper.selectPreview(1L, preview.getId())).thenReturn(preview);
        }

        private void apply(AgentDataSmartImportItem item) {
            service.apply(
                1L, preview.getId(), new ApplySmartImportRequest(1, List.of(item.getId()))
            );
        }

        private AgentDataSmartImportPreview historicalPreview() {
            AgentDataSmartImportPreview value = new AgentDataSmartImportPreview();
            value.setId(90L);
            value.setDatasetId(1L);
            value.setProfileJobId(20L);
            value.setStatus("draft");
            value.setDatasetRevision(5);
            value.setRevisionNo(1);
            value.setExpiresAt(LocalDateTime.now().plusMinutes(10));
            value.setCreatedBy(7L);
            value.setCreatedAt(LocalDateTime.now());
            return value;
        }
    }
}
