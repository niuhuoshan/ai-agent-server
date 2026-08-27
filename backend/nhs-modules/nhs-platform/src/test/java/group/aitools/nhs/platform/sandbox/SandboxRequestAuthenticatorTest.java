package group.aitools.nhs.platform.sandbox;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.sandbox.mapper.SandboxRunnerMapper;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxRunnerRow;
import group.aitools.nhs.platform.sandbox.service.SandboxRequestAuthenticator;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class SandboxRequestAuthenticatorTest {

    private static final String BOOTSTRAP = "b".repeat(48);
    private static final String RUNNER_SECRET = "asr_ABCDEFGHIJKL." + "A".repeat(43);

    private SandboxRunnerMapper mapper;
    private SandboxRequestAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        mapper = mock(SandboxRunnerMapper.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        when(ids.nextId()).thenReturn(1L);
        authenticator = new SandboxRequestAuthenticator(mapper, ids, BOOTSTRAP, 120);
    }

    @Test
    void registrationConsumesNonceAndRejectsReplay() {
        when(mapper.insertNonce(anyLong(), eq(0L), any(), any(), any(), any()))
            .thenReturn(1, 0);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = "registration_nonce_1234";

        authenticator.authenticateRegistration("Bearer " + BOOTSTRAP, timestamp, nonce);

        ServiceException replay = assertThrows(ServiceException.class, () ->
            authenticator.authenticateRegistration("Bearer " + BOOTSTRAP, timestamp, nonce)
        );
        assertEquals(401, replay.getCode());
    }

    @Test
    void authenticatesOnlyStoredRunnerHashAndConsumesRunnerScopedNonce() {
        SandboxRunnerRow row = new SandboxRunnerRow();
        row.setId(7L);
        row.setRunnerKey("runner-a");
        row.setStatus("active");
        row.setSecretHash(ContentHashing.sha256(RUNNER_SECRET));
        when(mapper.selectRunnerByKey("runner-a")).thenReturn(row);
        when(mapper.insertNonce(anyLong(), eq(7L), any(), any(), any(), any())).thenReturn(1);

        var result = authenticator.authenticateRunner(
            "runner-a", "Bearer " + RUNNER_SECRET,
            Long.toString(Instant.now().getEpochSecond()), "runner_request_nonce_123"
        );

        assertEquals(7L, result.runnerId());
        assertThrows(ServiceException.class, () -> authenticator.authenticateRunner(
            "runner-a", "Bearer asr_ABCDEFGHIJKL." + "B".repeat(43),
            Long.toString(Instant.now().getEpochSecond()), "runner_request_nonce_456"
        ));
    }

    @Test
    void rejectsStaleTimestampsBeforeTouchingNonceState() {
        assertThrows(ServiceException.class, () -> authenticator.authenticateRegistration(
            "Bearer " + BOOTSTRAP,
            Long.toString(Instant.now().minusSeconds(600).getEpochSecond()),
            "registration_nonce_5678"
        ));
    }
}
