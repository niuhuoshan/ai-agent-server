package group.aitools.nhs.platform.sandbox;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.sandbox.mapper.SandboxRunnerMapper;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobRow;
import group.aitools.nhs.platform.sandbox.service.SandboxRequestAuthenticator.RunnerAuthentication;
import group.aitools.nhs.platform.sandbox.service.SandboxRunnerApplicationService;
import group.aitools.nhs.platform.sandbox.service.SandboxSecretGenerator;
import group.aitools.nhs.platform.sandbox.web.CompleteSandboxJobRequest;
import group.aitools.nhs.platform.sandbox.web.AppendSandboxJobOutputRequest;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class SandboxRunnerApplicationServiceTest {

    private static final String TOKEN = "asj_ABCDEFGHIJKL." + "A".repeat(43);

    private SandboxRunnerMapper mapper;
    private PlatformIdGenerator idGenerator;
    private SandboxRunnerApplicationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SandboxRunnerMapper.class);
        idGenerator = mock(PlatformIdGenerator.class);
        service = new SandboxRunnerApplicationService(
            mapper, idGenerator, new SandboxSecretGenerator(),
            JsonMapper.builder().findAndAddModules().build(), 45, 90
        );
    }

    @Test
    void crossRunnerCompletionIsRejectedBeforeMutation() {
        SandboxJobRow job = job(99L, 1024);
        when(mapper.selectJob(20L)).thenReturn(job);

        assertThrows(ServiceException.class, () -> service.complete(
            new RunnerAuthentication(7L, "runner-a", "active"), 20L, TOKEN,
            completion(true, "ok", "")
        ));
    }

    @Test
    void outputIsBoundedAndSecretsAreRedactedBeforePersistence() {
        SandboxJobRow job = job(7L, 1024);
        when(mapper.selectJob(20L)).thenReturn(job);
        when(mapper.completeJob(
            anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(),
            anyString(), anyString(), any(), any(), any()
        )).thenReturn(1);
        String leaked = "asr_ABCDEFGHIJKL." + "Z".repeat(43);
        ArgumentCaptor<String> stdout = ArgumentCaptor.forClass(String.class);

        service.complete(
            new RunnerAuthentication(7L, "runner-a", "active"), 20L, TOKEN,
            completion(true, "result=" + leaked, "")
        );

        verify(mapper).completeJob(
            eq(20L), eq(7L), anyString(), eq("succeeded"), eq(0), stdout.capture(),
            any(), anyString(), anyString(), eq(null), eq(null), any()
        );
        assertFalse(stdout.getValue().contains(leaked));
        assertEquals("result=[REDACTED_SECRET]", stdout.getValue());
    }

    @Test
    void doubleCompletionOrExpiredTokenReturnsConflict() {
        SandboxJobRow job = job(7L, 1024);
        when(mapper.selectJob(20L)).thenReturn(job);
        when(mapper.completeJob(
            anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(),
            anyString(), anyString(), any(), any(), any()
        )).thenReturn(0);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.complete(
            new RunnerAuthentication(7L, "runner-a", "active"), 20L, TOKEN,
            completion(true, "ok", "")
        ));

        assertEquals(409, exception.getCode());
    }

    @Test
    void incrementalOutputIsRedactedBoundedAndPersistedWithPlatformSequence() {
        SandboxJobRow job = job(7L, 1024);
        job.setAttemptNo(1);
        job.setOutputBytes(0);
        when(mapper.selectJob(20L)).thenReturn(job);
        when(mapper.reserveOutputSequence(
            eq(20L), eq(7L), anyString(), eq(0L), anyInt(), any()
        )).thenReturn(1L);
        when(idGenerator.nextId()).thenReturn(99L);
        when(mapper.insertOutput(
            anyLong(), anyLong(), anyInt(), anyLong(), anyLong(),
            anyString(), anyString(), anyInt(), any()
        )).thenReturn(1);
        String leaked = "asj_ABCDEFGHIJKL." + "Z".repeat(43);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);

        service.appendOutput(
            new RunnerAuthentication(7L, "runner-a", "active"), 20L, TOKEN,
            new AppendSandboxJobOutputRequest(
                0L, "stdout", "value=" + leaked + " password=supersecret123"
            )
        );

        verify(mapper).insertOutput(
            eq(99L), eq(20L), eq(1), eq(1L), eq(0L), eq("stdout"),
            content.capture(), anyInt(), any()
        );
        assertEquals(
            "value=[REDACTED_SECRET] password=[REDACTED_SECRET]",
            content.getValue()
        );
    }

    private SandboxJobRow job(Long runnerId, int outputLimit) {
        SandboxJobRow row = new SandboxJobRow();
        row.setId(20L);
        row.setAssignedRunnerId(runnerId);
        row.setMaxOutputBytes(outputLimit);
        return row;
    }

    private CompleteSandboxJobRequest completion(boolean succeeded, String stdout, String stderr) {
        return new CompleteSandboxJobRequest(
            succeeded, 0, stdout, stderr, List.of(), Map.of(), null, null
        );
    }
}
