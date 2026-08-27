package group.aitools.nhs.platform.operations.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.operations.domain.LogMaintenanceRun;
import group.aitools.nhs.platform.operations.domain.LogRetentionPolicy;
import group.aitools.nhs.platform.operations.mapper.LogMaintenanceMapper;
import group.aitools.nhs.platform.operations.web.LogCleanupRequest;
import group.aitools.nhs.platform.operations.web.UpdateLogRetentionConfigRequest;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
class LogMaintenanceApplicationServiceTest {

    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER, PlatformRole.PLATFORM_ADMIN)
    );

    private CurrentPrincipalProvider principalProvider;
    private LogMaintenanceMapper mapper;
    private LogStorageMaintenanceRepository repository;
    private PlatformIdGenerator idGenerator;
    private AgentAuditEventMapper auditMapper;
    private LogMaintenanceApplicationService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        mapper = mock(LogMaintenanceMapper.class);
        repository = mock(LogStorageMaintenanceRepository.class);
        idGenerator = mock(PlatformIdGenerator.class);
        auditMapper = mock(AgentAuditEventMapper.class);
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        when(idGenerator.nextId()).thenReturn(101L, 102L, 103L, 104L, 105L);
        service = new LogMaintenanceApplicationService(
            principalProvider, mapper, repository, idGenerator, auditMapper, JsonMapper.builder().build()
        );
    }

    @Test
    void rejectsNonAdministratorBeforeReadingStorageFacts() {
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            2L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));

        assertThatThrownBy(service::partitions)
            .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(403));
        verifyNoInteractions(mapper, repository, auditMapper);
    }

    @Test
    void acceptsNhsCompatibleConfigUpdateAndPersistsAudit() {
        LogRetentionPolicy before = policy(90, 4);
        LogRetentionPolicy after = policy(120, 5);
        when(mapper.selectPolicy()).thenReturn(before, after);
        when(mapper.updatePolicy(eq(120), eq(4), eq(1L), any(), anyString())).thenReturn(1);

        var result = service.updateConfiguration(new UpdateLogRetentionConfigRequest(120, null, null));

        assertThat(result.retentionDays()).isEqualTo(120);
        assertThat(result.revisionNo()).isEqualTo(5);
        verify(mapper).updatePolicy(eq(120), eq(4), eq(1L), any(), eq("Nhs 日志管理兼容接口更新"));
        verify(auditMapper).insertEvent(
            anyLong(), eq("user"), eq(1L), eq("update"), eq("log_retention_policy"), eq(1L),
            eq(null), eq("success"), eq("platform_admin"), anyString(), any()
        );
    }

    @Test
    void createsSingleUsePreviewWithoutPersistingRawToken() {
        when(mapper.selectPolicy()).thenReturn(policy(90, 3));
        when(repository.inspect(any())).thenReturn(snapshot(7));
        ArgumentCaptor<LogMaintenanceRun> runCaptor = ArgumentCaptor.forClass(LogMaintenanceRun.class);

        var preview = service.previewCleanup();

        verify(mapper).insertRun(runCaptor.capture());
        LogMaintenanceRun persisted = runCaptor.getValue();
        assertThat(preview.confirmationToken()).hasSizeGreaterThan(32);
        assertThat(persisted.getConfirmationTokenHash()).hasSize(64).isNotEqualTo(preview.confirmationToken());
        assertThat(persisted.getStatus()).isEqualTo("previewed");
        assertThat(preview.expiredRows()).isEqualTo(7);
        assertThat(preview.warnings()).hasSize(3);
    }

    @Test
    void expiresStaleConfirmationWithoutTouchingLogTables() {
        LogMaintenanceRun run = manualRun();
        run.setConfirmationExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectRunByTokenHash(anyString())).thenReturn(run);

        assertThatThrownBy(() -> service.cleanup(new LogCleanupRequest("valid-preview-token", true)))
            .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(410));

        verify(mapper).expirePreview(eq(run.getId()), any());
        verify(repository, never()).maintain(any());
    }

    @Test
    void executesConfirmedCleanupAndPersistsStructuredResult() {
        LogMaintenanceRun run = manualRun();
        when(mapper.selectRunByTokenHash(anyString())).thenReturn(run);
        when(mapper.selectPolicy()).thenReturn(policy(90, 3));
        when(mapper.claimManualRun(eq(run.getId()), eq(3), any())).thenReturn(1);
        when(repository.maintain(run.getCutoffAt())).thenReturn(outcome(false));

        var result = service.cleanup(new LogCleanupRequest("valid-preview-token", true));

        assertThat(result.status()).isEqualTo("succeeded");
        assertThat(result.deletedRows()).isEqualTo(12);
        assertThat(result.droppedPartitions()).containsExactly("agent_audit_event_p202601");
        verify(mapper).finishRun(eq(run.getId()), eq("succeeded"), anyString(), eq(null), eq(null), any());
        verify(auditMapper).insertEvent(
            anyLong(), eq("user"), eq(1L), eq("cleanup"), eq("log_maintenance"), eq(run.getId()),
            eq(null), eq("success"), eq("platform_admin"), anyString(), any()
        );
    }

    @Test
    void recordsFailedRunWhenPostgresMaintenanceRollsBack() {
        LogMaintenanceRun run = manualRun();
        when(mapper.selectRunByTokenHash(anyString())).thenReturn(run);
        when(mapper.selectPolicy()).thenReturn(policy(90, 3));
        when(mapper.claimManualRun(eq(run.getId()), eq(3), any())).thenReturn(1);
        when(repository.maintain(run.getCutoffAt()))
            .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> service.cleanup(new LogCleanupRequest("valid-preview-token", true)))
            .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(503));

        verify(mapper).finishRun(
            eq(run.getId()), eq("failed"), eq(null), eq("LOG_MAINTENANCE_FAILED"), anyString(), any()
        );
        verify(auditMapper).insertEvent(
            anyLong(), eq("user"), eq(1L), eq("cleanup"), eq("log_maintenance"), eq(run.getId()),
            eq(null), eq("failure"), eq("log_maintenance_failed"), anyString(), any()
        );
    }

    @Test
    void recognizesOnlyCreatedAtRangePartitioning() {
        assertThat(LogStorageMaintenanceRepository.isSupportedPartitionKey("RANGE (created_at)")).isTrue();
        assertThat(LogStorageMaintenanceRepository.isSupportedPartitionKey("HASH (id)")).isFalse();
        assertThat(LogStorageMaintenanceRepository.isSupportedPartitionKey(null)).isFalse();
        assertThat(LogStorageMaintenanceRepository.partitionUpperBound(
            "FOR VALUES FROM ('2026-01-01 00:00:00') TO ('2026-02-01 00:00:00')"
        )).isEqualTo(LocalDateTime.of(2026, 2, 1, 0, 0));
        assertThat(LogStorageMaintenanceRepository.partitionUpperBound("DEFAULT")).isNull();
        assertThat(LogStorageMaintenanceRepository.partitionUpperBound(
            "FOR VALUES FROM (MINVALUE) TO ('2026-03-01'::timestamp without time zone)"
        )).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
    }

    private LogRetentionPolicy policy(int days, int revision) {
        LogRetentionPolicy policy = new LogRetentionPolicy();
        policy.setId(1);
        policy.setRetentionDays(days);
        policy.setRevisionNo(revision);
        policy.setUpdatedAt(LocalDateTime.now());
        policy.setChangeReason("test");
        return policy;
    }

    private LogMaintenanceRun manualRun() {
        LogMaintenanceRun run = new LogMaintenanceRun();
        run.setId(500L);
        run.setTriggerType("manual");
        run.setStatus("previewed");
        run.setRetentionDays(90);
        run.setPolicyRevision(3);
        run.setCutoffAt(LocalDateTime.now().minusDays(90));
        run.setConfirmationExpiresAt(LocalDateTime.now().plusMinutes(5));
        run.setRequestedBy(ADMIN.id());
        run.setCreatedAt(LocalDateTime.now());
        return run;
    }

    private LogStorageMaintenanceRepository.StorageSnapshot snapshot(long expiredRows) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        var partition = new LogStorageMaintenanceRepository.PartitionStorageFact(
            "public", "agent_audit_event", "UNPARTITIONED", false, 10, 4096,
            cutoff.minusDays(3), cutoff.plusDays(3), expiredRows, false
        );
        var table = new LogStorageMaintenanceRepository.TableStorageFact(
            "agent_audit_event", "平台审计事件", false, null, 10, 4096,
            partition.oldestAt(), partition.newestAt(), expiredRows, List.of(partition)
        );
        return new LogStorageMaintenanceRepository.StorageSnapshot(LocalDateTime.now(), cutoff, List.of(table));
    }

    private LogStorageMaintenanceRepository.MaintenanceOutcome outcome(boolean remaining) {
        var table = new LogStorageMaintenanceRepository.TableCleanupResult(
            "agent_audit_event", List.of("agent_audit_event_p202601"), 20, 12, remaining
        );
        return new LogStorageMaintenanceRepository.MaintenanceOutcome(
            List.of("agent_audit_event_p202610"), List.of(table), remaining
        );
    }
}
