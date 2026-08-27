package group.aitools.nhs.platform.web.auth;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.iam.service.PlatformUiPermissionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class PlatformAuthControllerTest {

    private final CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
    private final PlatformUiPermissionService uiPermissions = mock(PlatformUiPermissionService.class);
    private final PlatformAuthController controller = new PlatformAuthController(principals, uiPermissions);

    @Test
    void returnsSortedRolesAndEffectiveButtons() {
        CurrentPrincipal principal = new CurrentPrincipal(
            7L,
            "admin",
            PrincipalType.HUMAN,
            Set.of(PlatformRole.PLATFORM_ADMIN, PlatformRole.MEMBER)
        );
        when(principals.currentPrincipal()).thenReturn(principal);
        when(uiPermissions.buttons(principal)).thenReturn(List.of(
            "model:create", "connector:create", "tool:create"
        ));

        PlatformUserInfo result = controller.getUserInfo().getData();

        assertThat(result.userId()).isEqualTo("7");
        assertThat(result.userName()).isEqualTo("admin");
        assertThat(result.roles()).containsExactly("member", "platform_admin");
        assertThat(result.buttons()).containsExactly(
            "model:create", "connector:create", "tool:create"
        );
    }

    @Test
    void serviceAccountReceivesNoHumanButtons() {
        CurrentPrincipal principal = new CurrentPrincipal(
            9L,
            "runner",
            PrincipalType.SERVICE_ACCOUNT,
            Set.of(PlatformRole.SERVICE_ACCOUNT)
        );
        when(principals.currentPrincipal()).thenReturn(principal);
        when(uiPermissions.buttons(principal)).thenReturn(List.of());

        PlatformUserInfo result = controller.getUserInfo().getData();

        assertThat(result.roles()).containsExactly("service_account");
        assertThat(result.buttons()).isEmpty();
    }
}
