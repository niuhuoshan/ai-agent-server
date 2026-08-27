package group.aitools.nhs.platform.iam;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.management.domain.PermissionCopyRecord;
import group.aitools.nhs.platform.iam.management.domain.PermissionProfile;
import group.aitools.nhs.platform.iam.management.mapper.PermissionAdministrationMapper;
import group.aitools.nhs.platform.iam.management.service.PermissionAdministrationService;
import group.aitools.nhs.platform.iam.management.web.CopyPermissionRequest;
import group.aitools.nhs.platform.iam.management.web.CreatePermissionProfileRequest;
import group.aitools.nhs.platform.iam.management.web.CreateTemporaryGrantRequest;
import group.aitools.nhs.platform.iam.management.web.PermissionRuleInput;
import group.aitools.nhs.platform.iam.management.web.PutPermissionBindingRequest;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import group.aitools.nhs.system.api.UserService;
import group.aitools.nhs.system.api.domain.UserDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PermissionAdministrationServiceTest {

    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );

    private PermissionAdministrationMapper mapper;
    private UserService userService;
    private PermissionAdministrationService service;

    @BeforeEach
    void setUp() {
        CurrentPrincipalProvider principals = mock(CurrentPrincipalProvider.class);
        AuthorizationEnforcer authorization = mock(AuthorizationEnforcer.class);
        PlatformIdGenerator ids = mock(PlatformIdGenerator.class);
        mapper = mock(PermissionAdministrationMapper.class);
        userService = mock(UserService.class);
        when(principals.currentPrincipal()).thenReturn(ADMIN);
        when(userService.selectById(anyLong())).thenReturn(new UserDTO());
        service = new PermissionAdministrationService(
            principals, authorization, ids, mapper, userService, JsonMapper.builder().build()
        );
    }

    @Test
    void profileRulesRejectNonCapabilityResourcesAndRawCredentials() {
        ServiceException resource = assertThrows(ServiceException.class, () -> service.createProfile(
            profileRequest(new PermissionRuleInput(
                "conversation", 7L, null, "read", "allow", Map.of(), null
            ))
        ));
        ServiceException secret = assertThrows(ServiceException.class, () -> service.createProfile(
            profileRequest(new PermissionRuleInput(
                "tool", 7L, null, "invoke", "allow", Map.of("apiKey", "raw"), null
            ))
        ));

        assertEquals(HttpStatus.BAD_REQUEST, resource.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, secret.getCode());
        verify(mapper, never()).insertProfile(any());
    }

    @Test
    void permissionRuleRequiresExactlyOneTargetIdentifier() {
        ServiceException missing = assertThrows(ServiceException.class, () -> service.createProfile(
            profileRequest(new PermissionRuleInput(
                "tool", null, null, "invoke", "allow", Map.of(), null
            ))
        ));
        ServiceException duplicate = assertThrows(ServiceException.class, () -> service.createProfile(
            profileRequest(new PermissionRuleInput(
                "tool", 7L, "tool-key", "invoke", "allow", Map.of(), null
            ))
        ));

        assertEquals(HttpStatus.BAD_REQUEST, missing.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, duplicate.getCode());
    }

    @Test
    void temporaryGrantRejectsDenyAndInvalidLifetime() {
        PermissionRuleInput deny = new PermissionRuleInput(
            "tool", 7L, null, "invoke", "deny", Map.of(), null
        );
        ServiceException effect = assertThrows(ServiceException.class, () ->
            service.createTemporaryGrant(101L, new CreateTemporaryGrantRequest(
                deny, "temporary deny", null, LocalDateTime.now().plusHours(1)
            ))
        );
        PermissionRuleInput allow = new PermissionRuleInput(
            "tool", 7L, null, "invoke", "allow", Map.of(), null
        );
        ServiceException lifetime = assertThrows(ServiceException.class, () ->
            service.createTemporaryGrant(101L, new CreateTemporaryGrantRequest(
                allow, "too long", null, LocalDateTime.now().plusDays(366)
            ))
        );

        assertEquals(HttpStatus.BAD_REQUEST, effect.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, lifetime.getCode());
        verify(mapper, never()).insertTemporaryGrant(any());
    }

    @Test
    void onlyPublishedProfileVersionCanBeBound() {
        PermissionProfile draft = new PermissionProfile();
        draft.setId(10L);
        draft.setVersionNo(1);
        draft.setStatus("draft");
        when(mapper.selectProfile(10L)).thenReturn(draft);

        ServiceException exception = assertThrows(ServiceException.class, () ->
            service.putBinding(101L, new PutPermissionBindingRequest(
                "profile", 10L, 1, List.of()
            ))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(mapper, never()).insertBinding(any());
    }

    @Test
    void permissionCopyIdempotencyKeyCannotReplayDifferentPayload() {
        PermissionCopyRecord existing = new PermissionCopyRecord();
        existing.setId(50L);
        existing.setSourceUserId(101L);
        existing.setTargetUserId(202L);
        existing.setCopyMode("copy_base");
        existing.setDiffJson("{\"requestHash\":\"different\"}");
        existing.setExcludedJson("{\"rules\":[]}");
        when(mapper.selectCopyRecordByIdempotencyKey("copy-1")).thenReturn(existing);

        ServiceException exception = assertThrows(ServiceException.class, () ->
            service.copy(202L, new CopyPermissionRequest(
                "copy-1", 101L, "copy_base", null, null
            ))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        verify(mapper, never()).insertCopyRecord(any());
    }

    private CreatePermissionProfileRequest profileRequest(PermissionRuleInput rule) {
        return new CreatePermissionProfileRequest(
            "profile-1", "Profile", null, "custom", List.of(rule)
        );
    }
}
