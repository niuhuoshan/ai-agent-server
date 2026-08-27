package group.aitools.nhs.platform.openapi.web;

import group.aitools.nhs.platform.artifact.service.AcceptanceApplicationService;
import group.aitools.nhs.platform.artifact.service.ArtifactApplicationService;
import group.aitools.nhs.platform.artifact.web.AcceptanceDecisionRequest;
import group.aitools.nhs.platform.execution.service.ExecutionEventQueryService;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.platform.openapi.service.MachineApiGatewayService;
import group.aitools.nhs.platform.openapi.service.MachineApiGatewayService.ApiCallContext;
import group.aitools.nhs.platform.task.service.TaskApplicationService;
import group.aitools.nhs.platform.task.web.CreateTaskRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class OpenTaskApiControllerTest {

    private static final CurrentPrincipal MACHINE = new CurrentPrincipal(
        42L, "open-api", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
    );

    private MachineApiGatewayService gateway;
    private TaskApplicationService taskService;
    private AcceptanceApplicationService acceptanceService;
    private OpenTaskApiController controller;
    private ApiCallContext context;

    @BeforeEach
    void setUp() {
        gateway = mock(MachineApiGatewayService.class);
        taskService = mock(TaskApplicationService.class);
        acceptanceService = mock(AcceptanceApplicationService.class);
        controller = new OpenTaskApiController(
            gateway,
            taskService,
            mock(TaskRunApplicationService.class),
            mock(ExecutionEventQueryService.class),
            mock(ArtifactApplicationService.class),
            acceptanceService
        );
        context = new ApiCallContext(
            1L,
            "request-1",
            new AuthenticatedServiceAccount(
                MACHINE, 2L, "application", "open_api", 3L,
                Set.of("tasks:create", "acceptance:write")
            ),
            System.nanoTime()
        );
    }

    @Test
    void taskCreationRequiresDedicatedScopeAndUsesAuthenticatedMachine() {
        CreateTaskRequest request = new CreateTaskRequest(
            "task-create-1", "Task", "Objective", null, null, 88L, null,
            "enterprise_shared", "general", "single_agent", "L1_short_task",
            "R1", "human", 0, 0, null,
            Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of()
        );
        when(gateway.begin(
            "Bearer credential", Set.of("open_api", "internal"), "tasks:create",
            "task_create", "POST", "task", null, 128
        )).thenReturn(context);

        controller.createTask("Bearer credential", 128L, request);

        verify(taskService).createAs(MACHINE, request);
        verify(gateway).succeed(context, 200);
    }

    @Test
    void acceptanceRequiresDedicatedScopeAndUsesAuthenticatedMachine() {
        AcceptanceDecisionRequest request = new AcceptanceDecisionRequest(
            "accept-1", List.of(700L), "passed", "reviewed", Map.of("passed", true)
        );
        when(gateway.begin(
            "Bearer credential", Set.of("open_api", "internal"), "acceptance:write",
            "task_acceptance", "POST", "task", 10L, 96
        )).thenReturn(context);

        controller.accept(10L, 20L, "Bearer credential", 96L, request);

        verify(acceptanceService).decideAs(MACHINE, 10L, 20L, request);
        verify(gateway).succeed(context, 200);
    }
}
