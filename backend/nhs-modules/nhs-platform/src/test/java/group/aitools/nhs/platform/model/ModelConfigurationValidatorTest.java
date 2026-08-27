package group.aitools.nhs.platform.model;

import group.aitools.nhs.platform.model.service.ModelConfigurationValidator;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class ModelConfigurationValidatorTest {

    private final ModelConfigurationValidator validator = new ModelConfigurationValidator();

    @Test
    void unknownAndSecretLikeOptionsFailClosed() {
        ServiceException unknown = assertThrows(
            ServiceException.class,
            () -> validator.reasoning(Map.of("apiKey", "raw-secret"))
        );
        ServiceException nested = assertThrows(
            ServiceException.class,
            () -> validator.capabilities(Map.of("arbitrary", Map.of("authorization", "secret")))
        );

        assertEquals(400, unknown.getCode());
        assertEquals(400, nested.getCode());
    }

    @Test
    void nonFiniteAndFractionalIntegerOptionsAreRejected() {
        assertThrows(
            ServiceException.class,
            () -> validator.reasoning(Map.of("temperature", Double.NaN))
        );
        assertThrows(
            ServiceException.class,
            () -> validator.reasoning(Map.of("thinkingBudget", 1.25))
        );
    }

    @Test
    void endpointPathTraversalAndOversizedModalityListsAreRejected() {
        assertThrows(
            ServiceException.class,
            () -> validator.reasoning(Map.of("endpointPath", "/v1/../admin"))
        );
        assertThrows(
            ServiceException.class,
            () -> validator.capabilities(Map.of("inputModalities", List.of(
                "1", "2", "3", "4", "5", "6", "7", "8", "9",
                "10", "11", "12", "13", "14", "15", "16", "17"
            )))
        );
    }

    @Test
    void validOptionsAreNormalizedAndCopied() {
        Map<String, Object> result = validator.reasoning(Map.of(
            "temperature", 1,
            "thinkingBudget", 2048,
            "reasoningEffort", "high",
            "parallelToolCalls", false
        ));

        assertEquals(1.0, result.get("temperature"));
        assertEquals(2048, result.get("thinkingBudget"));
        assertEquals("high", result.get("reasoningEffort"));
        assertEquals(false, result.get("parallelToolCalls"));
    }
}
