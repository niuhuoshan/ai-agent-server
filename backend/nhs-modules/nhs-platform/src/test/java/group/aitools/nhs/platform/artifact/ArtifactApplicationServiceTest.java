package group.aitools.nhs.platform.artifact;

import group.aitools.nhs.platform.artifact.domain.AgentArtifact;
import group.aitools.nhs.platform.artifact.mapper.ArtifactAcceptanceMapper;
import group.aitools.nhs.platform.artifact.persistence.row.ArtifactTaskRow;
import group.aitools.nhs.platform.artifact.service.ArtifactApplicationService;
import group.aitools.nhs.platform.artifact.web.ArtifactView;
import group.aitools.nhs.platform.artifact.web.RegisterArtifactRequest;
import group.aitools.nhs.platform.audit.service.AuthorizationAuditService;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.domain.AgentTaskRun;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.iam.service.TaskVisibilityService;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ArtifactApplicationServiceTest {

    private static final String HASH = "a".repeat(64);
    private static final CurrentPrincipal OWNER = new CurrentPrincipal(
        101L, "owner", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private ArtifactAcceptanceMapper artifactMapper;
    private TaskRunCommandMapper runMapper;
    private PlatformIdGenerator idGenerator;
    private ArtifactApplicationService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        artifactMapper = mock(ArtifactAcceptanceMapper.class);
        runMapper = mock(TaskRunCommandMapper.class);
        idGenerator = mock(PlatformIdGenerator.class);
        when(principalProvider.currentPrincipal()).thenReturn(OWNER);
        when(artifactMapper.selectTask(10L)).thenReturn(task());
        when(runMapper.selectRun(10L, 500L)).thenReturn(run("running"));
        when(runMapper.selectRelations(10L, 101L, "human")).thenReturn(List.of("OWNER"));
        when(artifactMapper.selectNextVersion(any(), any(), any(), any())).thenReturn(2);
        when(artifactMapper.insertArtifact(any())).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(700L);
        when(artifactMapper.selectArtifact(10L, 700L)).thenReturn(storedArtifact());
        service = new ArtifactApplicationService(
            principalProvider,
            authorizationEnforcer,
            mock(AuthorizationAuditService.class),
            mock(TaskVisibilityService.class),
            mock(TaskQueryService.class),
            artifactMapper,
            runMapper,
            idGenerator,
            JsonMapper.builder().build(),
            mock(NotificationApplicationService.class)
        );
    }

    @Test
    void registersImmutableVersionOnlyAfterTakingTaskLock() {
        ArtifactView result = service.register(10L, 500L, request("local", "tasks/10/report.pdf", "internal", "inherit", Map.of()));

        assertEquals(700L, result.id());
        ArgumentCaptor<AgentArtifact> captor = ArgumentCaptor.forClass(AgentArtifact.class);
        verify(artifactMapper).insertArtifact(captor.capture());
        assertEquals(2, captor.getValue().getVersionNo());
        assertEquals("available", captor.getValue().getStatus());
        InOrder order = inOrder(runMapper, artifactMapper);
        order.verify(runMapper).lockTask(10L);
        order.verify(artifactMapper).selectNextVersion(10L, 500L, "document", "report.pdf");
        order.verify(artifactMapper).insertArtifact(any());
    }

    @Test
    void rejectsTraversalAndCredentialBearingExternalReferences() {
        ServiceException traversal = assertThrows(ServiceException.class, () ->
            service.register(10L, 500L, request("local", "../secrets.txt", "internal", "inherit", Map.of()))
        );
        ServiceException credentialUrl = assertThrows(ServiceException.class, () ->
            service.register(10L, 500L, request("external", "https://user:pass@example.com/report", "internal", "inherit", Map.of()))
        );

        assertTrue(traversal.getMessage().contains("安全"));
        assertTrue(credentialUrl.getMessage().contains("无凭证"));
        verify(artifactMapper, never()).insertArtifact(any());
    }

    @Test
    void rejectsSharedSensitiveArtifactsAndNestedSecretMetadata() {
        ServiceException shared = assertThrows(ServiceException.class, () ->
            service.register(10L, 500L, request("local", "tasks/10/report.pdf", "secret", "enterprise_shared", Map.of()))
        );
        ServiceException metadata = assertThrows(ServiceException.class, () ->
            service.register(10L, 500L, request(
                "local", "tasks/10/report.pdf", "internal", "inherit",
                Map.of("build", Map.of("apiToken", "must-not-persist"))
            ))
        );

        assertTrue(shared.getMessage().contains("敏感制品"));
        assertTrue(metadata.getMessage().contains("敏感字段"));
        verify(artifactMapper, never()).insertArtifact(any());
    }

    @Test
    void rejectsArtifactForWrongRunOrStep() {
        when(runMapper.selectRun(10L, 500L)).thenReturn(null);
        assertThrows(ServiceException.class, () ->
            service.register(10L, 500L, request("local", "tasks/10/report.pdf", "internal", "inherit", Map.of()))
        );

        when(runMapper.selectRun(10L, 500L)).thenReturn(run("running"));
        when(artifactMapper.stepBelongsToRun(500L, 999L)).thenReturn(false);
        RegisterArtifactRequest wrongStep = new RegisterArtifactRequest(
            "document", "report.pdf", "local", "tasks/10/report.pdf", "application/pdf",
            12L, HASH, "internal", "inherit", 999L, Map.of()
        );
        assertThrows(ServiceException.class, () -> service.register(10L, 500L, wrongStep));
        verify(artifactMapper, never()).insertArtifact(any());
    }

    private RegisterArtifactRequest request(
        String storageType,
        String storageRef,
        String sensitiveLevel,
        String visibility,
        Map<String, Object> metadata
    ) {
        return new RegisterArtifactRequest(
            "document", "report.pdf", storageType, storageRef, "application/pdf",
            12L, HASH, sensitiveLevel, visibility, null, metadata
        );
    }

    private ArtifactTaskRow task() {
        ArtifactTaskRow task = new ArtifactTaskRow();
        task.setTaskId(10L);
        task.setProjectId(20L);
        task.setLatestRunId(500L);
        task.setTaskStatus("running");
        task.setVisibility("enterprise_shared");
        return task;
    }

    private AgentTaskRun run(String status) {
        AgentTaskRun run = new AgentTaskRun();
        run.setId(500L);
        run.setTaskId(10L);
        run.setStatus(status);
        return run;
    }

    private AgentArtifact storedArtifact() {
        AgentArtifact artifact = new AgentArtifact();
        artifact.setId(700L);
        artifact.setProjectId(20L);
        artifact.setTaskId(10L);
        artifact.setRunId(500L);
        artifact.setArtifactType("document");
        artifact.setName("report.pdf");
        artifact.setVersionNo(2);
        artifact.setStorageType("local");
        artifact.setStorageRef("tasks/10/report.pdf");
        artifact.setMimeType("application/pdf");
        artifact.setSizeBytes(12L);
        artifact.setContentHash(HASH);
        artifact.setSensitiveLevel("internal");
        artifact.setVisibility("inherit");
        artifact.setStatus("available");
        artifact.setMetadataJson("{}");
        artifact.setCreatedBy(101L);
        return artifact;
    }
}
