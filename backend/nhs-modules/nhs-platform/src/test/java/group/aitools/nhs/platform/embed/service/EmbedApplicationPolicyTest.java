package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.domain.ApiApplication;
import group.aitools.nhs.platform.identity.mapper.MachineIdentityMapper;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class EmbedApplicationPolicyTest {

    private MachineIdentityMapper mapper;
    private EmbedApplicationPolicy policy;
    private AuthenticatedServiceAccount authenticated;

    @BeforeEach
    void setUp() {
        mapper = mock(MachineIdentityMapper.class);
        policy = new EmbedApplicationPolicy(mapper, JsonMapper.builder().build());
        authenticated = new AuthenticatedServiceAccount(
            new CurrentPrincipal(
                2L, "embed-worker", PrincipalType.SERVICE_ACCOUNT,
                Set.of(PlatformRole.SERVICE_ACCOUNT)
            ),
            10L, "embed-app", "embed", 30L, Set.of("chat:invoke")
        );
    }

    @Test
    void requiresCurrentOriginAgentAndLifetimePolicy() {
        when(mapper.selectApiApplication(10L)).thenReturn(application());

        assertDoesNotThrow(() -> policy.requireSessionAllowed(
            authenticated, "https://portal.example.com", 40L, 30
        ));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ServiceException.class, () ->
            policy.requireSessionAllowed(authenticated, "https://evil.example", 40L, 30)
        ).getCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ServiceException.class, () ->
            policy.requireSessionAllowed(authenticated, "https://portal.example.com", 41L, 30)
        ).getCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ServiceException.class, () ->
            policy.requireSessionAllowed(authenticated, "https://portal.example.com", 40L, 31)
        ).getCode());
    }

    @Test
    void serverToServerCallsMayOmitOriginButBrowserCallsMayNotEscapeAllowlist() {
        when(mapper.selectApiApplication(10L)).thenReturn(application());
        assertDoesNotThrow(() -> policy.requireOriginAllowed(authenticated, null));
        assertDoesNotThrow(() -> policy.requireOriginAllowed(authenticated, "https://portal.example.com"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ServiceException.class, () ->
            policy.requireOriginAllowed(authenticated, "https://evil.example")
        ).getCode());
    }

    @Test
    void everyMessageRechecksTheCurrentAgentVersionAllowlist() {
        when(mapper.selectApiApplication(10L)).thenReturn(application());
        assertDoesNotThrow(() -> policy.requireRequestAllowed(
            authenticated, "https://portal.example.com", 40L
        ));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ServiceException.class, () ->
            policy.requireRequestAllowed(authenticated, "https://portal.example.com", 41L)
        ).getCode());
    }

    private ApiApplication application() {
        ApiApplication application = new ApiApplication();
        application.setId(10L);
        application.setAppType("embed");
        application.setStatus("active");
        application.setExtraJson("""
            {"allowedOrigins":["https://portal.example.com"],"agentVersionIds":[40],
             "maxSessionMinutes":30,"watermark":true,"primaryColor":"#18a058"}
            """);
        return application;
    }
}
