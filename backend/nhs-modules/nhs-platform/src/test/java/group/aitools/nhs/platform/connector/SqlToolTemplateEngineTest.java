package group.aitools.nhs.platform.connector;

import group.aitools.nhs.platform.connector.service.SqlToolTemplateEngine;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class SqlToolTemplateEngineTest {

    private final SqlToolTemplateEngine engine = new SqlToolTemplateEngine();

    @Test
    void rendersOnlyTypedValuesAndEscapesSqlMetacharacters() {
        Map<String, Object> schema = schema(Map.of(
            "customer", Map.of("type", "string"),
            "years", Map.of("type", "array", "items", Map.of("type", "integer"))
        ), List.of("customer", "years"));
        var configuration = engine.validate(schema, policy("""
            SELECT o.customer_id
            FROM public.orders o
            WHERE o.customer_id = {{customer}} AND o.order_year IN ({{years}})
            """));

        String sql = engine.render(configuration, Map.of(
            "customer", "x'; DELETE FROM public.orders; --",
            "years", List.of(2025, 2026)
        ));

        assertEquals(
            "SELECT o.customer_id\nFROM public.orders o\n"
                + "WHERE o.customer_id = 'x''; DELETE FROM public.orders; --' "
                + "AND o.order_year IN (2025, 2026)",
            sql
        );
    }

    @Test
    void rejectsQuotedPlaceholdersAndUnusedOrOptionalParameters() {
        assertThrows(ServiceException.class, () -> engine.validate(
            schema(Map.of("customer", Map.of("type", "string")), List.of("customer")),
            policy("SELECT o.customer_id FROM public.orders o WHERE o.customer_id = '{{customer}}'")
        ));
        assertThrows(ServiceException.class, () -> engine.validate(
            schema(Map.of("customer", Map.of("type", "string")), List.of()),
            policy("SELECT o.customer_id FROM public.orders o WHERE o.customer_id = {{customer}}")
        ));
        assertThrows(ServiceException.class, () -> engine.validate(
            schema(Map.of("unused", Map.of("type", "string")), List.of("unused")),
            policy("SELECT o.customer_id FROM public.orders o")
        ));
        var configuration = engine.validate(
            schema(Map.of("customer", Map.of("type", "string")), List.of("customer")),
            policy("SELECT o.customer_id FROM public.orders o WHERE o.customer_id = {{customer}}")
        );
        assertThrows(ServiceException.class, () -> engine.render(
            configuration, Map.of("customer", "unsafe\\literal")
        ));
    }

    private Map<String, Object> schema(
        Map<String, Object> properties,
        List<String> required
    ) {
        return Map.of(
            "type", "object",
            "properties", properties,
            "required", required,
            "additionalProperties", false
        );
    }

    private Map<String, Object> policy(String sql) {
        return Map.of(
            "datasetId", "800",
            "queryPurpose", "按客户查询订单",
            "sqlTemplate", sql,
            "readOnly", true
        );
    }
}
