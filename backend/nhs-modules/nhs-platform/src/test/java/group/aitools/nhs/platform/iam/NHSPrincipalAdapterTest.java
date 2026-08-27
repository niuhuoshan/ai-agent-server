package group.aitools.nhs.platform.iam;

import group.aitools.nhs.platform.iam.adapter.NHSPrincipalAdapter;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.system.api.model.LoginUser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class NHSPrincipalAdapterTest {

    @Test
    void userWithoutDepartmentOrPostIsStillAValidPlatformPrincipal() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1001L);
        loginUser.setUsername("alice");
        loginUser.setUserType("sys_user");
        loginUser.setRolePermission(Set.of("member"));

        CurrentPrincipal principal = NHSPrincipalAdapter.adapt(loginUser);

        assertEquals(1001L, principal.id());
        assertEquals(PrincipalType.HUMAN, principal.type());
        assertTrue(principal.hasRole(PlatformRole.MEMBER));
    }

    @Test
    void organizationFieldsDoNotChangeThePlatformPrincipal() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1002L);
        loginUser.setUsername("bob");
        loginUser.setUserType("sys_user");
        loginUser.setRolePermission(Set.of("member"));

        CurrentPrincipal withoutOrganization = NHSPrincipalAdapter.adapt(loginUser);
        loginUser.setDeptId(88L);
        loginUser.setDeptName("ignored department");
        loginUser.setDeptCategory("ignored category");
        CurrentPrincipal withOrganization = NHSPrincipalAdapter.adapt(loginUser);

        assertEquals(withoutOrganization, withOrganization);
    }

    @Test
    void legacySuperAdminRoleMapsToPlatformAdminForBootstrapCompatibility() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);
        loginUser.setUsername("admin");
        loginUser.setUserType("sys_user");
        loginUser.setRolePermission(Set.of("superadmin"));

        CurrentPrincipal principal = NHSPrincipalAdapter.adapt(loginUser);

        assertTrue(principal.hasRole(PlatformRole.PLATFORM_ADMIN));
        assertTrue(principal.hasRole(PlatformRole.MEMBER));
    }

    @Test
    void serviceAccountCannotInheritHumanRoles() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(3001L);
        loginUser.setUsername("job-worker");
        loginUser.setUserType("service_account");
        loginUser.setRolePermission(Set.of("platform_admin", "service_account"));

        CurrentPrincipal principal = NHSPrincipalAdapter.adapt(loginUser);

        assertEquals(PrincipalType.SERVICE_ACCOUNT, principal.type());
        assertEquals(Set.of(PlatformRole.SERVICE_ACCOUNT), principal.roles());
    }
}
