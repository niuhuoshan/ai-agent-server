package group.aitools.nhs.platform.agent;

import group.aitools.nhs.platform.agent.service.AgentConfigurationValidator;
import group.aitools.nhs.platform.agent.web.AgentResourceBindingRequest;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class AgentConfigurationValidatorTest {

    private final AgentConfigurationValidator validator = new AgentConfigurationValidator();

    @Test
    void nestedSecretsAndNonFiniteValuesFailClosed() {
        assertThrows(
            ServiceException.class,
            () -> validator.welcomeConfig(Map.of("card", Map.of("apiKey", "raw-secret")))
        );
        assertThrows(
            ServiceException.class,
            () -> validator.welcomeConfig(Map.of("score", Double.NaN))
        );
        assertThrows(
            ServiceException.class,
            () -> validator.runtimeConfig(Map.of("temperature", Double.POSITIVE_INFINITY))
        );
    }

    @Test
    void utf8PromptLimitAndNullBytesAreEnforced() {
        assertThrows(ServiceException.class, () -> validator.systemPrompt("中".repeat(44_000)));
        assertThrows(ServiceException.class, () -> validator.systemPrompt("system\0prompt"));
    }

    @Test
    void duplicateResourcesAndCrossTypePermissionsAreRejected() {
        AgentResourceBindingRequest first = new AgentResourceBindingRequest(10L, "use", Map.of());
        AgentResourceBindingRequest duplicate = new AgentResourceBindingRequest(10L, "use", Map.of());

        assertThrows(
            ServiceException.class,
            () -> validator.bindings("tool", List.of(first, duplicate))
        );
        assertThrows(
            ServiceException.class,
            () -> validator.bindings(
                "knowledge_base",
                List.of(new AgentResourceBindingRequest(10L, "invoke", Map.of()))
            )
        );
    }

    @Test
    void unsafeAvatarAndDeepWelcomeConfigAreRejected() {
        assertThrows(ServiceException.class, () -> validator.avatarUrl("javascript:alert(1)"));
        assertThrows(ServiceException.class, () -> validator.avatarUrl("/assets/../secret"));
        Map<String, Object> nested = Map.of(
            "a", Map.of("b", Map.of("c", Map.of("d", Map.of("e", Map.of("f", Map.of("g", "x"))))))
        );
        assertThrows(ServiceException.class, () -> validator.welcomeConfig(nested));
    }

    @Test
    void routingTagsAreStableAndRuntimeConfigIsNormalized() {
        assertEquals(
            List.of("coding", "research"),
            validator.routingTags(List.of("Research", "coding", "research"))
        );
        assertEquals(
            Map.of("maxIterations", 20, "workspaceAccess", "read_only", "temperature", 0.2),
            validator.runtimeConfig(Map.of(
                "workspaceAccess", "read_only", "temperature", 0.2, "maxIterations", 20
            ))
        );
    }

    @Test
    void manualWelcomeCardsRequireThreeCompleteCardsWhileLegacyRemainsValid() {
        Map<String, Object> card = Map.of(
            "icon", "chat",
            "title", "开始分析",
            "subtitle", "从一个真实问题开始",
            "prompt", "请分析本月经营情况"
        );
        Map<String, Object> canonical = validator.welcomeConfig(Map.of(
            "enabled", true,
            "mode", "manual",
            "cards", List.of(card, card, card)
        ));

        assertEquals(3, ((List<?>) canonical.get("cards")).size());
        assertThrows(
            ServiceException.class,
            () -> validator.welcomeConfig(Map.of(
                "enabled", true, "mode", "manual", "cards", List.of(card, card)
            ))
        );
        assertEquals(
            List.of("查看日报", "排查异常"),
            validator.welcomeConfig(Map.of(
                "message", "欢迎使用",
                "suggestions", List.of("查看日报", "排查异常"),
                "showSuggestions", true
            )).get("suggestions")
        );
    }
}
