package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.platform.embed.domain.EmbedBrowserCredential;
import group.aitools.nhs.platform.embed.service.EmbedBrowserCredentialService.BrowserAccess;
import group.aitools.nhs.platform.embed.web.EmbedBrowserCredentialView;
import group.aitools.nhs.platform.embed.web.EmbedSessionView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.platform.identity.service.EmbedApplicationConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class EmbedWidgetSessionServiceTest {

    @Test
    void resetPersistsStopAndCancelsEveryActiveBackgroundTurn() {
        EmbedChatPersistenceService persistence = mock(EmbedChatPersistenceService.class);
        EmbedBrowserCredentialService credentials = mock(EmbedBrowserCredentialService.class);
        EmbedChatExecutionCoordinator coordinator = mock(EmbedChatExecutionCoordinator.class);
        EmbedWidgetSessionService service = new EmbedWidgetSessionService(
            persistence, credentials, coordinator
        );
        BrowserAccess access = access();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);
        EmbedSessionView next = new EmbedSessionView(51L, 40L, "active", expiresAt, LocalDateTime.now());
        when(persistence.requestSessionStops(access.authenticated(), 50L)).thenReturn(List.of(80L, 81L));
        when(persistence.createSessionWithHash(
            access.authenticated(), 40L, "a".repeat(64), 30
        )).thenReturn(next);
        when(credentials.rotateSession(access, 51L, expiresAt)).thenReturn(
            new EmbedBrowserCredentialView("ebt_" + "a".repeat(43), expiresAt, "1.0", "/embed/chat")
        );

        service.reset(access, 50L);

        verify(persistence).closeSession(access.authenticated(), 50L);
        verify(coordinator).requestStop(80L, "Embed会话重置");
        verify(coordinator).requestStop(81L, "Embed会话重置");
    }

    private BrowserAccess access() {
        AuthenticatedServiceAccount authenticated = new AuthenticatedServiceAccount(
            new CurrentPrincipal(
                20L, "embed", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
            ),
            10L, "embed-app", "embed", 30L, Set.of("chat:invoke")
        );
        EmbedBrowserCredential credential = new EmbedBrowserCredential();
        credential.setAgentVersionId(40L);
        credential.setExternalUserHash("a".repeat(64));
        credential.setSessionMinutes(30);
        EmbedApplicationConfig config = new EmbedApplicationConfig(
            List.of("https://portal.example.com"), Set.of(40L),
            "Assistant", "#18a058", true, 30
        );
        return new BrowserAccess(credential, authenticated, config);
    }
}
