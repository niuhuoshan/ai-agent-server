package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentConversationAttachment;
import group.aitools.nhs.platform.conversation.service.ConversationAttachmentStorage;
import group.aitools.nhs.platform.embed.domain.EmbedSession;
import group.aitools.nhs.platform.embed.mapper.EmbedChatMapper;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.platform.knowledge.service.KnowledgeDocumentParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class EmbedAttachmentServiceTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    );

    @TempDir
    Path temporary;

    private EmbedChatMapper mapper;
    private PlatformIdGenerator ids;
    private ConversationAttachmentStorage storage;
    private AuthenticatedServiceAccount authenticated;
    private EmbedSession session;

    @BeforeEach
    void setUp() {
        mapper = mock(EmbedChatMapper.class);
        ids = mock(PlatformIdGenerator.class);
        storage = new ConversationAttachmentStorage(temporary);
        CurrentPrincipal principal = new CurrentPrincipal(
            20L, "embed-worker", PrincipalType.SERVICE_ACCOUNT,
            Set.of(PlatformRole.SERVICE_ACCOUNT)
        );
        authenticated = new AuthenticatedServiceAccount(
            principal, 10L, "embed-app", "embed", 30L, Set.of("chat:invoke")
        );
        session = new EmbedSession();
        session.setId(50L);
        session.setApplicationId(10L);
        session.setServiceAccountId(20L);
        session.setConversationId(70L);
        session.setStatus("active");
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        when(mapper.selectSession(50L)).thenReturn(session);
        when(mapper.insertAttachment(any())).thenReturn(1);
        when(ids.nextId()).thenReturn(90L);
    }

    @Test
    void utf8TextAndHostContextBecomeBoundedRuntimeInput() {
        EmbedAttachmentService service = service(new KnowledgeDocumentParser());
        AgentConversationAttachment attachment = upload(
            service, "notes.txt", "text/plain", "customer status: active".getBytes(StandardCharsets.UTF_8)
        );

        var request = service.prepareRequest(
            "summarize", List.of(attachment.getId()), Map.of("page", "customer-detail")
        );
        var prepared = service.prepare(authenticated, session, request);

        assertTrue(prepared.runtimeInput().contains("customer status: active"));
        assertTrue(prepared.runtimeInput().contains("customer-detail"));
        assertTrue(prepared.contentJson().contains("notes.txt"));
        assertTrue(prepared.media().isEmpty());
    }

    @Test
    void pdfTextIsExtractedThroughTheKnowledgeDocumentParser() {
        KnowledgeDocumentParser parser = mock(KnowledgeDocumentParser.class);
        when(parser.parse(any(InputStream.class), eq("manual.pdf"), eq("application/pdf")))
            .thenReturn(new KnowledgeDocumentParser.ParsedDocument(
                "installation procedure", "application/pdf", Map.of(), "test"
            ));
        EmbedAttachmentService service = service(parser);
        AgentConversationAttachment attachment = upload(
            service, "manual.pdf", "application/pdf",
            "%PDF-1.7\n1 0 obj\n%%EOF".getBytes(StandardCharsets.US_ASCII)
        );

        var prepared = service.prepare(
            authenticated, session,
            service.prepareRequest("explain", List.of(attachment.getId()), Map.of())
        );

        assertTrue(prepared.runtimeInput().contains("installation procedure"));
        verify(parser).parse(any(InputStream.class), eq("manual.pdf"), eq("application/pdf"));
    }

    @Test
    void imageBytesBecomeAgentScopeMediaWithoutEnteringTextContext() {
        EmbedAttachmentService service = service(new KnowledgeDocumentParser());
        AgentConversationAttachment attachment = upload(service, "pixel.png", "image/png", PNG);

        var prepared = service.prepare(
            authenticated, session,
            service.prepareRequest("describe", List.of(attachment.getId()), Map.of())
        );

        assertEquals(1, prepared.media().size());
        assertEquals("image/png", prepared.media().getFirst().mimeType());
        assertEquals(Base64.getEncoder().encodeToString(PNG), prepared.media().getFirst().base64());
        assertFalse(prepared.runtimeInput().contains(prepared.media().getFirst().base64()));
    }

    private EmbedAttachmentService service(KnowledgeDocumentParser parser) {
        return new EmbedAttachmentService(
            mapper, ids, storage, JsonMapper.builder().build(), parser
        );
    }

    private AgentConversationAttachment upload(
        EmbedAttachmentService service,
        String name,
        String mimeType,
        byte[] content
    ) {
        service.upload(authenticated, 50L, new MockMultipartFile(
            "file", name, mimeType, content
        ));
        ArgumentCaptor<AgentConversationAttachment> capture =
            ArgumentCaptor.forClass(AgentConversationAttachment.class);
        verify(mapper).insertAttachment(capture.capture());
        AgentConversationAttachment attachment = capture.getValue();
        when(mapper.selectAttachment(70L, attachment.getId(), 20L)).thenReturn(attachment);
        return attachment;
    }
}
