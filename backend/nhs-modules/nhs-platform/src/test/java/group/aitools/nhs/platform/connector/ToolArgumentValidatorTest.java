package group.aitools.nhs.platform.connector;

import group.aitools.nhs.platform.connector.service.ToolArgumentValidator;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class ToolArgumentValidatorTest {

    private final ToolArgumentValidator validator = new ToolArgumentValidator(
        JsonMapper.builder().build()
    );

    @Test
    void enforcesNestedTypesAndAdditionalProperties() {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "properties", Map.of(
                "filters", Map.of(
                    "type", "array",
                    "items", Map.of("type", "integer")
                )
            ),
            "additionalProperties", false
        );

        assertEquals(
            "{\"filters\":[1,2]}",
            validator.validate(Map.of("filters", List.of(1, 2)), schema)
        );
        assertThrows(
            ServiceException.class,
            () -> validator.validate(Map.of("filters", List.of("bad")), schema)
        );
        assertThrows(
            ServiceException.class,
            () -> validator.validate(Map.of("unknown", true), schema)
        );
    }

    @Test
    void rejectsArgumentsLargerThan64Kb() {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> validator.validate(Map.of("value", "x".repeat(70 * 1024)), Map.of())
        );

        assertEquals(400, exception.getCode());
    }
}
