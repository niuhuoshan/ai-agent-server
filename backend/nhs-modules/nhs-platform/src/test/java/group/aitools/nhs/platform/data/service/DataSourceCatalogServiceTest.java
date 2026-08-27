package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.persistence.row.DatasetDeleteImpactRow;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class DataSourceCatalogServiceTest {

    private final CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
    private final AuthorizationEnforcer authorizationEnforcer = mock(AuthorizationEnforcer.class);
    private final DataCatalogMapper mapper = mock(DataCatalogMapper.class);
    private DataSourceCatalogService service;
    private AgentDataDataset dataset;

    @BeforeEach
    void setUp() {
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            7L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));
        dataset = dataset();
        service = new DataSourceCatalogService(
            principalProvider,
            authorizationEnforcer,
            mock(PlatformIdGenerator.class),
            mapper,
            mock(DataSourceConfigurationValidator.class),
            mock(DataSourceEndpointPolicy.class),
            mock(ReadOnlyJdbcConnectionFactory.class),
            JsonMapper.builder().build()
        );
    }

    @Test
    void returnsOnlyCategorizedCountsForDeleteImpact() {
        when(mapper.selectDataset(1L)).thenReturn(dataset);
        when(mapper.selectDatasetDeleteImpact(1L)).thenReturn(impact(2, 1, 3, 4, 5, 6, 1, 2, 3, 4, 5, 6));

        var result = service.datasetDeleteImpact(1L);

        assertEquals(40, result.blockingTotal());
        assertFalse(result.deletable());
        assertEquals(List.of(
            "active_task_bindings", "active_reports", "running_data_queries",
            "running_profile_jobs", "draft_smart_imports", "draft_catalog_imports",
            "running_metadata_syncs", "active_agent_dataset_bindings",
            "active_permission_profile_references", "active_permission_override_references",
            "active_temporary_grant_references", "active_permission_snapshot_references"
        ), result.categories().stream().map(category -> category.category()).toList());
        assertEquals(List.of(2L, 1L, 3L, 4L, 5L, 6L, 1L, 2L, 3L, 4L, 5L, 6L),
            result.categories().stream().map(category -> category.count()).toList());
        ArgumentCaptor<PermissionContext> context = ArgumentCaptor.forClass(PermissionContext.class);
        verify(authorizationEnforcer).requireAllowed(any(), context.capture());
        assertEquals("dataset", context.getValue().resourceType());
        assertEquals("delete", context.getValue().action());
    }

    @Test
    void deleteImpactDoesNotReadReferenceCountsBeforeAuthorization() {
        when(mapper.selectDataset(1L)).thenReturn(dataset);
        doThrow(new ServiceException("无权删除数据集", 403))
            .when(authorizationEnforcer).requireAllowed(any(), any());

        ServiceException error = assertThrows(
            ServiceException.class, () -> service.datasetDeleteImpact(1L)
        );

        assertEquals(403, error.getCode());
        verify(mapper, never()).selectDatasetDeleteImpact(anyLong());
    }

    @Test
    void deleteLocksAndRechecksImpactBeforeRejectingBlockingReferences() {
        when(mapper.selectDataset(1L)).thenReturn(dataset);
        when(mapper.lockDatasetForDelete(1L)).thenReturn(dataset);
        when(mapper.selectDatasetDeleteImpact(1L)).thenReturn(impact(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));

        ServiceException error = assertThrows(ServiceException.class, () -> service.deleteDataset(1L));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("仍被活动任务、Agent 或权限规则引用"));
        InOrder order = inOrder(mapper);
        order.verify(mapper).selectDataset(1L);
        order.verify(mapper).lockDatasetForDelete(1L);
        order.verify(mapper).selectDatasetDeleteImpact(1L);
        verify(mapper, never()).softDeleteDataset(anyLong(), anyLong(), any());
    }

    @Test
    void deleteRejectsFormalAgentAndPermissionReferencesEvenWithoutTaskFacts() {
        when(mapper.selectDataset(1L)).thenReturn(dataset);
        when(mapper.lockDatasetForDelete(1L)).thenReturn(dataset);
        when(mapper.selectDatasetDeleteImpact(1L)).thenReturn(
            impact(0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1)
        );

        ServiceException error = assertThrows(ServiceException.class, () -> service.deleteDataset(1L));

        assertEquals(409, error.getCode());
        verify(mapper, never()).softDeleteDataset(anyLong(), anyLong(), any());
    }

    @Test
    void deleteProceedsWhenOnlyHistoricalTerminalFactsRemain() {
        when(mapper.selectDataset(1L)).thenReturn(dataset);
        when(mapper.lockDatasetForDelete(1L)).thenReturn(dataset);
        when(mapper.selectDatasetDeleteImpact(1L)).thenReturn(impact(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        when(mapper.softDeleteDataset(anyLong(), anyLong(), any())).thenReturn(1);

        service.deleteDataset(1L);

        verify(mapper).softDeleteDataset(anyLong(), anyLong(), any());
    }

    private AgentDataDataset dataset() {
        AgentDataDataset value = new AgentDataDataset();
        value.setId(1L);
        value.setDataSourceId(2L);
        value.setDatasetKey("sales");
        value.setName("销售数据");
        value.setStatus("active");
        value.setRevisionNo(3);
        value.setOwnerId(7L);
        value.setDelFlag("0");
        return value;
    }

    private DatasetDeleteImpactRow impact(
        long tasks,
        long reports,
        long queries,
        long profiles,
        long smartImports,
        long catalogImports,
        long metadataSyncs,
        long agentBindings,
        long profileReferences,
        long overrideReferences,
        long temporaryGrants,
        long snapshotReferences
    ) {
        DatasetDeleteImpactRow value = new DatasetDeleteImpactRow();
        value.setActiveTaskBindings(tasks);
        value.setActiveReports(reports);
        value.setRunningDataQueries(queries);
        value.setRunningProfileJobs(profiles);
        value.setDraftSmartImports(smartImports);
        value.setDraftCatalogImports(catalogImports);
        value.setRunningMetadataSyncs(metadataSyncs);
        value.setActiveAgentDatasetBindings(agentBindings);
        value.setActivePermissionProfileReferences(profileReferences);
        value.setActivePermissionOverrideReferences(overrideReferences);
        value.setActiveTemporaryGrantReferences(temporaryGrants);
        value.setActivePermissionSnapshotReferences(snapshotReferences);
        return value;
    }
}
