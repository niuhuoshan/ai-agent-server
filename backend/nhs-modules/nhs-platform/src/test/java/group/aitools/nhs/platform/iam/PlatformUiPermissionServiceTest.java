package group.aitools.nhs.platform.iam;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PermissionRule;
import group.aitools.nhs.platform.iam.domain.PermissionSnapshot;
import group.aitools.nhs.platform.iam.domain.PermissionSource;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.PlatformUiPermissionService;
import group.aitools.nhs.platform.iam.service.impl.DefaultAuthorizationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class PlatformUiPermissionServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN,
        Set.of(PlatformRole.MEMBER, PlatformRole.PLATFORM_ADMIN)
    );
    private static final CurrentPrincipal SERVICE_ACCOUNT = new CurrentPrincipal(
        201L, "worker", PrincipalType.SERVICE_ACCOUNT, Set.of(PlatformRole.SERVICE_ACCOUNT)
    );

    @Test
    void memberKeepsPersonalSkillAndMemoryWithoutAdministrationRoutes() {
        PlatformUiPermissionService service = service(List.of());

        assertThat(service.allowedRoutes(MEMBER))
            .contains(
                "client", "home", "workspace", "task-center", "project-center", "knowledge",
                "risk-control", "saved-reports", "resource-center"
            )
            .doesNotContain("agent-center", "data-source", "automation", "open-api", "widget-debugger", "system");
        assertThat(service.buttons(MEMBER))
            .contains(
                "conversation:create", "task:create", "project:create", "knowledge_base:create",
                "report:create", "skill:list", "skill:create", "skill:update", "skill:delete",
                "skill:publish", "memory:list", "memory:create", "memory:update", "memory:delete",
                "memory:review", "resource:skill:list", "resource:skill:create",
                "resource:skill:edit", "resource:skill:delete", "resource:skill:operate",
                "resource:skill:publish", "resource:skill:archive", "resource:memory:list",
                "resource:memory:create", "resource:memory:edit", "resource:memory:delete"
            )
            .doesNotContain(
                "model:list", "model:create", "connector:list", "connector:create",
                "tool:list", "tool:create", "iam:manage", "resource:model:list",
                "resource:connector:list", "resource:tool:list", "resource:memory:operate"
            );
    }

    @Test
    void administratorCanManageModelsConnectorsAndTools() {
        PlatformUiPermissionService service = service(List.of());

        assertThat(service.allowedRoutes(ADMIN))
            .contains(
                "resource-center", "agent-center", "data-source", "automation",
                "open-api", "widget-debugger", "system"
            );
        assertThat(service.buttons(ADMIN)).contains(
            "model:list", "connector:list", "tool:list",
            "model:create", "model:update", "model:delete", "model:operate",
            "connector:create", "connector:update", "connector:delete", "connector:operate",
            "tool:create", "tool:update", "tool:delete", "resource:model:list",
            "resource:model:create", "resource:model:edit", "resource:model:delete",
            "resource:model:operate", "resource:connector:list", "resource:connector:create",
            "resource:connector:edit", "resource:connector:delete", "resource:connector:operate",
            "resource:tool:list", "resource:tool:create", "resource:tool:edit",
            "resource:tool:delete", "resource:tool:operate", "resource:memory:operate"
        );
    }

    @Test
    void explicitDenyOverridesMemberPersonalResourceBaseline() {
        PlatformUiPermissionService service = service(List.of(
            rule("skill", "*", PermissionEffect.DENY),
            rule("memory", "*", PermissionEffect.DENY)
        ));

        assertThat(service.allowedRoutes(MEMBER)).doesNotContain("resource-center");
        assertThat(service.buttons(MEMBER)).noneMatch(code ->
            code.startsWith("skill:") || code.startsWith("memory:")
                || code.startsWith("resource:skill:") || code.startsWith("resource:memory:")
        );
    }

    @Test
    void explicitAllowCanExtendMemberRoutesAndButtons() {
        PlatformUiPermissionService service = service(List.of(
            rule("agent", "list", PermissionEffect.ALLOW),
            rule("data_source", "list", PermissionEffect.ALLOW),
            rule("model", "create", PermissionEffect.ALLOW)
        ));

        assertThat(service.allowedRoutes(MEMBER)).contains("agent-center", "data-source");
        assertThat(service.buttons(MEMBER)).contains("model:create", "resource:model:create");
    }

    @Test
    void serviceAccountHasNoUiProjectionAndSkipsAuthorizationLookup() {
        PlatformUiPermissionService service = new PlatformUiPermissionService((principal, context) -> {
            throw new AssertionError("service accounts must not resolve UI permissions");
        });

        assertThat(service.allowedRoutes(SERVICE_ACCOUNT)).isEmpty();
        assertThat(service.buttons(SERVICE_ACCOUNT)).isEmpty();
        assertThat(service.isRouteAllowed(SERVICE_ACCOUNT, "home")).isFalse();
    }

    private PlatformUiPermissionService service(List<PermissionRule> rules) {
        return new PlatformUiPermissionService(new DefaultAuthorizationService((principal, context) ->
            new PermissionSnapshot("test", rules)
        ));
    }

    private PermissionRule rule(String resourceType, String action, PermissionEffect effect) {
        return new PermissionRule(
            resourceType, null, null, action, effect, PermissionSource.USER_OVERRIDE,
            "test:" + resourceType + ":" + action, "test rule"
        );
    }
}
