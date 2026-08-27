package group.aitools.nhs.platform.data.web;

import group.aitools.nhs.platform.data.service.DataMetadataSyncService;
import group.aitools.nhs.platform.data.service.DataQueryExecutionService;
import group.aitools.nhs.platform.data.service.DataQueryExportService;
import group.aitools.nhs.platform.data.service.DataSourceCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("dev")
class PlatformDataControllerTest {

    private DataSourceCatalogService catalogService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        catalogService = mock(DataSourceCatalogService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new PlatformDataController(
            catalogService,
            mock(DataMetadataSyncService.class),
            mock(DataQueryExecutionService.class),
            mock(DataQueryExportService.class)
        )).build();
    }

    @Test
    void exposesDatasetDeleteImpactWithoutReferencedObjectDetails() throws Exception {
        var view = new DatasetDeleteImpactView(
            1L,
            List.of(new DatasetDeleteImpactView.CategoryView("active_reports", 2)),
            2,
            false
        );
        when(catalogService.datasetDeleteImpact(1L)).thenReturn(view);

        mockMvc.perform(get("/platform/datasets/1/delete-impact"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.categories[0].category").value("active_reports"))
            .andExpect(jsonPath("$.data.categories[0].count").value(2))
            .andExpect(jsonPath("$.data.blockingTotal").value(2))
            .andExpect(jsonPath("$.data.deletable").value(false));

        verify(catalogService).datasetDeleteImpact(1L);
    }
}
