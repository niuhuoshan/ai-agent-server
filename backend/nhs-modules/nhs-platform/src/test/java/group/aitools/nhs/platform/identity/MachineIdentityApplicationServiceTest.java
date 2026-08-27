package group.aitools.nhs.platform.identity;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.identity.domain.ApiApplication;
import group.aitools.nhs.platform.identity.domain.ApiCredential;
import group.aitools.nhs.platform.identity.domain.ServiceAccount;
import group.aitools.nhs.platform.identity.mapper.MachineIdentityMapper;
import group.aitools.nhs.platform.identity.service.CredentialSecretGenerator;
import group.aitools.nhs.platform.identity.service.CredentialSecretGenerator.GeneratedCredential;
import group.aitools.nhs.platform.identity.service.MachineIdentityApplicationService;
import group.aitools.nhs.platform.identity.web.CreateApiApplicationRequest;
import group.aitools.nhs.platform.identity.web.CreateServiceAccountRequest;
import group.aitools.nhs.platform.identity.web.IssueApiCredentialRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import group.aitools.nhs.system.api.UserService;
import group.aitools.nhs.system.api.domain.UserDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class MachineIdentityApplicationServiceTest {

    private MachineIdentityMapper mapper;
    private PlatformIdGenerator ids;
    private CredentialSecretGenerator secretGenerator;
    private MachineIdentityApplicationService service;

    @BeforeEach
    void setUp() {
        CurrentPrincipal admin = new CurrentPrincipal(
            1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
        );
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        AuthorizationEnforcer authorization = mock(AuthorizationEnforcer.class);
        ids = mock(PlatformIdGenerator.class);
        mapper = mock(MachineIdentityMapper.class);
        UserService users = mock(UserService.class);
        secretGenerator = mock(CredentialSecretGenerator.class);
        when(principals.currentPrincipal()).thenReturn(admin);
        when(users.selectById(1L)).thenReturn(new UserDTO());
        service = new MachineIdentityApplicationService(
            principals, authorization, ids, mapper, users, secretGenerator,
            JsonMapper.builder().build()
        );
    }

    @Test
    void credentialScopeIsNarrowedAndOnlyHashIsPersisted() {
        ApiApplication application = application("[\"tasks:read\",\"tasks:run\"]");
        ServiceAccount account = account();
        String raw = "agk_abcdefghijkl.abcdefghijklmnopqrstuvwxyzABCDEFGH123456789";
        String hash = ContentHashing.sha256(raw);
        when(mapper.lockApiApplication(10L)).thenReturn(application);
        when(mapper.lockServiceAccount(20L)).thenReturn(account);
        when(ids.nextId()).thenReturn(30L);
        when(secretGenerator.generate()).thenReturn(new GeneratedCredential(raw, "agk_abcdefghijkl", hash));
        when(mapper.insertApiCredential(any())).thenReturn(1);

        var issued = service.issueCredential(10L, new IssueApiCredentialRequest(
            20L, List.of("tasks:read"), LocalDateTime.now().plusDays(10)
        ));

        ArgumentCaptor<ApiCredential> captor = ArgumentCaptor.forClass(ApiCredential.class);
        verify(mapper).insertApiCredential(captor.capture());
        assertEquals(raw, issued.secret());
        assertEquals(hash, captor.getValue().getSecretHash());
        assertFalse(captor.getValue().getSecretHash().contains(raw));
        assertEquals(Set.of("tasks:read"), issued.credential().scopes());
    }

    @Test
    void credentialCannotExpandApplicationScope() {
        when(mapper.lockApiApplication(10L)).thenReturn(application("[\"tasks:read\"]"));
        when(mapper.lockServiceAccount(20L)).thenReturn(account());

        ServiceException exception = assertThrows(ServiceException.class, () ->
            service.issueCredential(10L, new IssueApiCredentialRequest(
                20L, List.of("tasks:run"), LocalDateTime.now().plusDays(1)
            ))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verify(mapper, never()).insertApiCredential(any());
    }

    @Test
    void machineIdentityMetadataRejectsSecretsAndUnsafeCallbacks() {
        ServiceException secret = assertThrows(ServiceException.class, () ->
            service.createServiceAccount(new CreateServiceAccountRequest(
                "worker", "Worker", null, 1L, null, Map.of("apiKey", "raw")
            ))
        );
        ServiceException callback = assertThrows(ServiceException.class, () ->
            service.createApiApplication(new CreateApiApplicationRequest(
                "open-api", "Open API", "open_api", 1L, "http://example.com/callback",
                List.of("tasks:read"), null
            ))
        );

        assertEquals(HttpStatus.BAD_REQUEST, secret.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, callback.getCode());
        verify(mapper, never()).insertServiceAccount(any());
        verify(mapper, never()).insertApiApplication(any());
    }

    @Test
    void embedApplicationPersistsOnlyValidatedBrowserPolicy() {
        when(ids.nextId()).thenReturn(10L);
        when(mapper.insertApiApplication(any())).thenReturn(1);

        var view = service.createApiApplication(new CreateApiApplicationRequest(
            "finance-widget", "Finance widget", "embed", 1L, null,
            List.of("chat:invoke"), null,
            Map.of(
                "allowedOrigins", List.of("https://portal.example.com/"),
                "agentVersionIds", List.of(40L),
                "maxSessionMinutes", 30
            )
        ));

        ArgumentCaptor<ApiApplication> captor = ArgumentCaptor.forClass(ApiApplication.class);
        verify(mapper).insertApiApplication(captor.capture());
        assertEquals(List.of("https://portal.example.com"), view.config().get("allowedOrigins"));
        assertEquals(List.of(40L), view.config().get("agentVersionIds"));
        assertFalse(captor.getValue().getExtraJson().contains("token"));
    }

    private ApiApplication application(String scopeJson) {
        ApiApplication application = new ApiApplication();
        application.setId(10L);
        application.setAppKey("app");
        application.setStatus("active");
        application.setScopeJson(scopeJson);
        return application;
    }

    private ServiceAccount account() {
        ServiceAccount account = new ServiceAccount();
        account.setId(20L);
        account.setAccountKey("worker");
        account.setName("Worker");
        account.setStatus("active");
        return account;
    }
}
