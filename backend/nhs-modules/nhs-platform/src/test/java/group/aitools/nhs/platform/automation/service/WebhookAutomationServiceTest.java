package group.aitools.nhs.platform.automation.service;

import group.aitools.nhs.platform.automation.domain.AutomationTrigger;
import group.aitools.nhs.platform.automation.mapper.AutomationMapper;
import group.aitools.nhs.platform.automation.web.AutomationFireView;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.identity.service.ApiCredentialAuthenticator;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class WebhookAutomationServiceTest {

    private static final String SECRET =
        "agk_abcdefghijkl.abcdefghijklmnopqrstuvwxyzABCDEFGH123456789";
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final String TIMESTAMP = String.valueOf(NOW.getEpochSecond());
    private static final String NONCE = "nonce_abcdefghijkl";
    private static final String TRIGGER_KEY = "daily-report";
    private static final String BODY = "{\"input\":\"build report\"}";

    private AutomationMapper mapper;
    private ApiCredentialAuthenticator authenticator;
    private AutomationApplicationService applicationService;
    private WebhookAutomationService service;
    private AutomationTrigger trigger;

    @BeforeEach
    void setUp() {
        mapper = mock(AutomationMapper.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        authenticator = mock(ApiCredentialAuthenticator.class);
        applicationService = mock(AutomationApplicationService.class);
        trigger = new AutomationTrigger();
        trigger.setId(10L);
        trigger.setTriggerKey(TRIGGER_KEY);
        trigger.setTriggerType("webhook");
        trigger.setServiceAccountId(20L);
        trigger.setRevisionNo(1L);
        trigger.setStatus("active");
        when(ids.nextId()).thenReturn(99L);
        when(mapper.selectTriggerByKey(TRIGGER_KEY)).thenReturn(trigger);
        when(authenticator.authenticate(SECRET)).thenReturn(authenticated(20L, Set.of("webhooks:invoke")));
        service = new WebhookAutomationService(
            mapper, ids, authenticator, applicationService, JsonMapper.builder().build(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void validSignedWebhookPersistsOnlyNonceHashThenDelegates() throws Exception {
        AutomationFireView accepted = fireView(false);
        when(mapper.insertWebhookNonce(any(), any(), anyString(), any(), any(), any())).thenReturn(1);
        when(applicationService.webhookFire(trigger, "request-1", "build report"))
            .thenReturn(accepted);

        AutomationFireView result = invoke(TIMESTAMP, NONCE, signature(TIMESTAMP, NONCE, BODY), BODY);

        assertEquals(accepted, result);
        verify(mapper).insertWebhookNonce(
            any(), any(), org.mockito.ArgumentMatchers.eq(ContentHashing.sha256(NONCE)),
            any(), any(), any()
        );
        verify(applicationService).webhookFire(trigger, "request-1", "build report");
    }

    @Test
    void reusedNonceIsRejectedBeforeAnyFireIsAccepted() throws Exception {
        when(mapper.insertWebhookNonce(any(), any(), anyString(), any(), any(), any())).thenReturn(0);

        ServiceException exception = assertThrows(ServiceException.class, () ->
            invoke(TIMESTAMP, NONCE, signature(TIMESTAMP, NONCE, BODY), BODY)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(applicationService, never()).webhookFire(any(), anyString(), any());
    }

    @Test
    void forgedAndMalformedSignaturesUseTheSameUnauthorizedOutcome() {
        ServiceException forged = assertThrows(ServiceException.class, () ->
            invoke(TIMESTAMP, NONCE, "v1=" + "0".repeat(64), BODY)
        );
        ServiceException malformed = assertThrows(ServiceException.class, () ->
            invoke(TIMESTAMP, NONCE, "not-a-signature", BODY)
        );

        assertEquals(HttpStatus.UNAUTHORIZED, forged.getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, malformed.getCode());
        assertFalse(service.validSignature(SECRET, "canonical", "v1=" + "0".repeat(64)));
        verify(mapper, never()).insertWebhookNonce(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void expiredAndFutureTimestampsAreRejectedBeforeCredentialLookup() {
        String expired = String.valueOf(NOW.minusSeconds(301).getEpochSecond());
        String future = String.valueOf(NOW.plusSeconds(301).getEpochSecond());

        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ServiceException.class, () ->
            invoke(expired, NONCE, "v1=" + "0".repeat(64), BODY)
        ).getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ServiceException.class, () ->
            invoke(future, NONCE, "v1=" + "0".repeat(64), BODY)
        ).getCode());
        verify(authenticator, never()).authenticate(anyString());
    }

    @Test
    void credentialScopeAndAccountMustMatchTrigger() throws Exception {
        when(authenticator.authenticate(SECRET)).thenReturn(authenticated(21L, Set.of("webhooks:invoke")));
        ServiceException mismatch = assertThrows(ServiceException.class, () ->
            invoke(TIMESTAMP, NONCE, signature(TIMESTAMP, NONCE, BODY), BODY)
        );
        when(authenticator.authenticate(SECRET)).thenReturn(authenticated(20L, Set.of("tasks:run")));
        ServiceException scope = assertThrows(ServiceException.class, () ->
            invoke(TIMESTAMP, NONCE, signature(TIMESTAMP, NONCE, BODY), BODY)
        );

        assertEquals(HttpStatus.FORBIDDEN, mismatch.getCode());
        assertEquals(HttpStatus.FORBIDDEN, scope.getCode());
        verify(mapper, never()).insertWebhookNonce(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void revokedCredentialAndTemplateFieldInjectionFailClosed() throws Exception {
        when(authenticator.authenticate(SECRET)).thenThrow(
            new ServiceException("API凭证无效或已失效", HttpStatus.UNAUTHORIZED)
        );
        ServiceException revoked = assertThrows(ServiceException.class, () ->
            invoke(TIMESTAMP, NONCE, signature(TIMESTAMP, NONCE, BODY), BODY)
        );

        String injected = "{\"input\":\"ok\",\"template\":\"${secret}\"}";
        ServiceException injection = assertThrows(ServiceException.class, () ->
            invoke(TIMESTAMP, NONCE, "v1=" + "0".repeat(64), injected)
        );

        assertEquals(HttpStatus.UNAUTHORIZED, revoked.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, injection.getCode());
        verify(applicationService, never()).webhookFire(any(), anyString(), any());
    }

    private AutomationFireView invoke(
        String timestamp,
        String nonce,
        String signature,
        String body
    ) {
        return service.invoke(
            TRIGGER_KEY, "Bearer " + SECRET, timestamp, nonce, signature, "request-1", body
        );
    }

    private String signature(String timestamp, String nonce, String body) throws Exception {
        String canonical = timestamp + "\n" + nonce + "\n" + TRIGGER_KEY + "\n"
            + ContentHashing.sha256(body);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "v1=" + HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private AuthenticatedServiceAccount authenticated(Long accountId, Set<String> scopes) {
        CurrentPrincipal principal = new CurrentPrincipal(
            accountId, "worker", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
        );
        return new AuthenticatedServiceAccount(
            principal, 30L, "webhook-app", "webhook", 40L, scopes
        );
    }

    private AutomationFireView fireView(boolean replayed) {
        return new AutomationFireView(
            1L, 10L, "webhook", "queued", 2L, null, 0, null,
            null, null, null, replayed
        );
    }
}
