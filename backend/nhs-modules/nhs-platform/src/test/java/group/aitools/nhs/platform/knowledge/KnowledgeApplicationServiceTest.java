package group.aitools.nhs.platform.knowledge;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeBase;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeChunk;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDirectory;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDocument;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeCatalogMapper;
import group.aitools.nhs.platform.knowledge.service.KnowledgeApplicationService;
import group.aitools.nhs.platform.knowledge.service.KnowledgeAuthorizationContextFactory;
import group.aitools.nhs.platform.knowledge.service.KnowledgeFileStorage;
import group.aitools.nhs.platform.knowledge.service.KnowledgeOperationAuditService;
import group.aitools.nhs.platform.knowledge.web.CreateKnowledgeDirectoryRequest;
import group.aitools.nhs.platform.knowledge.web.UpdateKnowledgeDirectoryRequest;
import group.aitools.nhs.platform.knowledge.web.UpdateKnowledgeDocumentRequest;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class KnowledgeApplicationServiceTest {

    private KnowledgeCatalogMapper mapper;
    private KnowledgeFileStorage storage;
    private KnowledgeOperationAuditService operationAudit;
    private KnowledgeApplicationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(KnowledgeCatalogMapper.class);
        storage = mock(KnowledgeFileStorage.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        when(ids.nextId()).thenReturn(2001L);
        operationAudit = mock(KnowledgeOperationAuditService.class);
        CurrentPrincipal principal = new CurrentPrincipal(
            101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        );
        service = new KnowledgeApplicationService(
            () -> principal,
            mock(AuthorizationEnforcer.class),
            mock(KnowledgeAuthorizationContextFactory.class),
            ids,
            mapper,
            storage,
            mock(AgentModelMapper.class),
            JsonMapper.builder().build(),
            operationAudit
        );
        AgentKnowledgeBase base = new AgentKnowledgeBase();
        base.setId(1001L);
        base.setStatus("active");
        base.setProviderType("postgres_pgvector");
        when(mapper.selectBaseById(1001L)).thenReturn(base);
    }

    @Test
    void duplicateUploadDeletesStoredFileExactlyOnce() {
        byte[] bytes = "same content".getBytes(StandardCharsets.UTF_8);
        var file = new MockMultipartFile("file", "policy.txt", "text/plain", bytes);
        var stored = new KnowledgeFileStorage.StoredFile(
            "2001/source.bin", bytes.length, "a".repeat(64)
        );
        when(storage.put(org.mockito.ArgumentMatchers.eq(2001L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq((long) bytes.length)))
            .thenReturn(stored);
        when(mapper.selectDuplicateDocument(1001L, stored.sha256())).thenReturn(999L);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.upload(1001L, file)
        );

        assertEquals(409, exception.getCode());
        verify(storage).delete("2001/source.bin");
    }

    @Test
    void downloadsAnAuthorizedLocalDocumentStream() throws Exception {
        AgentKnowledgeDocument document = new AgentKnowledgeDocument();
        document.setId(2001L);
        document.setKnowledgeBaseId(1001L);
        document.setName("policy.pdf");
        document.setStorageType("local");
        document.setStorageRef("2001/source.bin");
        document.setMimeType("application/pdf");
        document.setSizeBytes(3L);
        when(mapper.selectDocumentById(2001L)).thenReturn(document);
        InputStream input = new ByteArrayInputStream(new byte[] {1, 2, 3});
        when(storage.open("2001/source.bin")).thenReturn(input);

        var download = service.download(1001L, 2001L);

        assertEquals(document, download.document());
        assertEquals(1, download.input().read());
        verify(storage).open("2001/source.bin");
    }

    @Test
    void doesNotOpenADocumentOwnedByAnotherKnowledgeBase() {
        AgentKnowledgeDocument document = document(2001L);
        document.setKnowledgeBaseId(9999L);
        when(mapper.selectDocumentById(2001L)).thenReturn(document);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.download(1001L, 2001L)
        );

        assertEquals(404, exception.getCode());
        verify(storage, never()).open(anyString());
    }

    @Test
    void mapsOnlyTheRequestedDocumentChunksAndKeepsPaginationBounded() {
        AgentKnowledgeChunk chunk = new AgentKnowledgeChunk();
        chunk.setId(3001L);
        chunk.setKnowledgeBaseId(1001L);
        chunk.setDocumentId(2001L);
        chunk.setChunkNo(0);
        chunk.setContent("retention policy");
        chunk.setContentHash("b".repeat(64));
        chunk.setTokenCount(2);
        chunk.setStatus("active");
        chunk.setMetadataJson("{}");
        when(mapper.selectDocumentById(2001L)).thenReturn(document(2001L));
        when(mapper.selectChunks(2001L, 20, 10)).thenReturn(List.of(chunk));

        var result = service.chunks(1001L, 2001L, 20, 10);

        assertEquals(1, result.size());
        assertEquals("retention policy", result.getFirst().content());
        assertEquals(2, result.getFirst().tokenCount());
        verify(mapper).selectChunks(2001L, 20, 10);
    }

    @Test
    void rejectsAnInvalidChunkPageBeforeQueryingStorage() {
        when(mapper.selectDocumentById(2001L)).thenReturn(document(2001L));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.chunks(1001L, 2001L, 0, 201)
        );

        assertEquals(400, exception.getCode());
        verify(mapper, never()).selectChunks(anyLong(), anyInt(), anyInt());
    }

    @Test
    void createsRootDirectoryAndWritesContentFreeAudit() {
        AgentKnowledgeDirectory directory = new AgentKnowledgeDirectory();
        directory.setId(2001L);
        directory.setKnowledgeBaseId(1001L);
        directory.setName("Policies");
        directory.setRevisionNo(1L);
        when(mapper.selectDirectoryNameConflict(1001L, null, "Policies", null)).thenReturn(null);
        when(mapper.selectDirectoryById(2001L)).thenReturn(directory);

        var result = service.createDirectory(
            1001L, new CreateKnowledgeDirectoryRequest("Policies", null)
        );

        assertEquals("Policies", result.name());
        verify(mapper).insertDirectory(org.mockito.ArgumentMatchers.any(AgentKnowledgeDirectory.class));
        verify(operationAudit).record(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("knowledge_directory_create"),
            org.mockito.ArgumentMatchers.eq("knowledge_directory"),
            org.mockito.ArgumentMatchers.eq(2001L),
            org.mockito.ArgumentMatchers.contains("baseId=1001")
        );
    }

    @Test
    void rejectsMovingDirectoryIntoItsDescendant() {
        AgentKnowledgeDirectory current = directory(2001L, 1001L, 3001L, "Policies", 1L);
        AgentKnowledgeDirectory descendant = directory(3001L, 1001L, 2001L, "Archive", 1L);
        when(mapper.selectDirectoryById(2001L)).thenReturn(current);
        when(mapper.selectDirectoryById(3001L)).thenReturn(descendant);
        when(mapper.candidateParentContainsDirectory(1001L, 3001L, 2001L)).thenReturn(true);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.updateDirectory(
                1001L, 2001L, new UpdateKnowledgeDirectoryRequest(1L, null, 3001L)
            )
        );

        assertEquals(409, exception.getCode());
        verify(mapper, never()).updateDirectory(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updatesDocumentCatalogWithoutChangingParseRevision() {
        AgentKnowledgeDirectory directory = directory(3001L, 1001L, null, "Policies", 1L);
        AgentKnowledgeDocument document = document(2001L);
        document.setCatalogRevisionNo(1L);
        document.setTagsJson("[]");
        document.setRemark(null);
        when(mapper.selectDirectoryById(3001L)).thenReturn(directory);
        when(mapper.selectDocumentById(2001L)).thenReturn(document);
        when(mapper.updateDocumentCatalog(document)).thenAnswer(invocation -> {
            document.setCatalogRevisionNo(document.getCatalogRevisionNo() + 1);
            return 1;
        });

        var result = service.updateDocument(
            1001L, 2001L,
            new UpdateKnowledgeDocumentRequest(1L, "renamed.txt", 3001L, List.of("policy"), "internal")
        );

        assertEquals("renamed.txt", result.name());
        assertEquals(1L, result.revision());
        assertEquals(2L, result.catalogRevision());
        verify(mapper).updateDocumentCatalog(document);
        verify(operationAudit).record(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("knowledge_document_update"),
            org.mockito.ArgumentMatchers.eq("knowledge_document"),
            org.mockito.ArgumentMatchers.eq(2001L),
            org.mockito.ArgumentMatchers.contains("directoryId=3001")
        );
    }

    @Test
    void explicitNullDirectoryMovesDocumentToRoot() {
        AgentKnowledgeDocument document = document(2001L);
        document.setDirectoryId(3001L);
        document.setCatalogRevisionNo(3L);
        document.setTagsJson("[]");
        when(mapper.selectDirectoryById(3001L)).thenReturn(directory(3001L, 1001L, null, "Policies", 1L));
        when(mapper.selectDocumentById(2001L)).thenReturn(document);
        when(mapper.updateDocumentCatalog(document)).thenAnswer(invocation -> {
            document.setCatalogRevisionNo(document.getCatalogRevisionNo() + 1);
            return 1;
        });
        UpdateKnowledgeDocumentRequest request = new UpdateKnowledgeDocumentRequest();
        request.setExpectedRevision(3L);
        request.setDirectoryId(null);

        service.updateDocument(1001L, 2001L, request);

        assertEquals(null, document.getDirectoryId());
        verify(mapper).updateDocumentCatalog(document);
    }

    @Test
    void rejectsDuplicateDocumentTagsBeforeWriting() {
        AgentKnowledgeDocument document = document(2001L);
        document.setCatalogRevisionNo(1L);
        document.setTagsJson("[]");
        when(mapper.selectDocumentById(2001L)).thenReturn(document);
        UpdateKnowledgeDocumentRequest request = new UpdateKnowledgeDocumentRequest();
        request.setExpectedRevision(1L);
        request.setTags(List.of("Policy", "policy"));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.updateDocument(1001L, 2001L, request)
        );

        assertEquals(400, exception.getCode());
        verify(mapper, never()).updateDocumentCatalog(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refusesToDeleteNonEmptyDirectory() {
        when(mapper.selectDirectoryById(2001L)).thenReturn(directory(2001L, 1001L, null, "Policies", 4L));
        when(mapper.countDirectoryEntries(1001L, 2001L)).thenReturn(1L);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.deleteDirectory(1001L, 2001L, 4L)
        );

        assertEquals(409, exception.getCode());
        verify(mapper, never()).softDeleteDirectory(
            anyLong(), anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.any()
        );
    }

    private AgentKnowledgeDirectory directory(
        Long id, Long baseId, Long parentId, String name, Long revision
    ) {
        AgentKnowledgeDirectory value = new AgentKnowledgeDirectory();
        value.setId(id);
        value.setKnowledgeBaseId(baseId);
        value.setParentId(parentId);
        value.setName(name);
        value.setRevisionNo(revision);
        return value;
    }

    private AgentKnowledgeDocument document(Long id) {
        AgentKnowledgeDocument document = new AgentKnowledgeDocument();
        document.setId(id);
        document.setKnowledgeBaseId(1001L);
        document.setName("policy.txt");
        document.setStorageType("local");
        document.setStorageRef(id + "/source.bin");
        document.setMimeType("text/plain");
        document.setMetadataJson("{}");
        document.setRevisionNo(1L);
        return document;
    }
}
