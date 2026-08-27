package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.embed.domain.EmbedBrowserCredential;
import group.aitools.nhs.platform.embed.mapper.EmbedChatMapper;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.service.ApiCredentialAuthenticator;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.platform.identity.service.EmbedApplicationConfig;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class EmbedBrowserCredentialServiceTest {

    private EmbedChatMapper mapper;
    private PlatformIdGenerator ids;
    private ApiCredentialAuthenticator apiAuthenticator;
    private EmbedApplicationPolicy policy;
    private EmbedBrowserCredentialService service;
    private AuthenticatedServiceAccount authenticated;

    @BeforeEach
    void setUp() {
        mapper = mock(EmbedChatMapper.class);
        ids = mock(PlatformIdGenerator.class);
        apiAuthenticator = mock(ApiCredentialAuthenticator.class);
        policy = mock(EmbedApplicationPolicy.class);
        service = new EmbedBrowserCredentialService(mapper, ids, apiAuthenticator, policy);
        authenticated = new AuthenticatedServiceAccount(
            new CurrentPrincipal(
                20L, "embed-worker", PrincipalType.SERVICE_ACCOUNT,
                Set.of(PlatformRole.SERVICE_ACCOUNT)
            ),
            10L, "embed-app", "embed", 30L, Set.of("chat:invoke")
        );
    }

    @Test
    void launchStoresOnlyHashAndExactBrowserScope() {
        when(ids.nextId()).thenReturn(100L);
        when(mapper.insertBrowserCredential(any())).thenReturn(1);

        var issued = service.issueLaunch(
            authenticated, "HTTPS://Portal.Example.com:443/", 40L, "customer-1", 30
        );

        ArgumentCaptor<EmbedBrowserCredential> stored = ArgumentCaptor.forClass(
            EmbedBrowserCredential.class
        );
        verify(mapper).insertBrowserCredential(stored.capture());
        assertTrue(issued.credential().matches("ebt_[A-Za-z0-9_-]{43}"));
        assertEquals(ContentHashing.sha256(issued.credential()), stored.getValue().getTokenHash());
        assertNotEquals(issued.credential(), stored.getValue().getTokenHash());
        assertEquals("https://portal.example.com", stored.getValue().getHostOrigin());
        assertEquals(40L, stored.getValue().getAgentVersionId());
        assertEquals(ContentHashing.sha256("customer-1"), stored.getValue().getExternalUserHash());
        assertEquals("launch", stored.getValue().getTokenKind());
    }

    @Test
    void eachUseRevalidatesUnderlyingCredentialAndCurrentPolicy() {
        String raw = "ebt_" + "a".repeat(43);
        EmbedBrowserCredential credential = credential(raw, "session", 50L);
        when(mapper.selectBrowserCredential(ContentHashing.sha256(raw))).thenReturn(credential);
        when(apiAuthenticator.authenticateCredential(30L)).thenReturn(authenticated);
        when(mapper.touchBrowserCredential(any(), any())).thenReturn(1);
        EmbedApplicationConfig config = config();
        when(policy.currentConfig(authenticated)).thenReturn(config);

        var access = service.authenticateSession("Bearer " + raw, "https://portal.example.com", 50L);

        assertEquals(authenticated, access.authenticated());
        verify(apiAuthenticator).authenticateCredential(30L);
        verify(policy).requireSessionAllowed(authenticated, "https://portal.example.com", 40L, 30);
        verify(mapper).touchBrowserCredential(any(), any());
    }

    @Test
    void originMismatchFailsBeforeUnderlyingCredentialCanBeUsed() {
        String raw = "ebt_" + "a".repeat(43);
        when(mapper.selectBrowserCredential(ContentHashing.sha256(raw)))
            .thenReturn(credential(raw, "session", 50L));

        ServiceException exception = assertThrows(ServiceException.class, () ->
            service.authenticateSession("Bearer " + raw, "https://evil.example", 50L)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
        verify(apiAuthenticator, never()).authenticateCredential(any());
    }

    private EmbedBrowserCredential credential(String raw, String kind, Long sessionId) {
        EmbedBrowserCredential credential = new EmbedBrowserCredential();
        credential.setId(100L);
        credential.setTokenHash(ContentHashing.sha256(raw));
        credential.setTokenKind(kind);
        credential.setApplicationId(10L);
        credential.setApiCredentialId(30L);
        credential.setServiceAccountId(20L);
        credential.setAgentVersionId(40L);
        credential.setHostOrigin("https://portal.example.com");
        credential.setExternalUserHash("b".repeat(64));
        credential.setSessionMinutes(30);
        credential.setSessionId(sessionId);
        credential.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        credential.setCreatedAt(LocalDateTime.now());
        return credential;
    }

    private EmbedApplicationConfig config() {
        return new EmbedApplicationConfig(
            List.of("https://portal.example.com"), Set.of(40L), "助手", "#18a058", true, 30
        );
    }
}
