package group.aitools.nhs.platform.portal.quota;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.portal.quota.domain.AgentQuotaPolicy;
import group.aitools.nhs.platform.portal.quota.mapper.AgentQuotaPolicyMapper;
import group.aitools.nhs.platform.portal.quota.persistence.row.QuotaRoleRow;
import group.aitools.nhs.platform.portal.quota.service.PortalQuotaService;
import group.aitools.nhs.platform.portal.quota.web.QuotaPolicyRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class PortalQuotaServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        7L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );

    private CurrentPrincipalProvider principalProvider;
    private PlatformIdGenerator idGenerator;
    private AgentQuotaPolicyMapper mapper;
    private PortalQuotaService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        idGenerator = mock(PlatformIdGenerator.class);
        mapper = mock(AgentQuotaPolicyMapper.class);
        service = new PortalQuotaService(principalProvider, idGenerator, mapper);
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
        when(mapper.selectMonthlyUsage(eq(7L), any(), any())).thenReturn(120L);
        when(mapper.selectPolicy(eq("user"), eq(7L))).thenReturn(null);
        when(mapper.selectRoles(7L)).thenReturn(List.of(role(10L, "分析员")));
        when(mapper.selectPolicies(eq("role"), anyList())).thenReturn(List.of(rolePolicy(10L, 1000L)));
        when(mapper.selectPolicy(eq("system"), eq(null))).thenReturn(null);
    }

    @Test
    void resolvesRolePolicyAndCalculatesRemainingTokens() {
        Map<String, Object> status = service.myQuota();

        assertThat(status)
            .containsEntry("source", "role")
            .containsEntry("limit_tokens", 1000L)
            .containsEntry("used_tokens", 120L)
            .containsEntry("remaining_tokens", 880L)
            .containsEntry("is_admin_bypass", false);
    }

    @Test
    void userPolicyOverridesRolePolicyAndNullMeansUnlimited() {
        AgentQuotaPolicy policy = rolePolicy(7L, null);
        policy.setScopeType("user");
        when(mapper.selectPolicy(eq("user"), eq(7L))).thenReturn(policy);

        Map<String, Object> status = service.myQuota();

        assertThat(status).containsEntry("source", "user").containsEntry("limit_tokens", null);
        assertThat(status.get("remaining_tokens")).isNull();
    }

    @Test
    void nonAdminCannotChangePolicy() {
        assertThatThrownBy(() -> service.upsert("system", null, new QuotaPolicyRequest(true, 10L)))
            .isInstanceOfAny(RuntimeException.class)
            .hasMessageContaining("管理员");
    }

    @Test
    void adminCanCreateSystemPolicy() {
        when(principalProvider.currentPrincipal()).thenReturn(ADMIN);
        when(idGenerator.nextId()).thenReturn(99L);
        when(mapper.selectPolicy(eq("system"), eq(null))).thenReturn(null);
        when(mapper.insertPolicy(any())).thenReturn(1);
        Map<String, Object> result = service.upsert("system", null, new QuotaPolicyRequest(true, 5000L));

        assertThat(result).containsEntry("scope_type", "system").containsEntry("limit_tokens", 5000L);
    }

    private QuotaRoleRow role(Long id, String name) {
        QuotaRoleRow row = new QuotaRoleRow();
        row.setRoleId(id);
        row.setRoleName(name);
        return row;
    }

    private AgentQuotaPolicy rolePolicy(Long id, Long limit) {
        AgentQuotaPolicy policy = new AgentQuotaPolicy();
        policy.setId(id);
        policy.setScopeType("role");
        policy.setScopeId(id);
        policy.setEnabled(true);
        policy.setLimitTokens(limit);
        policy.setActionOnExceed("block");
        return policy;
    }
}
