package group.aitools.nhs.platform.nhs.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class NhsV1OperationAuditServiceTest {

    @Test
    void writesAuthenticatedOperationWithoutRawQuestionContent() throws Exception {
        AgentAuditEventMapper mapper = mock(AgentAuditEventMapper.class);
        PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
        when(idGenerator.nextId()).thenReturn(901L);
        when(principalProvider.currentPrincipal()).thenReturn(new CurrentPrincipal(
            7L, "analyst", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
        ));
        acceptInserts(mapper);

        NhsV1OperationAuditService service = new NhsV1OperationAuditService(
            mapper, idGenerator, principalProvider
        );
        service.recordCurrent(
            "dataset_menu.click", "dataset_menu", null, "success", "preference_recorded",
            "queryLength=18; queryFingerprint=abc123"
        );

        verify(mapper).insertEvent(
            eq(901L), eq("user"), eq(7L), eq("dataset_menu.click"), eq("dataset_menu"),
            isNull(), isNull(), eq("success"), eq("preference_recorded"),
            eq("queryLength=18; queryFingerprint=abc123"), any(LocalDateTime.class)
        );
        Method record = NhsV1OperationAuditService.class.getMethod(
            "record", CurrentPrincipal.class, String.class, String.class, Long.class,
            String.class, String.class, String.class
        );
        Transactional transactional = record.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void recordsPublicCapabilityAsApplicationAndSanitizesLineBreaks() {
        AgentAuditEventMapper mapper = mock(AgentAuditEventMapper.class);
        PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
        when(idGenerator.nextId()).thenReturn(902L);
        acceptInserts(mapper);

        NhsV1OperationAuditService service = new NhsV1OperationAuditService(
            mapper, idGenerator, principalProvider
        );
        service.recordApplication(
            "generated_file.download", "generated_file", null, "deny",
            "capability_invalid_or_expired", "artifactFingerprint=abc\nsize=0"
        );

        verify(mapper).insertEvent(
            eq(902L), eq("application"), isNull(), eq("generated_file.download"),
            eq("generated_file"), isNull(), isNull(), eq("deny"),
            eq("capability_invalid_or_expired"), eq("artifactFingerprint=abc size=0"),
            any(LocalDateTime.class)
        );
    }

    @Test
    void failsClosedWhenAuditInsertDoesNotWriteExactlyOneRow() {
        AgentAuditEventMapper mapper = mock(AgentAuditEventMapper.class);
        PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
        when(idGenerator.nextId()).thenReturn(903L);
        when(mapper.insertEvent(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(0);

        NhsV1OperationAuditService service = new NhsV1OperationAuditService(
            mapper, idGenerator, principalProvider
        );

        ServiceException failure = assertThrows(ServiceException.class, () -> service.recordApplication(
            "generated_file.download", "generated_file", null, "success",
            "capability_accepted", "artifactFingerprint=abc"
        ));

        assertThat(failure.getCode()).isEqualTo(503);
        assertThat(failure.getMessage()).isEqualTo("操作审计写入失败，请稍后重试");
        assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(failure.getDetailMessage()).contains("persist_event");
    }

    @Test
    void doesNotDowngradeMissingAuthenticatedPrincipalToApplicationIdentity() {
        AgentAuditEventMapper mapper = mock(AgentAuditEventMapper.class);
        PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
        IllegalStateException identityFailure = new IllegalStateException("security context unavailable");
        when(principalProvider.currentPrincipal()).thenThrow(identityFailure);

        NhsV1OperationAuditService service = new NhsV1OperationAuditService(
            mapper, idGenerator, principalProvider
        );

        ServiceException failure = assertThrows(ServiceException.class, () -> service.recordCurrent(
            "dataset_menu.view", "dataset_menu", null, "success", "authorized_catalog", "datasets=1"
        ));

        assertThat(failure.getCode()).isEqualTo(503);
        assertThat(failure.getCause()).isSameAs(identityFailure);
        assertThat(failure.getDetailMessage()).contains("resolve_principal");
        verifyNoInteractions(mapper);
    }

    private void acceptInserts(AgentAuditEventMapper mapper) {
        when(mapper.insertEvent(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(1);
    }
}
