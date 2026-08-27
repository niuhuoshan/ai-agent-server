package group.aitools.nhs.platform.canvas;

import group.aitools.nhs.platform.canvas.domain.AgentConversationCanvas;
import group.aitools.nhs.platform.canvas.domain.AgentConversationCanvasVersion;
import group.aitools.nhs.platform.canvas.mapper.ConversationCanvasMapper;
import group.aitools.nhs.platform.canvas.service.CanvasActionAuditService;
import group.aitools.nhs.platform.canvas.service.ConversationCanvasService;
import group.aitools.nhs.platform.canvas.web.CreateCanvasRequest;
import group.aitools.nhs.platform.canvas.web.RestoreCanvasVersionRequest;
import group.aitools.nhs.platform.canvas.web.SaveCanvasToWorkspaceRequest;
import group.aitools.nhs.platform.canvas.web.UpdateCanvasRequest;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.nhs.service.NhsWorkspaceService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ConversationCanvasServiceTest {

    private static final CurrentPrincipal OWNER = new CurrentPrincipal(
        101L, "owner", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        999L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );

    private CurrentPrincipalProvider principalProvider;
    private AgentConversationMapper conversationMapper;
    private ConversationCanvasMapper canvasMapper;
    private PlatformIdGenerator idGenerator;
    private CanvasActionAuditService auditService;
    private NhsWorkspaceService workspaceService;
    private ConversationCanvasService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        conversationMapper = mock(AgentConversationMapper.class);
        canvasMapper = mock(ConversationCanvasMapper.class);
        idGenerator = mock(PlatformIdGenerator.class);
        auditService = mock(CanvasActionAuditService.class);
        workspaceService = mock(NhsWorkspaceService.class);
        when(principalProvider.currentPrincipal()).thenReturn(OWNER);
        when(conversationMapper.selectOwnedConversation(7L, OWNER.id())).thenReturn(conversation());
        when(canvasMapper.insertCanvas(any())).thenReturn(1);
        when(canvasMapper.insertVersion(any())).thenReturn(1);
        service = new ConversationCanvasService(
            principalProvider, conversationMapper, canvasMapper, idGenerator,
            JsonMapper.builder().build(), auditService, workspaceService
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"markdown", "html", "code", "mermaid", "pdf", "csv", "image", "compare"})
    void createsEveryFrozenCanvasTypeWithAnImmutableFirstVersion(String contentType) {
        when(idGenerator.nextId()).thenReturn(501L, 601L);

        var result = service.create(7L, new CreateCanvasRequest(
            "Quarterly result", contentType, "content", Map.of(
                "fileName", "result.txt", "workspacePath", "reports/result.txt", "sourceMessageId", 88L
            )
        ));

        assertEquals(501L, result.id());
        assertEquals(1, result.currentVersion());
        assertEquals(1, result.revision());
        assertEquals(contentType, result.contentType());
        assertEquals("reports/result.txt", result.workspacePath());
        assertEquals(88L, result.sourceMessageId());
        ArgumentCaptor<AgentConversationCanvasVersion> version =
            ArgumentCaptor.forClass(AgentConversationCanvasVersion.class);
        verify(canvasMapper).insertVersion(version.capture());
        assertEquals("created", version.getValue().getChangeType());
        assertEquals(1, version.getValue().getVersionNo());
        assertEquals(64, version.getValue().getContentSha256().length());
        verify(auditService).record(
            OWNER, "create", 501L, "success", "completed", "conversationId=7,canvasId=501"
        );
    }

    @Test
    void updateAtomicallyAdvancesVersionAndNeverMutatesThePriorVersion() {
        AgentConversationCanvas current = canvas(1);
        when(canvasMapper.selectOwnedCanvas(7L, 501L, OWNER.id())).thenReturn(current);
        when(canvasMapper.advanceVersion(
            eq(7L), eq(501L), eq(OWNER.id()), eq(1), eq(2), any(), any(), any(),
            anyLong(), any(), any()
        )).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(602L);

        var result = service.update(7L, 501L, new UpdateCanvasRequest(
            1, "Updated", "markdown", "new body", Map.of("sourcePath", "draft.md")
        ));

        assertEquals(2, result.currentVersion());
        assertEquals("new body", result.content());
        ArgumentCaptor<AgentConversationCanvasVersion> version =
            ArgumentCaptor.forClass(AgentConversationCanvasVersion.class);
        verify(canvasMapper).insertVersion(version.capture());
        assertEquals(2, version.getValue().getVersionNo());
        assertEquals("updated", version.getValue().getChangeType());
        assertNull(version.getValue().getSourceVersionNo());
    }

    @Test
    void staleClientAndConcurrentAtomicUpdateBothReturnConflict() {
        when(canvasMapper.selectOwnedCanvas(7L, 501L, OWNER.id())).thenReturn(canvas(2));

        ServiceException stale = assertThrows(ServiceException.class, () -> service.update(
            7L, 501L, new UpdateCanvasRequest(1, "Stale", "markdown", "body", Map.of())
        ));
        assertEquals(HttpStatus.CONFLICT, stale.getCode());
        verify(canvasMapper, never()).advanceVersion(
            anyLong(), anyLong(), anyLong(), anyInt(), anyInt(), any(), any(), any(), anyLong(), any(), any()
        );

        AgentConversationCanvas first = canvas(1);
        when(canvasMapper.selectOwnedCanvas(7L, 501L, OWNER.id())).thenReturn(first);
        when(canvasMapper.advanceVersion(
            eq(7L), eq(501L), eq(OWNER.id()), eq(1), eq(2), any(), any(), any(),
            anyLong(), any(), any()
        )).thenReturn(0);
        ServiceException raced = assertThrows(ServiceException.class, () -> service.update(
            7L, 501L, new UpdateCanvasRequest(1, "Raced", "markdown", "body", Map.of())
        ));
        assertEquals(HttpStatus.CONFLICT, raced.getCode());
    }

    @Test
    void administratorStillCannotReadAnotherUsersPrivateConversationCanvas() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        when(conversationMapper.selectOwnedConversation(7L, ADMIN.id())).thenReturn(null);

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.get(7L, 501L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getCode());
        verify(canvasMapper, never()).selectOwnedCanvas(anyLong(), anyLong(), anyLong());
        verify(auditService).record(
            eq(ADMIN), eq("view"), eq(501L), eq("deny"), any(), eq("conversationId=7,canvasId=501")
        );
    }

    @Test
    void restoreAppendsANewVersionThatReferencesTheHistoricalSource() {
        AgentConversationCanvas current = canvas(2);
        AgentConversationCanvasVersion source = version(1, "old body");
        when(canvasMapper.selectOwnedCanvas(7L, 501L, OWNER.id())).thenReturn(current);
        when(canvasMapper.selectOwnedVersion(7L, 501L, OWNER.id(), 1)).thenReturn(source);
        when(canvasMapper.advanceVersion(
            eq(7L), eq(501L), eq(OWNER.id()), eq(2), eq(3), any(), any(), any(),
            anyLong(), any(), any()
        )).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(603L);

        var result = service.restore(7L, 501L, 1, new RestoreCanvasVersionRequest(2));

        assertEquals(3, result.currentVersion());
        assertEquals("old body", result.content());
        ArgumentCaptor<AgentConversationCanvasVersion> inserted =
            ArgumentCaptor.forClass(AgentConversationCanvasVersion.class);
        verify(canvasMapper).insertVersion(inserted.capture());
        assertEquals("restored", inserted.getValue().getChangeType());
        assertEquals(1, inserted.getValue().getSourceVersionNo());
    }

    @Test
    void deleteUsesTheSameOptimisticVersionBoundary() {
        when(canvasMapper.selectOwnedCanvas(7L, 501L, OWNER.id())).thenReturn(canvas(1));
        when(canvasMapper.softDelete(7L, 501L, OWNER.id(), 1)).thenReturn(1);

        service.delete(7L, 501L, 1);

        verify(canvasMapper).softDelete(7L, 501L, OWNER.id(), 1);
        verify(auditService).record(
            OWNER, "delete", 501L, "success", "completed",
            "conversationId=7,canvasId=501,expectedVersion=1"
        );
    }

    @Test
    void workspaceDefaultsToNoOverwriteAndSurfacesA409() {
        when(canvasMapper.selectOwnedCanvas(7L, 501L, OWNER.id())).thenReturn(canvas(1));
        when(workspaceService.writeCanvas("report.md", "body".getBytes(), false))
            .thenThrow(new ServiceException("同名", HttpStatus.CONFLICT));

        ServiceException exception = assertThrows(ServiceException.class, () ->
            service.saveToWorkspace(7L, 501L, new SaveCanvasToWorkspaceRequest(
                "report.md", false, 1
            ))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(auditService).record(eq(OWNER), eq("save_workspace"), eq(501L), eq("failure"), any(), any());
    }

    @Test
    void explicitWorkspaceOverwriteIsPassedThroughAndReported() {
        when(canvasMapper.selectOwnedCanvas(7L, 501L, OWNER.id())).thenReturn(canvas(1));
        when(workspaceService.writeCanvas("reports/report.md", "body".getBytes(), true))
            .thenReturn(Map.of("path", "reports/report.md", "size", 4L));

        var result = service.saveToWorkspace(7L, 501L, new SaveCanvasToWorkspaceRequest(
            "reports/report.md", true, 1
        ));

        assertTrue(result.overwritten());
        assertEquals("report.md", result.fileName());
        assertEquals(4, result.contentSize());
        verify(workspaceService).writeCanvas("reports/report.md", "body".getBytes(), true);
    }

    @Test
    void rejectsOversizedContentAndSensitiveOrInvalidMetadata() {
        ServiceException oversized = assertThrows(ServiceException.class, () -> service.create(
            7L, new CreateCanvasRequest("large", "markdown", "x".repeat(10 * 1024 * 1024 + 1), Map.of())
        ));
        ServiceException sensitive = assertThrows(ServiceException.class, () -> service.create(
            7L, new CreateCanvasRequest("secret", "code", "print(1)", Map.of("apiToken", "hidden"))
        ));
        ServiceException mime = assertThrows(ServiceException.class, () -> service.create(
            7L, new CreateCanvasRequest("image", "image", "image", Map.of("mimeType", "application/pdf"))
        ));

        assertEquals(HttpStatus.BAD_REQUEST, oversized.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, sensitive.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, mime.getCode());
        verify(canvasMapper, never()).insertCanvas(any());
    }

    @Test
    void listsVersionPayloadsOnlyAfterRecheckingConversationOwnership() {
        when(canvasMapper.selectOwnedCanvas(7L, 501L, OWNER.id())).thenReturn(canvas(2));
        when(canvasMapper.selectOwnedVersions(7L, 501L, OWNER.id(), 50))
            .thenReturn(List.of(version(2, "current"), version(1, "old")));

        var result = service.versions(7L, 501L, 50);

        assertEquals(List.of(2, 1), result.stream().map(value -> value.versionNo()).toList());
        assertEquals("current", result.getFirst().content());
    }

    private AgentConversation conversation() {
        AgentConversation value = new AgentConversation();
        value.setId(7L);
        value.setUserId(OWNER.id());
        value.setPrincipalType("human");
        value.setStatus("active");
        value.setDelFlag("0");
        return value;
    }

    private AgentConversationCanvas canvas(int version) {
        AgentConversationCanvas value = new AgentConversationCanvas();
        value.setId(501L);
        value.setConversationId(7L);
        value.setOwnerId(OWNER.id());
        value.setTitle("report");
        value.setCanvasType("markdown");
        value.setCurrentVersionNo(version);
        value.setRevisionNo(version);
        value.setMetadataJson("{}");
        value.setContent("body");
        value.setContentSize(4L);
        value.setContentSha256("a".repeat(64));
        value.setCreateBy(OWNER.id());
        value.setCreateTime(LocalDateTime.now().minusMinutes(1));
        value.setUpdateBy(OWNER.id());
        value.setUpdateTime(LocalDateTime.now());
        value.setDelFlag("0");
        return value;
    }

    private AgentConversationCanvasVersion version(int version, String content) {
        AgentConversationCanvasVersion value = new AgentConversationCanvasVersion();
        value.setId(600L + version);
        value.setCanvasId(501L);
        value.setVersionNo(version);
        value.setTitle("report");
        value.setCanvasType("markdown");
        value.setContent(content);
        value.setMetadataJson("{}");
        value.setContentSize((long) content.getBytes().length);
        value.setContentSha256("b".repeat(64));
        value.setChangeType(version == 1 ? "created" : "updated");
        value.setCreatedBy(OWNER.id());
        value.setCreatedAt(LocalDateTime.now());
        return value;
    }
}
