package group.aitools.nhs.platform.knowledge;

import group.aitools.nhs.platform.knowledge.mapper.KnowledgeCatalogMapper;
import group.aitools.nhs.platform.knowledge.persistence.row.KnowledgeParseJobRow;
import group.aitools.nhs.platform.knowledge.service.KnowledgeParsePersistenceService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class KnowledgeParsePersistenceServiceTest {

    @Test
    void allowsProcessingResumeOnlyWhenCallerMarksRecoveredLease() {
        KnowledgeCatalogMapper mapper = mock(KnowledgeCatalogMapper.class);
        KnowledgeParsePersistenceService service = new KnowledgeParsePersistenceService(mapper);
        when(mapper.markDocumentProcessing(eq(10L), eq(2L), eq(false), any())).thenReturn(0);
        when(mapper.markDocumentProcessing(eq(10L), eq(2L), eq(true), any())).thenReturn(1);

        assertFalse(service.start(10L, 2L, false));
        assertTrue(service.start(10L, 2L, true));
    }

    @Test
    void staleWorkerCannotResetDocumentAfterLosingJobLease() {
        KnowledgeCatalogMapper mapper = mock(KnowledgeCatalogMapper.class);
        KnowledgeParsePersistenceService service = new KnowledgeParsePersistenceService(mapper);
        KnowledgeParseJobRow job = job(2, 3);
        when(mapper.failParseJob(20L, "old-worker", "timeout")).thenReturn(0);

        service.fail(job, "old-worker", 10L, 2L, "timeout");

        verify(mapper, never()).retryDocument(any(), any(), any(), any());
        verify(mapper, never()).failDocument(any(), any(), any(), any());
    }

    @Test
    void renewFailsClosedAfterLeaseOwnershipChanges() {
        KnowledgeCatalogMapper mapper = mock(KnowledgeCatalogMapper.class);
        KnowledgeParsePersistenceService service = new KnowledgeParsePersistenceService(mapper);
        KnowledgeParseJobRow job = job(1, 3);
        when(mapper.renewParseJob(20L, "worker-1")).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.renew(job, "worker-1"));
    }

    private KnowledgeParseJobRow job(int attempt, int maxAttempts) {
        KnowledgeParseJobRow job = new KnowledgeParseJobRow();
        job.setId(20L);
        job.setAttemptNo(attempt);
        job.setMaxAttempts(maxAttempts);
        return job;
    }
}
