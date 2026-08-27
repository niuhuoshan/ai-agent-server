package group.aitools.nhs.platform.data;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.service.DataMetadataPersistenceService;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class DataMetadataPersistenceServiceTest {

    private DataCatalogMapper mapper;
    private DataMetadataPersistenceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DataCatalogMapper.class);
        service = new DataMetadataPersistenceService(
            mock(PlatformIdGenerator.class), mapper, JsonMapper.builder().build()
        );
    }

    @Test
    void appliesAndFinishesOnlyTheSameDatasetAndSourceRevisions() {
        AgentDataDataset dataset = dataset(3, "syncing");
        AgentDataSource source = source(7, "active");
        when(mapper.lockDatasetForMetadataApply(800L)).thenReturn(dataset);
        when(mapper.lockSourceForMetadataApply(700L)).thenReturn(source);
        when(mapper.selectTables(800L)).thenReturn(List.of());
        when(mapper.selectColumns(800L)).thenReturn(List.of());
        when(mapper.finishDatasetSync(eq(800L), eq(3), eq("active"), eq(null), eq(9L), any()))
            .thenReturn(1);
        when(mapper.recordSourceMetadataSync(eq(700L), eq(7), eq(null), eq(9L), any()))
            .thenReturn(1);
        LocalDateTime now = LocalDateTime.now();

        service.applyCurrentSnapshot(800L, 3, 700L, 7, List.of(), 9L, now);

        verify(mapper).deactivateMissingTables(800L, List.of(), 9L, now);
        verify(mapper).finishDatasetSync(800L, 3, "active", null, 9L, now);
        verify(mapper).recordSourceMetadataSync(700L, 7, null, 9L, now);
    }

    @Test
    void rejectsAResultDiscoveredFromAnOlderDatasetRevisionBeforeChangingMetadata() {
        when(mapper.lockDatasetForMetadataApply(800L)).thenReturn(dataset(4, "syncing"));

        ServiceException exception = assertThrows(ServiceException.class, () ->
            service.applyCurrentSnapshot(
                800L, 3, 700L, 7, List.of(), 9L, LocalDateTime.now()
            )
        );

        assertEquals(409, exception.getCode());
        verify(mapper, never()).selectTables(800L);
        verify(mapper, never()).deactivateMissingTables(eq(800L), any(), eq(9L), any());
        verify(mapper, never()).finishDatasetSync(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsAResultDiscoveredFromAnOlderSourceRevision() {
        when(mapper.lockDatasetForMetadataApply(800L)).thenReturn(dataset(3, "syncing"));
        when(mapper.lockSourceForMetadataApply(700L)).thenReturn(source(8, "active"));

        ServiceException exception = assertThrows(ServiceException.class, () ->
            service.applyCurrentSnapshot(
                800L, 3, 700L, 7, List.of(), 9L, LocalDateTime.now()
            )
        );

        assertEquals(409, exception.getCode());
        verify(mapper, never()).selectTables(800L);
        verify(mapper, never()).finishDatasetSync(any(), any(), any(), any(), any(), any());
    }

    private AgentDataDataset dataset(int revision, String status) {
        AgentDataDataset value = new AgentDataDataset();
        value.setId(800L);
        value.setDataSourceId(700L);
        value.setRevisionNo(revision);
        value.setStatus(status);
        return value;
    }

    private AgentDataSource source(int revision, String status) {
        AgentDataSource value = new AgentDataSource();
        value.setId(700L);
        value.setRevisionNo(revision);
        value.setStatus(status);
        return value;
    }
}
