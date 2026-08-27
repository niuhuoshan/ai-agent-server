package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.mapper.DataGovernanceMapper;
import group.aitools.nhs.platform.data.mapper.DataProfileMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class DataProfileRecommendationServiceTest {

    @Test
    void generatesRecommendationsOnlyFromProfilesProducedByTheRequestedJob() {
        PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        DataCatalogMapper catalogMapper = mock(DataCatalogMapper.class);
        DataGovernanceMapper governanceMapper = mock(DataGovernanceMapper.class);
        DataProfileMapper profileMapper = mock(DataProfileMapper.class);
        when(catalogMapper.selectTables(1L)).thenReturn(List.of());
        when(catalogMapper.selectColumns(1L)).thenReturn(List.of());
        when(governanceMapper.selectRelationships(1L)).thenReturn(List.of());
        when(profileMapper.selectJobProfiles(1L, 20L)).thenReturn(List.of());
        DataProfileRecommendationService service = new DataProfileRecommendationService(
            idGenerator, catalogMapper, governanceMapper, profileMapper
        );

        int inserted = service.generate(1L, 20L);

        assertEquals(0, inserted);
        verify(profileMapper).selectJobProfiles(1L, 20L);
        verify(profileMapper, never()).selectLatestProfiles(1L);
    }
}
