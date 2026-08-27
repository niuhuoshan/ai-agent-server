package group.aitools.nhs.platform.task;

import group.aitools.nhs.platform.audit.service.AuthorizationAuditService;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.iam.service.TaskVisibilityService;
import group.aitools.nhs.platform.task.domain.AgentTask;
import group.aitools.nhs.platform.task.mapper.AgentTaskMapper;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class TaskQueryServiceTest {

    @Test
    void deniedRestrictedTaskIsAuditedAndHiddenAsNotFound() {
        CurrentPrincipal principal = new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );
        CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
        AuthorizationEnforcer authorizationEnforcer = mock(AuthorizationEnforcer.class);
        TaskVisibilityService visibilityService = mock(TaskVisibilityService.class);
        AuthorizationAuditService auditService = mock(AuthorizationAuditService.class);
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTask restrictedTask = new AgentTask();
        restrictedTask.setId(77L);
        restrictedTask.setVisibility("restricted");
        AuthorizationDecision denied = new AuthorizationDecision(
            PermissionEffect.DENY,
            "RESTRICTED_ACCESS_RULE_REQUIRED",
            "restricted task",
            List.of()
        );
        when(principalProvider.currentPrincipal()).thenReturn(principal);
        when(taskMapper.selectPlatformTaskById(77L)).thenReturn(restrictedTask);
        when(visibilityService.authorizeView(principal, 77L, null, group.aitools.nhs.platform.iam.domain.TaskVisibility.RESTRICTED))
            .thenReturn(denied);
        TaskQueryService service = new TaskQueryService(
            principalProvider,
            authorizationEnforcer,
            visibilityService,
            auditService,
            taskMapper,
            JsonMapper.builder().build()
        );

        ServiceException exception = assertThrows(ServiceException.class, () -> service.get(77L));

        assertEquals("任务不存在", exception.getMessage());
        verify(auditService).record(
            eq(principal),
            org.mockito.ArgumentMatchers.argThat(
                context -> context instanceof PermissionContext
                    && Long.valueOf(77L).equals(context.resourceId())
                    && Long.valueOf(77L).equals(context.taskId())
                    && "view".equals(context.action())
            ),
            eq(denied)
        );
    }
}
