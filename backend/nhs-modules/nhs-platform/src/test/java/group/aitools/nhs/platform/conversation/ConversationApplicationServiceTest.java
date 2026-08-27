package group.aitools.nhs.platform.conversation;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.conversation.service.ConversationApplicationService;
import group.aitools.nhs.platform.conversation.web.CreateConversationRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.project.domain.AgentProject;
import group.aitools.nhs.platform.project.mapper.AgentProjectMapper;
import group.aitools.nhs.platform.task.domain.AgentTask;
import group.aitools.nhs.platform.task.mapper.AgentTaskMapper;
import group.aitools.nhs.platform.task.service.TaskApplicationService;
import group.aitools.nhs.platform.task.web.ConvertConversationToTaskRequest;
import group.aitools.nhs.platform.task.web.TaskConversionResult;
import group.aitools.nhs.platform.task.web.TaskMutationResult;
import group.aitools.nhs.platform.task.web.TaskView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ConversationApplicationServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private PlatformIdGenerator idGenerator;
    private AgentConversationMapper conversationMapper;
    private AgentProjectMapper projectMapper;
    private AgentTaskMapper taskMapper;
    private TaskApplicationService taskApplicationService;
    private ConversationApplicationService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        idGenerator = mock(PlatformIdGenerator.class);
        conversationMapper = mock(AgentConversationMapper.class);
        projectMapper = mock(AgentProjectMapper.class);
        taskMapper = mock(AgentTaskMapper.class);
        taskApplicationService = mock(TaskApplicationService.class);
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
        service = new ConversationApplicationService(
            principalProvider, authorizationEnforcer, idGenerator, conversationMapper,
            projectMapper, taskMapper, taskApplicationService
        );
    }

    @Test
    void creatingConversationRequiresAgentVersionPermission() {
        when(idGenerator.nextId()).thenReturn(10L);
        when(idGenerator.nextUuid()).thenReturn("session");

        service.create(new CreateConversationRequest("chat", null, 5L, 88L));

        verify(authorizationEnforcer, times(2)).requireAllowed(eq(MEMBER), any());
        verify(authorizationEnforcer).requireAllowed(eq(MEMBER), org.mockito.ArgumentMatchers.argThat(
            context -> "agent_version".equals(context.resourceType())
                && Long.valueOf(88L).equals(context.resourceId())
                && "use".equals(context.action())
        ));
    }

    @Test
    void outsiderCannotCreateConversationAgainstAnotherProject() {
        AgentProject project = new AgentProject();
        project.setId(900L);
        project.setOwnerId(999L);
        project.setProjectKey("P-private");
        project.setStatus("active");
        when(projectMapper.selectProject(900L)).thenReturn(project);
        when(projectMapper.selectActiveMember(900L, MEMBER.id())).thenReturn(null);
        doAnswer(invocation -> {
            PermissionContext context = invocation.getArgument(1);
            if ("project".equals(context.resourceType()) && context.relations().isEmpty()) {
                throw new ServiceException("项目无权访问", HttpStatus.FORBIDDEN);
            }
            return null;
        }).when(authorizationEnforcer).requireAllowed(any(), any());

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.create(new CreateConversationRequest("private", 900L, null, null))
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getCode());
        assertEquals("项目不存在", exception.getMessage());
        verify(conversationMapper, never()).insert(any(AgentConversation.class));
    }

    @Test
    void missingProjectCannotBeBoundToConversation() {
        when(projectMapper.selectProject(902L)).thenReturn(null);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.create(new CreateConversationRequest("missing", 902L, null, null))
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getCode());
        assertEquals("项目不存在", exception.getMessage());
        verify(projectMapper, never()).selectActiveMember(any(), any());
        verify(conversationMapper, never()).insert(any(AgentConversation.class));
    }

    @Test
    void inactiveProjectIsHiddenFromOutsider() {
        AgentProject project = new AgentProject();
        project.setId(903L);
        project.setOwnerId(999L);
        project.setProjectKey("P-suspended-private");
        project.setStatus("suspended");
        when(projectMapper.selectProject(903L)).thenReturn(project);
        when(projectMapper.selectActiveMember(903L, MEMBER.id())).thenReturn(null);
        doAnswer(invocation -> {
            PermissionContext context = invocation.getArgument(1);
            if ("project".equals(context.resourceType()) && context.relations().isEmpty()) {
                throw new ServiceException("项目无权访问", HttpStatus.FORBIDDEN);
            }
            return null;
        }).when(authorizationEnforcer).requireAllowed(any(), any());

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.create(new CreateConversationRequest("suspended", 903L, null, null))
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getCode());
        assertEquals("项目不存在", exception.getMessage());
        verify(conversationMapper, never()).insert(any(AgentConversation.class));
    }

    @Test
    void archivedProjectCannotBeUsedForConversation() {
        AgentProject project = new AgentProject();
        project.setId(901L);
        project.setOwnerId(MEMBER.id());
        project.setProjectKey("P-archived");
        project.setStatus("archived");
        when(projectMapper.selectProject(901L)).thenReturn(project);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.create(new CreateConversationRequest("archived", 901L, null, null))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(conversationMapper, never()).insert(any(AgentConversation.class));
    }

    @Test
    void nulTitleIsRejectedBeforeConversationPersistence() {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.create(new CreateConversationRequest("unsafe\0title", null, null, null))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(conversationMapper, never()).insert(any(AgentConversation.class));
    }

    @Test
    void oversizedOrNulSearchIsRejectedBeforeDatabaseQuery() {
        ServiceException oversized = assertThrows(
            ServiceException.class,
            () -> service.list("x".repeat(256), 50)
        );
        ServiceException nul = assertThrows(
            ServiceException.class,
            () -> service.list("unsafe\0search", 50)
        );

        assertEquals(HttpStatus.BAD_REQUEST, oversized.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, nul.getCode());
        verify(conversationMapper, never()).searchOwnedConversations(any(), any(), anyInt());
    }

    @Test
    void ownerCanReadHistoryWithoutInternalMetadata() {
        AgentConversation conversation = ownedConversation(7L);
        ConversationMessageRow row = new ConversationMessageRow();
        row.setId(90L);
        row.setConversationId(7L);
        row.setSequenceNo(2);
        row.setRole("assistant");
        row.setContent("migrated answer");
        row.setStatus("completed");
        row.setTotalTokens(12);
        row.setCreatedAt(LocalDateTime.now());
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(conversation);
        when(conversationMapper.selectMessages(7L, 0, 200)).thenReturn(List.of(row));

        var messages = service.messages(7L, 0, 200);

        assertEquals(1, messages.size());
        assertEquals("migrated answer", messages.getFirst().content());
        assertEquals(2, messages.getFirst().sequenceNo());
        verify(authorizationEnforcer).requireAllowed(eq(MEMBER), org.mockito.ArgumentMatchers.argThat(
            context -> "conversation".equals(context.resourceType())
                && Long.valueOf(7L).equals(context.resourceId())
                && "view".equals(context.action())
        ));
    }

    @Test
    void crossUserHistoryIsHiddenBeforeMessageQuery() {
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(null);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.messages(7L, 0, 200)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getCode());
        verify(conversationMapper, never()).selectMessages(any(), anyInt(), anyInt());
    }

    @Test
    void crossUserConversationConversionReturnsNotFoundWithoutCreatingTask() {
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(null);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.convertToTask(7L, confirmationRequest("enterprise_shared", Map.of()))
        );

        assertEquals("会话不存在", exception.getMessage());
        verify(taskApplicationService, never()).createFromConversation(any(), any());
    }

    @Test
    void existingConversationConversionReplaysSameTask() {
        AgentConversation conversation = ownedConversation(7L);
        conversation.setTaskId(40L);
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(conversation);
        when(taskMapper.selectPlatformTaskById(40L)).thenReturn(existingTask(40L, 41L));

        TaskConversionResult result = service.convertToTask(
            7L, confirmationRequest("enterprise_shared", Map.of())
        );

        assertEquals(40L, result.taskId());
        assertEquals(41L, result.taskVersionId());
        assertTrue(result.replayed());
        verify(taskApplicationService, never()).createFromConversation(any(), any());
    }

    @Test
    void confirmedDraftDelegatesCompleteCreationAndLinksConversation() {
        AgentConversation conversation = ownedConversation(7L);
        TaskMutationResult mutation = mutation(100L, 101L, false);
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(conversation);
        when(conversationMapper.linkTaskIfAbsent(7L, 101L, 100L)).thenReturn(1);
        when(taskApplicationService.createFromConversation(eq(7L), any()))
            .thenReturn(mutation);

        TaskConversionResult result = service.convertToTask(
            7L, confirmationRequest("restricted", Map.of("selectedMessages", List.of(1L, 2L)))
        );

        assertEquals(100L, result.taskId());
        assertEquals(101L, result.taskVersionId());
        assertFalse(result.replayed());
        verify(taskApplicationService).createFromConversation(eq(7L), any());
        verify(conversationMapper).linkTaskIfAbsent(7L, 101L, 100L);
    }

    @Test
    void concurrentConversationLinkAcceptsSameWinningTask() {
        AgentConversation initial = ownedConversation(7L);
        AgentConversation refreshed = ownedConversation(7L);
        refreshed.setTaskId(100L);
        TaskMutationResult mutation = mutation(100L, 101L, true);
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(initial, refreshed);
        when(conversationMapper.linkTaskIfAbsent(7L, 101L, 100L)).thenReturn(0);
        when(taskApplicationService.createFromConversation(eq(7L), any()))
            .thenReturn(mutation);

        TaskConversionResult result = service.convertToTask(
            7L, confirmationRequest("enterprise_shared", Map.of())
        );

        assertTrue(result.replayed());
        assertEquals(100L, result.taskId());
    }

    @Test
    void conflictingConversationLinkIsRejected() {
        AgentConversation initial = ownedConversation(7L);
        AgentConversation refreshed = ownedConversation(7L);
        refreshed.setTaskId(999L);
        TaskMutationResult mutation = mutation(100L, 101L, false);
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(initial, refreshed);
        when(conversationMapper.linkTaskIfAbsent(7L, 101L, 100L)).thenReturn(0);
        when(taskApplicationService.createFromConversation(eq(7L), any()))
            .thenReturn(mutation);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.convertToTask(7L, confirmationRequest("enterprise_shared", Map.of()))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
    }

    @Test
    void taskDraftPreviewReturnsDirectlyConfirmablePayloadWithoutCreatingTask() {
        AgentConversation conversation = ownedConversation(7L);
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(conversation);
        when(taskMapper.selectBySourceConversationId(7L)).thenReturn(null);
        when(taskApplicationService.previewConversationDraftHash(eq(7L), any()))
            .thenReturn("a".repeat(64));

        var preview = service.previewTaskDraft(
            7L, draftRequest("enterprise_shared", Map.of("selectedMessages", List.of(1L)))
        );

        assertEquals(7L, preview.conversationId());
        assertEquals("a".repeat(64), preview.draftHash());
        assertEquals(preview.draftHash(), preview.draft().draftHash());
        assertTrue(preview.confirmationRequired());
        verify(taskApplicationService, never()).createFromConversation(any(), any());
    }

    @Test
    void alreadyConvertedConversationCannotBePreviewedAgain() {
        AgentConversation conversation = ownedConversation(7L);
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(conversation);
        when(taskMapper.selectBySourceConversationId(7L)).thenReturn(existingTask(100L, 101L));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.previewTaskDraft(7L, draftRequest("enterprise_shared", Map.of()))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(taskApplicationService, never()).previewConversationDraftHash(any(), any());
    }

    private AgentConversation ownedConversation(Long id) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(id);
        conversation.setUserId(101L);
        conversation.setVisibility("private");
        conversation.setStatus("active");
        return conversation;
    }

    private AgentTask existingTask(Long taskId, Long versionId) {
        AgentTask task = new AgentTask();
        task.setId(taskId);
        task.setCurrentVersionId(versionId);
        return task;
    }

    private TaskMutationResult mutation(Long taskId, Long versionId, boolean replayed) {
        TaskView task = mock(TaskView.class);
        when(task.id()).thenReturn(taskId);
        return new TaskMutationResult(task, versionId, replayed);
    }

    private ConvertConversationToTaskRequest confirmationRequest(
        String visibility,
        Map<String, Object> context
    ) {
        return draftRequest(visibility, context).withDraftHash("a".repeat(64));
    }

    private ConvertConversationToTaskRequest draftRequest(
        String visibility,
        Map<String, Object> context
    ) {
        return new ConvertConversationToTaskRequest(
            "idem-1", "First task", "Complete the requested work", null,
            null, 88L, null, visibility, "general", "single_agent", "L1_short_task",
            "R1", "human", 1, 1, LocalDateTime.now(), context, List.of(),
            Map.of("mode", "human"), Map.of(), Map.of(), Map.of(), List.of(), null
        );
    }
}
