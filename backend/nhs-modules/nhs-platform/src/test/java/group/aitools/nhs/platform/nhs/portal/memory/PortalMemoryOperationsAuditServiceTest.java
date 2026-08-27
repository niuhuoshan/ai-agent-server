package group.aitools.nhs.platform.nhs.portal.memory;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PortalMemoryOperationsAuditServiceTest {

    @Test
    void writesSanitizedContentFreeOperationFactsInAnIndependentTransaction() throws Exception {
        AgentAuditEventMapper mapper = mock(AgentAuditEventMapper.class);
        PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
        when(idGenerator.nextId()).thenReturn(900L);
        CurrentPrincipal administrator = new CurrentPrincipal(
            1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
        );
        PortalMemoryOperationsAuditService service = new PortalMemoryOperationsAuditService(mapper, idGenerator);

        service.record(
            administrator, "memory.config_update", 1L, "success",
            "configuration\nupdated", "ownerId=1\rdefaultSearchLimit=25"
        );

        verify(mapper).insertEvent(
            org.mockito.ArgumentMatchers.eq(900L),
            org.mockito.ArgumentMatchers.eq("user"),
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq("memory.config_update"),
            org.mockito.ArgumentMatchers.eq("memory_operations"),
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("success"),
            org.mockito.ArgumentMatchers.eq("configuration updated"),
            org.mockito.ArgumentMatchers.eq("ownerId=1 defaultSearchLimit=25"),
            any(LocalDateTime.class)
        );
        Method record = PortalMemoryOperationsAuditService.class.getMethod(
            "record", CurrentPrincipal.class, String.class, Long.class, String.class, String.class, String.class
        );
        Transactional transactional = record.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
