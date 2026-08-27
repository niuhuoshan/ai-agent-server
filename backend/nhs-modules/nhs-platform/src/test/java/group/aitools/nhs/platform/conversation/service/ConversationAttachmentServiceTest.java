package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.domain.AgentConversationAttachment;
import group.aitools.nhs.platform.conversation.mapper.ConversationTurnMapper;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ConversationAttachmentServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    @TempDir
    Path temporary;

    private CurrentPrincipalProvider principals;
    private ConversationTurnMapper mapper;
    private ConversationAttachmentService service;

    @BeforeEach
    void setUp() {
        principals = mock(CurrentPrincipalProvider.class);
        AuthorizationEnforcer authorization = mock(AuthorizationEnforcer.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        mapper = mock(ConversationTurnMapper.class);
        when(principals.currentPrincipal()).thenReturn(MEMBER);
        when(ids.nextId()).thenReturn(500L);
        when(mapper.lockOwnedActiveConversation(7L, 101L)).thenReturn(conversation());
        when(mapper.selectOwnedActiveConversation(7L, 101L)).thenReturn(conversation());
        when(mapper.insertAttachment(any())).thenReturn(1);
        service = new ConversationAttachmentService(
            principals, authorization, ids, mapper,
            new ConversationAttachmentStorage(temporary)
        );
    }

    @Test
    void validUtf8TextIsStoredWithDetectedMimeAndHash() {
        var result = service.upload(7L, new MockMultipartFile(
            "file", "notes.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)
        ));

        assertEquals("text/plain", result.mimeType());
        assertEquals(64, result.sha256().length());
        verify(mapper).insertAttachment(any());
    }

    @Test
    void pathLikeFilenameIsRejectedBeforePersistence() {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.upload(7L, new MockMultipartFile(
                "file", "../secret.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8)
            ))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(mapper, never()).insertAttachment(any());
    }

    @Test
    void declaredMimeCannotDisguisePdfAsImage() {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.upload(7L, new MockMultipartFile(
                "file", "report.pdf", "image/png", "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII)
            ))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(mapper, never()).insertAttachment(any());
    }

    @Test
    void textAttachmentWithNulIsRejected() {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.upload(7L, new MockMultipartFile(
                "file", "notes.txt", "text/plain", new byte[] {'a', 0, 'b'}
            ))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
    }

    @Test
    void oversizedDeclaredUploadIsRejectedWithoutWritingMetadata() {
        MockMultipartFile file = mock(MockMultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(ConversationAttachmentService.MAX_UPLOAD_BYTES + 1);

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.upload(7L, file)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(mapper, never()).insertAttachment(any());
    }

    @Test
    void anotherUsersConversationIsHiddenBeforeFileRead() {
        when(mapper.lockOwnedActiveConversation(7L, 101L)).thenReturn(null);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.upload(7L, new MockMultipartFile(
                "file", "notes.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8)
            ))
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getCode());
        verify(mapper, never()).insertAttachment(any());
    }

    @Test
    void oneAttachmentIdCannotBeRepeatedWithinATurn() {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.requireReady(7L, 101L, List.of(9L, 9L))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(mapper, never()).selectOwnedAttachment(any(), any(), any());
    }

    @Test
    void listingAttachmentsUsesNonLockingOwnershipLookup() {
        when(mapper.selectOwnedAttachments(7L, 101L, 20)).thenReturn(List.of());

        assertTrue(service.list(7L, 20).isEmpty());

        verify(mapper).selectOwnedActiveConversation(7L, 101L);
        verify(mapper, never()).lockOwnedActiveConversation(7L, 101L);
    }

    @Test
    void verifiedImageIsPreparedAsEphemeralRuntimeMedia() {
        byte[] png = java.util.HexFormat.of().parseHex("89504e470d0a1a0a00000000");
        var uploaded = service.upload(7L, new MockMultipartFile(
            "file", "chart.png", "image/png", png
        ));
        AgentConversationAttachment attachment = new AgentConversationAttachment();
        attachment.setId(uploaded.id());
        attachment.setMimeType(uploaded.mimeType());
        attachment.setSizeBytes(uploaded.sizeBytes());
        attachment.setSha256(uploaded.sha256());
        attachment.setStorageRef("500/source.bin");

        var media = service.runtimeMedia(List.of(attachment));

        assertEquals(1, media.size());
        assertEquals("image/png", media.getFirst().get("mimeType"));
        assertEquals(Base64.getEncoder().encodeToString(png), media.getFirst().get("base64"));
    }

    private AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(7L);
        conversation.setUserId(101L);
        conversation.setStatus("active");
        return conversation;
    }
}
