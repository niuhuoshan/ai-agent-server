package group.aitools.nhs.platform.web.route;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.iam.service.PlatformUiPermissionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class PlatformRouteControllerTest {

    private final CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
    private final PlatformUiPermissionService uiPermissions = mock(PlatformUiPermissionService.class);
    private final PlatformRouteController controller = new PlatformRouteController(principals, uiPermissions);

    @Test
    void humanMemberReceivesBusinessRoutesWithoutAdministration() {
        when(principals.currentPrincipal()).thenReturn(human(PlatformRole.MEMBER));
        when(uiPermissions.allowedRoutes(org.mockito.ArgumentMatchers.any())).thenReturn(Set.of(
            "client", "home", "workspace", "task-center", "project-center", "knowledge",
            "risk-control", "saved-reports", "resource-center"
        ));

        PlatformUserRoutes result = controller.getUserRoutes().getData();

        assertThat(result.home()).isEqualTo("client");
        assertThat(result.routes()).extracting(PlatformRoute::name)
            .containsExactly(
                "client", "home", "workspace", "task-center", "project-center",
                "knowledge", "risk-control", "saved-reports", "resource-center"
            )
            .doesNotContain("system", "open-api", "agent-center", "data-source", "automation");
        assertThat(controller.isRouteExist("task-center").getData()).isTrue();
        assertThat(controller.isRouteExist("client").getData()).isTrue();
        assertThat(controller.isRouteExist("system").getData()).isTrue();
        assertThat(controller.isRouteExist("unknown").getData()).isFalse();
    }

    @Test
    void platformAdministratorReceivesAdministrationRoutes() {
        when(principals.currentPrincipal()).thenReturn(human(PlatformRole.PLATFORM_ADMIN));
        when(uiPermissions.allowedRoutes(org.mockito.ArgumentMatchers.any())).thenReturn(Set.of(
            "client", "home", "workspace", "task-center", "project-center", "agent-center",
            "knowledge", "data-source", "automation", "risk-control", "saved-reports",
            "resource-center", "open-api", "widget-debugger", "system"
        ));

        assertThat(controller.getUserRoutes().getData().routes()).extracting(PlatformRoute::name)
            .contains("system", "open-api", "widget-debugger", "resource-center");
        assertThat(controller.isRouteExist("system").getData()).isTrue();
        assertThat(controller.isRouteExist("resource-center").getData()).isTrue();
        assertThat(controller.isRouteExist("widget-debugger").getData()).isTrue();
    }

    @Test
    void serviceAccountNeverInheritsHumanNavigation() {
        when(principals.currentPrincipal()).thenReturn(new CurrentPrincipal(
            9L, "runner", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
        ));

        assertThat(controller.getUserRoutes().getData().routes()).extracting(PlatformRoute::name)
            .isEmpty();
        assertThat(controller.getUserRoutes().getData().home()).isEqualTo("403");
        assertThat(controller.isRouteExist("client").getData()).isTrue();
        assertThat(controller.isRouteExist("task-center").getData()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(uiPermissions);
    }

    private CurrentPrincipal human(PlatformRole role) {
        return new CurrentPrincipal(1L, "user", PrincipalType.HUMAN, Set.of(role));
    }
}
