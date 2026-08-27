package group.aitools.nhs.platform.operations;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.operations.domain.PlatformConfiguration;
import group.aitools.nhs.platform.operations.domain.PlatformConfigurationHistory;
import group.aitools.nhs.platform.operations.mapper.PlatformConfigurationMapper;
import group.aitools.nhs.platform.operations.service.PlatformConfigurationApplicationService;
import group.aitools.nhs.platform.operations.web.UpdatePlatformConfigurationRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PlatformConfigurationApplicationServiceTest {

    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        7L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );
    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        8L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private CurrentPrincipalProvider principalProvider;
    private PlatformConfigurationMapper mapper;
    private PlatformIdGenerator idGenerator;
    private AgentAuditEventMapper auditMapper;
    private PlatformConfigurationApplicationService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        mapper = mock(PlatformConfigurationMapper.class);
        idGenerator = mock(PlatformIdGenerator.class);
        auditMapper = mock(AgentAuditEventMapper.class);
        service = new PlatformConfigurationApplicationService(
            principalProvider, mapper, idGenerator, auditMapper
        );
        when(mapper.selectCurrent()).thenReturn(current(3L));
    }

    @Test
    void onlyAdministratorCanReadManagedConfiguration() {
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);

        ServiceException exception = assertThrows(ServiceException.class, service::current);

        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
        verify(mapper, never()).selectCurrent();
    }

    @Test
    void updatesConfigurationWithRevisionHistoryAndAudit() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        when(mapper.updateCurrent(any(), eq(3L))).thenReturn(1);
        when(mapper.insertHistory(any())).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(501L, 502L);

        var result = service.update(request(3L, "/assets/logo.svg"));

        assertEquals(4L, result.revisionNo());
        assertEquals("#1570EF", result.primaryColor());
        assertEquals("Asia/Shanghai", result.platformTimezone());
        ArgumentCaptor<PlatformConfigurationHistory> history =
            ArgumentCaptor.forClass(PlatformConfigurationHistory.class);
        verify(mapper).insertHistory(history.capture());
        assertEquals(501L, history.getValue().getId());
        assertEquals(4L, history.getValue().getRevisionNo());
        assertEquals("调整企业品牌", history.getValue().getChangeReason());
        verify(auditMapper).insertEvent(
            eq(502L), eq("user"), eq(7L), eq("update"),
            eq("platform_configuration"), eq(1L), eq(null), eq("success"),
            eq("platform_admin"), any(), any(LocalDateTime.class)
        );
    }

    @Test
    void rejectsStaleRevisionBeforeWriting() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.update(request(2L, "/assets/logo.svg"))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(mapper, never()).updateCurrent(any(), anyLong());
        verify(mapper, never()).insertHistory(any());
    }

    @Test
    void rejectsInsecureRemoteBrandAsset() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.update(request(3L, "http://example.test/logo.svg"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        assertTrue(exception.getMessage().contains("HTTPS"));
        verify(mapper, never()).updateCurrent(any(), anyLong());
    }

    private UpdatePlatformConfigurationRequest request(Long revision, String logoUrl) {
        return new UpdatePlatformConfigurationRequest(
            "企业智能体工作平台", "智能体平台", logoUrl, "/favicon.svg",
            "#1570ef", "Asia/Shanghai", "zh-CN", true, revision, "调整企业品牌"
        );
    }

    private PlatformConfiguration current(Long revision) {
        PlatformConfiguration value = new PlatformConfiguration();
        value.setId(1L);
        value.setProductName("企业级智能体工作平台");
        value.setProductShortName("智能体平台");
        value.setPrimaryColor("#18A058");
        value.setPlatformTimezone("Asia/Shanghai");
        value.setDefaultLocale("zh-CN");
        value.setWatermarkEnabled(false);
        value.setRevisionNo(revision);
        value.setUpdateTime(LocalDateTime.now());
        return value;
    }
}
