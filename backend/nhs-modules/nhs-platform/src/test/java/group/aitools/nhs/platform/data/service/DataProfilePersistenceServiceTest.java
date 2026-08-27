package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataProfileJob;
import group.aitools.nhs.platform.data.domain.AgentDataProfileJobTable;
import group.aitools.nhs.platform.data.mapper.DataGovernanceMapper;
import group.aitools.nhs.platform.data.mapper.DataProfileMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class DataProfilePersistenceServiceTest {

    private static final String STALE_WORKER = "profile-worker-stale";

    private final PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
    private final DataProfileMapper mapper = mock(DataProfileMapper.class);
    private final DataGovernanceMapper governanceMapper = mock(DataGovernanceMapper.class);
    private final DataProfilePersistenceService service = new DataProfilePersistenceService(
        idGenerator, mapper, governanceMapper, JsonMapper.builder().build()
    );

    @Test
    void staleWorkerCannotResetCancelledRunningTable() {
        AgentDataProfileJob job = job();
        AgentDataProfileJobTable item = item();
        when(mapper.resetJobTable(job.getId(), item.getId(), STALE_WORKER)).thenReturn(0);

        assertThrows(IllegalStateException.class, () ->
            service.resetAndFinishCancelled(job, item, STALE_WORKER)
        );

        verify(mapper, never()).finishJob(anyLong(), anyString(), any());
        verifyNoInteractions(governanceMapper);
    }

    @Test
    void staleWorkerCannotFailRunningTableOrRefreshProgress() {
        AgentDataProfileJob job = job();
        AgentDataProfileJobTable item = item();
        when(mapper.failJobTable(
            eq(job.getId()), eq(item.getId()), eq(STALE_WORKER), eq("provider error"), any()
        )).thenReturn(0);

        service.failTable(job, item, STALE_WORKER, "provider error");

        verify(mapper, never()).refreshJobProgress(anyLong(), anyString());
        verifyNoInteractions(governanceMapper);
    }

    @Test
    void staleWorkerCannotFinishJob() {
        AgentDataProfileJob job = job();
        when(mapper.selectClaimedJob(job.getId())).thenReturn(job);
        when(mapper.finishJob(anyLong(), anyString(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.finish(job, STALE_WORKER));

        verifyNoInteractions(governanceMapper);
    }

    @Test
    void staleWorkerCannotFailJobOrWriteAudit() {
        AgentDataProfileJob job = job();
        when(mapper.selectClaimedJob(job.getId())).thenReturn(job);
        when(mapper.failRunningJobTables(anyLong(), anyString(), anyString(), any())).thenReturn(0);
        when(mapper.refreshJobProgress(anyLong(), anyString())).thenReturn(0);
        when(mapper.failJob(anyLong(), anyString(), anyString(), any())).thenReturn(0);

        service.failJob(job, STALE_WORKER, "provider error");

        verify(mapper).failRunningJobTables(
            eq(job.getId()), eq(STALE_WORKER), eq("provider error"), any()
        );
        verify(mapper).refreshJobProgress(job.getId(), STALE_WORKER);
        verify(mapper).failJob(eq(job.getId()), eq(STALE_WORKER), eq("provider error"), any());
        verifyNoInteractions(governanceMapper);
    }

    private AgentDataProfileJob job() {
        AgentDataProfileJob value = new AgentDataProfileJob();
        value.setId(20L);
        value.setDatasetId(1L);
        value.setRequestedBy(7L);
        value.setStatus("running");
        return value;
    }

    private AgentDataProfileJobTable item() {
        AgentDataProfileJobTable value = new AgentDataProfileJobTable();
        value.setId(30L);
        value.setJobId(20L);
        value.setDatasetId(1L);
        value.setTableId(10L);
        value.setStatus("running");
        return value;
    }
}
