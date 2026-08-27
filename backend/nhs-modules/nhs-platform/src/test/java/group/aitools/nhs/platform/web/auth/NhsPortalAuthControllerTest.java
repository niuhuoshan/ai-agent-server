package group.aitools.nhs.platform.web.auth;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.iam.service.PlatformUiPermissionService;
import group.aitools.nhs.common.core.domain.R;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class NhsPortalAuthControllerTest {

    @Test
    void projectsTheCurrentPrincipalAndEffectiveUiPermissions() {
        CurrentPrincipal principal = new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        PlatformUiPermissionService permissions = mock(PlatformUiPermissionService.class);
        when(principals.currentPrincipal()).thenReturn(principal);
        when(permissions.buttons(principal)).thenReturn(List.of("conversation:list"));
        when(permissions.allowedRoutes(principal)).thenReturn(Set.of("workspace"));

        Map<String, Object> response = new NhsPortalAuthController(principals, permissions).me();

        assertThat(response).containsEntry("status", "success");
        assertThat(response.get("data")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(data).containsEntry("user_id", 101L).containsEntry("role", "user");
        assertThat(data.get("permissions")).isEqualTo(Map.of(
            "buttons", List.of("conversation:list"), "routes", Set.of("workspace")
        ));
    }

    @Test
    void wrapsEffectiveUiPermissionsInTheStandardResponseEnvelope() {
        CurrentPrincipal principal = new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        PlatformUiPermissionService permissions = mock(PlatformUiPermissionService.class);
        when(principals.currentPrincipal()).thenReturn(principal);
        when(permissions.buttons(principal)).thenReturn(List.of("conversation:list"));
        when(permissions.allowedRoutes(principal)).thenReturn(Set.of("workspace"));

        R<Map<String, Object>> response = new NhsPortalAuthController(principals, permissions).permissions();

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getMsg()).isEqualTo("操作成功");
        assertThat(response.getData()).isEqualTo(Map.of(
            "buttons", List.of("conversation:list"), "routes", Set.of("workspace")
        ));
    }
}
