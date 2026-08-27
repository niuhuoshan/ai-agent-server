package group.aitools.nhs.platform.compat.nhs;

import group.aitools.nhs.platform.data.service.DataSourceCatalogService;
import group.aitools.nhs.platform.data.web.DataColumnView;
import group.aitools.nhs.platform.data.web.DataTableView;
import group.aitools.nhs.platform.data.web.DatasetView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("local")
class NhsV1CompatibilityControllerTest {

    private final CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
    private final DataSourceCatalogService catalogService = mock(DataSourceCatalogService.class);
    private NhsV1CompatibilityController controller;

    @BeforeEach
    void setUp() {
        controller = new NhsV1CompatibilityController(principalProvider, catalogService);
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            7L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));
    }

    @Test
    void profileUsesCurrentPrincipalWithoutExposingApiKey() {
        NhsResponse<NhsUserProfile> response = controller.profile(null);

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.message()).isEqualTo("success");
        assertThat(response.data().id()).isEqualTo(7L);
        assertThat(response.data().username()).isEqualTo("alice");
        assertThat(response.data().role()).isEqualTo("user");
        assertThat(response.data().api_key()).isNull();
        assertThat(response.data().roles()).containsExactly("member");
        assertThat(response.data().unsupported()).containsExactly("api_key", "permissions");
    }

    @Test
    void schemaDelegatesAuthorizationAndBuildsLocalYamlContext() {
        DatasetView dataset = new DatasetView(
            11L, 12L, "sales", "Sales", "Sales data", "active", List.of("public"),
            1, null, null, 7L, null, null
        );
        DataColumnView column = new DataColumnView(
            101L, "orders.amount", "amount", "Amount", "numeric", "Order amount",
            false, false, "active", true
        );
        DataTableView table = new DataTableView(
            21L, "orders", "public", "orders", "Orders", "Order facts", "table",
            "active", true, List.of(column)
        );
        when(catalogService.listDatasets(200)).thenReturn(List.of(dataset));
        when(catalogService.metadata(11L)).thenReturn(List.of(table));

        NhsResponse<NhsSchemaResponse> response = controller.schema(
            new NhsSchemaRequest("amount", "local", 5, null, null)
        );

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.data().provider()).isEqualTo("local");
        assertThat(response.data().hits()).extracting(NhsSchemaHit::id).containsExactly(11L);
        assertThat(response.data().schema_context()).contains("datasets", "orders", "amount");
        assertThat(response.data().unsupported()).contains("ragflow", "relationships");
        verify(catalogService).metadata(11L);
    }

    @Test
    void ragflowProviderIsExplicitlyUnsupported() {
        assertThatThrownBy(() -> controller.schema(
            new NhsSchemaRequest("sales", "ragflow", null, null, null)
        ))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("not configured");
    }
}
