package group.aitools.nhs.platform.report.service;

import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.report.mapper.AgentReportMapper;
import group.aitools.nhs.platform.report.persistence.row.ReportExecutionPrincipalRow;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class ReportExecutionPrincipalResolverTest {

    private final AgentReportMapper mapper = mock(AgentReportMapper.class);
    private final ReportExecutionPrincipalResolver resolver = new ReportExecutionPrincipalResolver(mapper);

    @Test
    void resolvesCurrentRolesAndMapsLegacySuperAdmin() {
        ReportExecutionPrincipalRow row = user("0", "0", "sys_user");
        when(mapper.selectReportExecutionPrincipal(101L)).thenReturn(row);
        when(mapper.selectReportExecutionRoleKeys(101L)).thenReturn(List.of("superadmin", "approval_user"));

        var principal = resolver.resolve(101L);

        assertThat(principal.username()).isEqualTo("member");
        assertThat(principal.roles()).contains(
            PlatformRole.MEMBER, PlatformRole.PLATFORM_ADMIN, PlatformRole.APPROVAL_USER
        );
    }

    @Test
    void rejectsDisabledOwnerBeforeAnyDataQuery() {
        when(mapper.selectReportExecutionPrincipal(101L)).thenReturn(user("1", "0", "sys_user"));

        assertThatThrownBy(() -> resolver.resolve(101L))
            .hasMessageContaining("已停用或不存在");
    }

    @Test
    void resolvesActiveHumanByUsernameWithCurrentRoles() {
        ReportExecutionPrincipalRow row = user(202L, "alice", "0", "0", "sys_user");
        when(mapper.selectReportExecutionPrincipalByUsername("Alice")).thenReturn(row);
        when(mapper.selectReportExecutionRoleKeys(202L)).thenReturn(List.of("superadmin", "approval_user"));

        var principal = resolver.resolve(" Alice ");

        assertThat(principal.id()).isEqualTo(202L);
        assertThat(principal.username()).isEqualTo("alice");
        assertThat(principal.roles()).contains(
            PlatformRole.MEMBER, PlatformRole.PLATFORM_ADMIN, PlatformRole.APPROVAL_USER
        );
    }

    @Test
    void rejectsDisabledDeletedAndServiceAccountUsersResolvedByUsername() {
        when(mapper.selectReportExecutionPrincipalByUsername("disabled"))
            .thenReturn(user(201L, "disabled", "1", "0", "sys_user"));
        when(mapper.selectReportExecutionPrincipalByUsername("deleted"))
            .thenReturn(user(202L, "deleted", "0", "1", "sys_user"));
        when(mapper.selectReportExecutionPrincipalByUsername("service"))
            .thenReturn(user(203L, "service", "0", "0", "service_account"));

        assertThatThrownBy(() -> resolver.resolve("disabled")).hasMessageContaining("不存在或已禁用");
        assertThatThrownBy(() -> resolver.resolve("deleted")).hasMessageContaining("不存在或已禁用");
        assertThatThrownBy(() -> resolver.resolve("service")).hasMessageContaining("服务账号");
    }

    @Test
    void reportsUnknownUsernameAsNotFound() {
        assertThatThrownBy(() -> resolver.resolve("missing"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("不存在或已禁用")
            .extracting(exception -> ((ServiceException) exception).getCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ReportExecutionPrincipalRow user(String status, String delFlag, String userType) {
        return user(101L, "member", status, delFlag, userType);
    }

    private ReportExecutionPrincipalRow user(
        Long userId,
        String username,
        String status,
        String delFlag,
        String userType
    ) {
        ReportExecutionPrincipalRow row = new ReportExecutionPrincipalRow();
        row.setUserId(userId);
        row.setUserName(username);
        row.setUserType(userType);
        row.setStatus(status);
        row.setDelFlag(delFlag);
        return row;
    }
}
