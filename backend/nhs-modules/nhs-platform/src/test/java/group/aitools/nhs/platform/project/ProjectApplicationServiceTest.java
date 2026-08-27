package group.aitools.nhs.platform.project;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.project.domain.AgentProject;
import group.aitools.nhs.platform.project.domain.AgentProjectMember;
import group.aitools.nhs.platform.project.mapper.AgentProjectMapper;
import group.aitools.nhs.platform.project.service.ProjectApplicationService;
import group.aitools.nhs.platform.project.web.CreateProjectRequest;
import group.aitools.nhs.platform.project.web.ProjectMemberView;
import group.aitools.nhs.platform.project.web.ProjectMutationResult;
import group.aitools.nhs.platform.project.web.ProjectView;
import group.aitools.nhs.platform.project.web.UpdateProjectRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ProjectApplicationServiceTest {

    private static final CurrentPrincipal OWNER = new CurrentPrincipal(
        101L, "owner", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal MANAGER = new CurrentPrincipal(
        102L, "manager", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        103L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal VIEWER = new CurrentPrincipal(
        104L, "viewer", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal OUTSIDER = new CurrentPrincipal(
        105L, "outsider", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private CurrentPrincipal currentPrincipal;
    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private PlatformIdGenerator idGenerator;
    private AgentProjectMapper projectMapper;
    private ProjectApplicationService service;

    @BeforeEach
    void setUp() {
        currentPrincipal = OWNER;
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        idGenerator = mock(PlatformIdGenerator.class);
        projectMapper = mock(AgentProjectMapper.class);
        when(principalProvider.currentPrincipal()).thenAnswer(invocation -> currentPrincipal);
        service = new ProjectApplicationService(
            principalProvider,
            authorizationEnforcer,
            idGenerator,
            projectMapper,
            JsonMapper.builder().build()
        );
    }

    @Test
    void sameIdempotencyRequestReplaysWithoutCreatingAnotherProject() {
        when(idGenerator.nextId()).thenReturn(500L, 501L);
        when(projectMapper.selectByKey(anyString())).thenReturn(null);
        when(projectMapper.insertProject(any())).thenReturn(1);
        when(projectMapper.insertMember(any())).thenReturn(1);

        ProjectMutationResult first = service.create(request("project-a", "create-1"));
        ArgumentCaptor<AgentProject> projectCaptor = ArgumentCaptor.forClass(AgentProject.class);
        verify(projectMapper).insertProject(projectCaptor.capture());
        AgentProject persisted = projectCaptor.getValue();

        when(projectMapper.selectByKey(anyString())).thenReturn(persisted);
        ProjectMutationResult replay = service.create(request("project-a", "create-1"));

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.project().id(), replay.project().id());
        verify(projectMapper).insertProject(any());
        verify(projectMapper).insertMember(any());
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejected() {
        when(idGenerator.nextId()).thenReturn(500L, 501L);
        when(projectMapper.selectByKey(anyString())).thenReturn(null);
        when(projectMapper.insertProject(any())).thenReturn(1);
        when(projectMapper.insertMember(any())).thenReturn(1);
        service.create(request("project-a", "create-1"));
        ArgumentCaptor<AgentProject> projectCaptor = ArgumentCaptor.forClass(AgentProject.class);
        verify(projectMapper).insertProject(projectCaptor.capture());

        when(projectMapper.selectByKey(anyString())).thenReturn(projectCaptor.getValue());
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.create(request("project-b", "create-1"))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(projectMapper).insertProject(any());
    }

    @Test
    void sensitivePolicyFieldsAndDeepPoliciesAreRejectedBeforePersistence() {
        Map<String, Object> secretPolicy = Map.of("connector", Map.of("apiKey", "raw-secret"));
        ServiceException secret = assertThrows(
            ServiceException.class,
            () -> service.create(new CreateProjectRequest(
                "create-secret", "project", null, null, secretPolicy, Map.of(), List.of()
            ))
        );
        assertEquals(HttpStatus.BAD_REQUEST, secret.getCode());

        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cursor = deep;
        for (int i = 0; i < 18; i++) {
            Map<String, Object> nested = new LinkedHashMap<>();
            cursor.put("level" + i, nested);
            cursor = nested;
        }
        ServiceException tooDeep = assertThrows(
            ServiceException.class,
            () -> service.create(new CreateProjectRequest(
                "create-deep", "project", null, null, deep, Map.of(), List.of()
            ))
        );
        assertEquals(HttpStatus.BAD_REQUEST, tooDeep.getCode());
        verify(projectMapper, never()).insertProject(any());
    }

    @Test
    void outsiderCannotViewProjectAndRoleRelationsArePassedToAuthorization() {
        AgentProject project = project(900L, OWNER.id(), "active");
        when(projectMapper.selectProject(900L)).thenReturn(project);

        currentPrincipal = OUTSIDER;
        when(projectMapper.selectActiveMember(900L, OUTSIDER.id())).thenReturn(null);
        doAnswer(invocation -> {
            PermissionContext context = invocation.getArgument(1);
            if (context.relations().isEmpty()) {
                throw new ServiceException("forbidden", HttpStatus.FORBIDDEN);
            }
            return null;
        }).when(authorizationEnforcer).requireAllowed(any(), any());
        assertThrows(ServiceException.class, () -> service.get(900L));

        currentPrincipal = MANAGER;
        when(projectMapper.selectActiveMember(900L, MANAGER.id())).thenReturn(member(900L, MANAGER.id(), "manager"));
        ArgumentCaptor<PermissionContext> managerContext = ArgumentCaptor.forClass(PermissionContext.class);
        service.get(900L);
        verify(authorizationEnforcer, org.mockito.Mockito.atLeastOnce()).requireAllowed(any(), managerContext.capture());
        assertTrue(managerContext.getValue().relations().contains(BusinessRelation.PROJECT_ADMIN));

        currentPrincipal = VIEWER;
        when(projectMapper.selectActiveMember(900L, VIEWER.id())).thenReturn(member(900L, VIEWER.id(), "viewer"));
        ArgumentCaptor<PermissionContext> viewerContext = ArgumentCaptor.forClass(PermissionContext.class);
        service.get(900L);
        verify(authorizationEnforcer, org.mockito.Mockito.atLeastOnce()).requireAllowed(any(), viewerContext.capture());
        assertTrue(viewerContext.getValue().relations().contains(BusinessRelation.WATCHER));
    }

    @Test
    void managerCanUpdateButMemberCannotUseProjectAdminOperation() {
        AgentProject project = project(901L, OWNER.id(), "active");
        when(projectMapper.lockProject(901L)).thenReturn(901L);
        when(projectMapper.selectProject(901L)).thenReturn(project);
        when(projectMapper.updateProject(any())).thenReturn(1);

        currentPrincipal = MANAGER;
        when(projectMapper.selectActiveMember(901L, MANAGER.id())).thenReturn(member(901L, MANAGER.id(), "manager"));
        service.update(901L, updateRequest());
        verify(projectMapper).updateProject(any());

        currentPrincipal = MEMBER;
        when(projectMapper.selectActiveMember(901L, MEMBER.id())).thenReturn(member(901L, MEMBER.id(), "member"));
        doAnswer(invocation -> {
            PermissionContext context = invocation.getArgument(1);
            if ("admin".equals(context.action())) {
                throw new ServiceException("forbidden", HttpStatus.FORBIDDEN);
            }
            return null;
        }).when(authorizationEnforcer).requireAllowed(any(), any());
        assertThrows(ServiceException.class, () -> service.update(901L, updateRequest()));
    }

    @Test
    void ownerCannotBeRemovedAndArchivedProjectCannotBeEdited() {
        AgentProject active = project(902L, OWNER.id(), "active");
        when(projectMapper.lockProject(902L)).thenReturn(902L);
        when(projectMapper.selectProject(902L)).thenReturn(active);
        assertEquals(HttpStatus.CONFLICT, assertThrows(
            ServiceException.class, () -> service.removeMember(902L, OWNER.id())
        ).getCode());
        verify(projectMapper, never()).removeMember(anyLong(), anyLong());

        AgentProject archived = project(903L, OWNER.id(), "archived");
        when(projectMapper.lockProject(903L)).thenReturn(903L);
        when(projectMapper.selectProject(903L)).thenReturn(archived);
        assertEquals(HttpStatus.CONFLICT, assertThrows(
            ServiceException.class, () -> service.update(903L, updateRequest())
        ).getCode());
        verify(projectMapper, never()).updateProject(any());
    }

    private CreateProjectRequest request(String name, String idempotencyKey) {
        return new CreateProjectRequest(
            idempotencyKey, name, "description", null,
            Map.of("workspace", Map.of("mode", "isolated")),
            Map.of("events", List.of("completed")), List.of("phase-1")
        );
    }

    private UpdateProjectRequest updateRequest() {
        return new UpdateProjectRequest(
            "updated", "description", null, Map.of(), Map.of(), List.of()
        );
    }

    private AgentProject project(Long id, Long ownerId, String status) {
        AgentProject project = new AgentProject();
        project.setId(id);
        project.setProjectKey("P-project-" + id);
        project.setName("project-" + id);
        project.setDescription("description");
        project.setStatus(status);
        project.setOwnerId(ownerId);
        project.setWorkspacePolicyJson("{}");
        project.setNotificationPolicyJson("{}");
        project.setTagsJson("[]");
        project.setCreateTime(LocalDateTime.now());
        project.setDelFlag("0");
        return project;
    }

    private AgentProjectMember member(Long projectId, Long userId, String role) {
        AgentProjectMember member = new AgentProjectMember();
        member.setId(userId + 1000L);
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setMemberRole(role);
        member.setStatus("active");
        member.setJoinedAt(LocalDateTime.now());
        return member;
    }
}
