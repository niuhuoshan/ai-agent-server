package group.aitools.nhs.platform.sandbox;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.sandbox.mapper.SandboxRunnerMapper;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobOutputRow;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobRow;
import group.aitools.nhs.platform.sandbox.service.ChatCodeExecutionService;
import group.aitools.nhs.platform.sandbox.web.ChatCodeExecutionRequest;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class ChatCodeExecutionServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private AgentConversationMapper conversationMapper;
    private SandboxRunnerMapper sandboxMapper;
    private PlatformIdGenerator idGenerator;
    private ChatCodeExecutionService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        conversationMapper = mock(AgentConversationMapper.class);
        sandboxMapper = mock(SandboxRunnerMapper.class);
        idGenerator = mock(PlatformIdGenerator.class);
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
        service = new ChatCodeExecutionService(
            principalProvider, authorizationEnforcer, conversationMapper,
            sandboxMapper, idGenerator, JsonMapper.builder().findAndAddModules().build()
        );
    }

    @Test
    void submitsNormalizedScriptOnlyWhenOwnedConversationAndRunnerAreAvailable() {
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(conversation(7L));
        when(sandboxMapper.countAvailableRunners(eq("code"), any())).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(900L);
        when(idGenerator.nextUuid()).thenReturn("trace-seed");
        when(sandboxMapper.insertChatCodeJob(
            anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(),
            anyInt(), anyInt(), anyInt(), anyInt(), any()
        )).thenReturn(1);
        when(sandboxMapper.selectChatJobOwnedByUser(900L, 101L))
            .thenReturn(job(900L, 7L, "queued"));

        var result = service.submit(new ChatCodeExecutionRequest(
            "python3", "print('ok')", "7", null
        ));

        assertEquals("900", result.executionId());
        assertEquals("python", result.language());
        verify(sandboxMapper).insertChatCodeJob(
            eq(900L), eq(101L), eq(7L), anyString(), anyString(), eq("code"),
            eq("python"), eq("print('ok')"), eq("[\"__chat_code__\"]"), eq("."),
            eq(60), eq(512), eq(1000), eq(128), eq(102400), eq(10), any()
        );
    }

    @Test
    void returnsExplicitUnavailableInsteadOfQueuingWithoutRunner() {
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(conversation(7L));
        when(sandboxMapper.countAvailableRunners(eq("code"), any())).thenReturn(0);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.submit(
            new ChatCodeExecutionRequest("bash", "echo ok", "7", null)
        ));

        assertEquals(503, exception.getCode());
        verify(sandboxMapper, never()).insertChatCodeJob(
            anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(),
            anyInt(), anyInt(), anyInt(), anyInt(), any()
        );
    }

    @Test
    void crossUserConversationIsHiddenBeforeRunnerLookup() {
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(null);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.submit(
            new ChatCodeExecutionRequest("python", "print(1)", "7", null)
        ));

        assertEquals(404, exception.getCode());
        verify(sandboxMapper, never()).countAvailableRunners(anyString(), any());
    }

    @Test
    void publicSubmissionRejectsClientControlledSkillManifestBeforeQueuing() {
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(conversation(7L));
        String manifest = "{\"version\":1,\"workspaceKey\":\"run-7\",\"skills\":["
            + "{\"skillId\":10,\"versionId\":11,\"skillKey\":\"private-skill\","
            + "\"fileBundleHash\":\"" + "a".repeat(64) + "\"}]}";

        ServiceException exception = assertThrows(ServiceException.class, () -> service.submit(
            new ChatCodeExecutionRequest(
                "python", "print(1)", "7", null, "run-7", manifest
            )
        ));

        assertEquals(400, exception.getCode());
        verifyNoInteractions(sandboxMapper);
    }

    @Test
    void runtimeSubmissionAcceptsServerFrozenSkillManifest() {
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(conversation(7L));
        when(sandboxMapper.countAvailableRunners(eq("code"), any())).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(901L);
        when(idGenerator.nextUuid()).thenReturn("runtime-trace-seed");
        when(sandboxMapper.insertChatCodeJobWithManifest(
            anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any()
        )).thenReturn(1);
        when(sandboxMapper.selectChatJobOwnedByUser(901L, 101L))
            .thenReturn(job(901L, 7L, "queued"));
        String manifest = "{\"version\":1,\"workspaceKey\":\"run-7\",\"skills\":["
            + "{\"skillId\":10,\"versionId\":11,\"skillKey\":\"reviewer\","
            + "\"fileBundleHash\":\"" + "b".repeat(64) + "\"}]}";

        var result = service.submitRuntime(new ChatCodeExecutionRequest(
            "bash", "echo ok", "7", null, "run-7", manifest
        ));

        assertEquals("901", result.executionId());
        verify(sandboxMapper).insertChatCodeJobWithManifest(
            eq(901L), eq(101L), eq(7L), anyString(), anyString(), eq("code"),
            eq("bash"), eq("echo ok"), eq("[\"__chat_code__\"]"), eq("."),
            eq("run-7"), anyString(), anyString(), eq(60), eq(512), eq(1000),
            eq(128), eq(102400), eq(10), any()
        );
    }

    @Test
    void cancellationIsBoundToBothOwnerAndConversation() {
        SandboxJobRow running = job(900L, 7L, "running");
        SandboxJobRow cancelled = job(900L, 7L, "cancelled");
        when(sandboxMapper.selectOwnedChatJob(900L, 101L, 7L))
            .thenReturn(running, cancelled);
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(conversation(7L));
        when(sandboxMapper.cancelOwnedChatJob(eq(900L), eq(101L), eq(7L), any()))
            .thenReturn(1);

        var result = service.cancel(900L, "7");

        assertEquals("cancelled", result.status());
        verify(sandboxMapper).cancelOwnedChatJob(eq(900L), eq(101L), eq(7L), any());
    }

    @Test
    void globalCancellationConsumesEveryOwnedConversationJobLease() {
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(conversation(7L));
        when(sandboxMapper.cancelOwnedChatJobs(
            eq(101L), eq(7L), eq("stop all"), any()
        )).thenReturn(3);

        int stopped = service.cancelAllForConversation(7L, "stop all");

        assertEquals(3, stopped);
        verify(sandboxMapper).cancelOwnedChatJobs(
            eq(101L), eq(7L), eq("stop all"), any()
        );
    }

    @Test
    void globalCancellationChecksConversationOwnershipBeforeBulkUpdate() {
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(null);

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.cancelAllForConversation(7L, null)
        );

        assertEquals(404, exception.getCode());
        verify(sandboxMapper, never()).cancelOwnedChatJobs(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void streamReaderReturnsOnlyPersistedOwnerOutputAfterCursor() {
        SandboxJobRow running = job(900L, 7L, "running");
        SandboxJobOutputRow output = new SandboxJobOutputRow();
        output.setJobId(900L);
        output.setSequenceNo(3L);
        output.setStream("stdout");
        output.setContent("hello");
        when(sandboxMapper.selectChatJobOwnedByUser(900L, 101L)).thenReturn(running);
        when(sandboxMapper.selectOwnedChatJob(900L, 101L, 7L)).thenReturn(running);
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(conversation(7L));
        when(sandboxMapper.selectOutputs(900L, 2L, 20)).thenReturn(List.of(output));

        var batch = service.reader(900L, "7").read(2L, 20);

        assertEquals(1, batch.outputs().size());
        assertEquals("hello", batch.outputs().getFirst().getContent());
    }

    @Test
    void listsOnlyOwnedConversationExecutionsNewestFirstWithBoundedLimit() {
        SandboxJobRow newest = job(902L, 7L, "running");
        SandboxJobRow older = job(901L, 7L, "succeeded");
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(conversation(7L));
        when(sandboxMapper.selectOwnedChatJobs(101L, 7L, 50))
            .thenReturn(List.of(newest, older));

        var result = service.list("7", 500);

        assertEquals(List.of("902", "901"), result.stream()
            .map(value -> value.executionId()).toList());
        verify(sandboxMapper).selectOwnedChatJobs(101L, 7L, 50);
    }

    @Test
    void listHidesAnotherUsersConversationBeforeReadingExecutions() {
        when(conversationMapper.selectOwnedConversation(7L, 101L)).thenReturn(null);

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.list("7", 20)
        );

        assertEquals(404, exception.getCode());
        verify(sandboxMapper, never()).selectOwnedChatJobs(anyLong(), anyLong(), anyInt());
    }

    private AgentConversation conversation(Long id) {
        AgentConversation value = new AgentConversation();
        value.setId(id);
        value.setUserId(101L);
        return value;
    }

    private SandboxJobRow job(Long id, Long conversationId, String status) {
        SandboxJobRow row = new SandboxJobRow();
        row.setId(id);
        row.setSourceType("chat_code");
        row.setOwnerUserId(101L);
        row.setConversationId(conversationId);
        row.setTraceId("a".repeat(64));
        row.setScriptLanguage("python");
        row.setStatus(status);
        row.setOutputBytes(0);
        row.setOutputSequence(0L);
        row.setOutputTruncated(false);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }
}
