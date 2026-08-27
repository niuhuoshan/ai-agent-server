package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataMetric;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.mapper.DataGovernanceMapper;
import group.aitools.nhs.platform.data.persistence.row.MetadataChangeRow;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.CreateMetricRequest;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.CreateRelationshipRequest;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.RowPolicyRule;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.UpdateRowPolicyRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class DataGovernanceServiceTest {

    private final CurrentPrincipal principal = new CurrentPrincipal(
        7L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private final CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
    private final AuthorizationEnforcer authorizationEnforcer = mock(AuthorizationEnforcer.class);
    private final PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
    private final DataSourceCatalogService catalogService = mock(DataSourceCatalogService.class);
    private final DataCatalogMapper catalogMapper = mock(DataCatalogMapper.class);
    private final DataGovernanceMapper mapper = mock(DataGovernanceMapper.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private DataGovernanceService service;

    @BeforeEach
    void setUp() {
        AtomicLong ids = new AtomicLong(100);
        when(idGenerator.nextId()).thenAnswer(ignored -> ids.incrementAndGet());
        when(principalProvider.currentPrincipal()).thenReturn(principal);
        when(catalogService.requireDataset(1L)).thenReturn(dataset());
        when(mapper.insertMetric(any())).thenReturn(1);
        when(mapper.insertRelationship(any())).thenReturn(1);
        when(mapper.insertChange(any())).thenReturn(1);
        when(mapper.updateRowPolicy(anyLong(), anyInt(), anyBoolean(), anyString(), anyLong(), any())).thenReturn(1);
        service = new DataGovernanceService(
            principalProvider, authorizationEnforcer, idGenerator, catalogService,
            catalogMapper, mapper, jsonMapper
        );
    }

    @Test
    void createsVersionedMetricAndDurableChangeSnapshot() {
        when(mapper.countMetricKey(1L, "gross_margin")).thenReturn(0);

        var result = service.createMetric(1L, new CreateMetricRequest(
            "gross_margin", "毛利率", "收入减成本后的占比", "(revenue-cost)/revenue", "%", "active"
        ));

        assertEquals("gross_margin", result.metricKey());
        assertEquals(1, result.versionNo());
        ArgumentCaptor<AgentDataMetric> metric = ArgumentCaptor.forClass(AgentDataMetric.class);
        verify(mapper).insertMetric(metric.capture());
        assertEquals(7L, metric.getValue().getCreatedBy());
        ArgumentCaptor<MetadataChangeRow> change = ArgumentCaptor.forClass(MetadataChangeRow.class);
        verify(mapper).insertChange(change.capture());
        assertEquals("create", change.getValue().getAction());
        assertEquals(64, change.getValue().getAfterHash().length());
        assertTrue(change.getValue().getAfterJson().contains("gross_margin"));
    }

    @Test
    void storesValidatedPrincipalBoundRowPolicyWithOptimisticRevision() {
        AgentDataTable table = table(10L, "orders");
        AgentDataColumn owner = column(11L, 10L, "owner_id", "bigint");
        when(catalogMapper.selectTables(1L)).thenReturn(List.of(table));
        when(catalogMapper.selectColumns(1L)).thenReturn(List.of(owner));

        var result = service.updateRowPolicy(1L, new UpdateRowPolicyRequest(
            3, true, List.of(new RowPolicyRule(10L, 11L, "eq", "principal_id"))
        ));

        assertTrue(result.enabled());
        assertEquals(4, result.revisionNo());
        ArgumentCaptor<String> policy = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateRowPolicy(eq(1L), eq(3), eq(true), policy.capture(), eq(7L), any());
        assertTrue(policy.getValue().contains("principal_id"));
        verify(mapper).insertChange(any(MetadataChangeRow.class));
    }

    @Test
    void rejectsRowPolicyWhenPrincipalSourceDoesNotMatchColumnType() {
        when(catalogMapper.selectTables(1L)).thenReturn(List.of(table(10L, "orders")));
        when(catalogMapper.selectColumns(1L)).thenReturn(List.of(column(11L, 10L, "owner_name", "varchar(64)")));

        assertThrows(ServiceException.class, () -> service.updateRowPolicy(
            1L,
            new UpdateRowPolicyRequest(
                3, true, List.of(new RowPolicyRule(10L, 11L, "eq", "principal_id"))
            )
        ));
    }

    @Test
    void createsOnlyParsedEquiRelationshipsBetweenDatasetColumns() {
        AgentDataTable orders = table(10L, "orders");
        AgentDataTable customers = table(20L, "customers");
        when(catalogMapper.selectTables(1L)).thenReturn(List.of(orders, customers));
        when(catalogMapper.selectColumns(1L)).thenReturn(List.of(
            column(11L, 10L, "customer_id", "bigint"),
            column(21L, 20L, "id", "bigint")
        ));
        when(mapper.countActiveRelationship(1L, 10L, 20L, null)).thenReturn(0);

        var result = service.createRelationship(1L, new CreateRelationshipRequest(
            10L, 20L, "inner", "orders.customer_id = customers.id", "客户归属", "active"
        ));

        assertEquals("public.orders", result.sourceTableName());
        assertEquals("public.customers", result.targetTableName());
        verify(mapper).insertRelationship(any());
        assertThrows(ServiceException.class, () -> service.createRelationship(
            1L,
            new CreateRelationshipRequest(
                10L, 20L, "inner", "orders.customer_id = 1", "非法", "active"
            )
        ));
    }

    private AgentDataDataset dataset() {
        AgentDataDataset dataset = new AgentDataDataset();
        dataset.setId(1L);
        dataset.setDatasetKey("sales");
        dataset.setStatus("active");
        dataset.setRevisionNo(3);
        dataset.setEnableRowPolicy(false);
        dataset.setRowPolicyJson("{}");
        return dataset;
    }

    private AgentDataTable table(Long id, String name) {
        AgentDataTable table = new AgentDataTable();
        table.setId(id);
        table.setTableKey(name);
        table.setPhysicalSchema("public");
        table.setPhysicalName(name);
        table.setStatus("active");
        table.setMetadataPresent(true);
        return table;
    }

    private AgentDataColumn column(Long id, Long tableId, String name, String dataType) {
        AgentDataColumn column = new AgentDataColumn();
        column.setId(id);
        column.setTableId(tableId);
        column.setPhysicalName(name);
        column.setDataType(dataType);
        column.setStatus("active");
        column.setMetadataPresent(true);
        return column;
    }
}
