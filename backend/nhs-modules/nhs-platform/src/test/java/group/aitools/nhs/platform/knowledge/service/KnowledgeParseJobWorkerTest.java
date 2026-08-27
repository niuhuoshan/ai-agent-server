package group.aitools.nhs.platform.knowledge.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeBase;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDocument;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeCatalogMapper;
import group.aitools.nhs.platform.knowledge.persistence.row.KnowledgeParseJobRow;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class KnowledgeParseJobWorkerTest {

    @Test
    void resumesRecoveredProcessingRevisionAndPersistsRebuiltChunks() {
        KnowledgeParsePersistenceService persistence = mock(KnowledgeParsePersistenceService.class);
        KnowledgeCatalogMapper mapper = mock(KnowledgeCatalogMapper.class);
        KnowledgeFileStorage storage = mock(KnowledgeFileStorage.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        when(ids.nextId()).thenReturn(3001L, 3002L);
        KnowledgeParseJobRow job = job(
            "{\"knowledgeBaseId\":1001,\"documentId\":2001,\"revision\":2}"
        );
        job.setRecovered(true);
        AgentKnowledgeBase base = new AgentKnowledgeBase();
        base.setId(1001L);
        base.setStatus("active");
        base.setConfigJson("{\"chunkSize\":200,\"chunkOverlap\":20}");
        AgentKnowledgeDocument document = new AgentKnowledgeDocument();
        document.setId(2001L);
        document.setKnowledgeBaseId(1001L);
        document.setRevisionNo(2L);
        document.setStatus("processing");
        document.setStorageType("local");
        document.setStorageRef("2001/source.bin");
        document.setName("policy.txt");
        document.setMimeType("text/plain");
        when(mapper.selectDocumentById(2001L)).thenReturn(document);
        when(mapper.selectBaseById(1001L)).thenReturn(base);
        when(persistence.start(2001L, 2L, true)).thenReturn(true);
        when(storage.open("2001/source.bin")).thenAnswer(ignored -> new ByteArrayInputStream(
            "Approved policy text for grounded retrieval.".getBytes(StandardCharsets.UTF_8)
        ));
        KnowledgeParseJobWorker worker = new KnowledgeParseJobWorker(
            persistence,
            mapper,
            storage,
            new KnowledgeDocumentParser(),
            new KnowledgeChunker(),
            mock(AgentModelMapper.class),
            mock(KnowledgeEmbeddingClient.class),
            ids,
            JsonMapper.builder().build()
        );

        worker.process(job);

        verify(persistence, atLeastOnce()).renew(eq(job), anyString());
        ArgumentCaptor<List> chunks = ArgumentCaptor.forClass(List.class);
        verify(persistence).complete(
            eq(job), anyString(), eq(document), anyString(), anyString(), chunks.capture()
        );
        assertFalse(chunks.getValue().isEmpty());
    }

    @Test
    void invalidPayloadFailsOnlyClaimedJobWithoutGuessingDocument() {
        KnowledgeParsePersistenceService persistence = mock(KnowledgeParsePersistenceService.class);
        KnowledgeCatalogMapper mapper = mock(KnowledgeCatalogMapper.class);
        KnowledgeParseJobRow job = job("{}");
        KnowledgeParseJobWorker worker = new KnowledgeParseJobWorker(
            persistence,
            mapper,
            mock(KnowledgeFileStorage.class),
            new KnowledgeDocumentParser(),
            new KnowledgeChunker(),
            mock(AgentModelMapper.class),
            mock(KnowledgeEmbeddingClient.class),
            mock(PlatformIdGenerator.class),
            JsonMapper.builder().build()
        );

        worker.process(job);

        verify(mapper).failParseJob(eq(9001L), anyString(), anyString());
        verify(persistence, never()).fail(any(), anyString(), any(), any(), anyString());
    }

    private KnowledgeParseJobRow job(String payload) {
        KnowledgeParseJobRow job = new KnowledgeParseJobRow();
        job.setId(9001L);
        job.setPayloadJson(payload);
        job.setAttemptNo(1);
        job.setMaxAttempts(3);
        return job;
    }
}
