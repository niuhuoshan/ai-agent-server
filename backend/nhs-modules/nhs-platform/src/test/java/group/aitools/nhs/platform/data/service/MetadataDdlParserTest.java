package group.aitools.nhs.platform.data.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class MetadataDdlParserTest {

    private final MetadataDdlParser parser = new MetadataDdlParser(new MetadataYamlCodec());

    @Test
    void parsesMultipleCreateTablesAndBothPrimaryKeyForms() {
        var document = parser.parse("""
            CREATE TABLE sales.orders (
                id BIGINT PRIMARY KEY,
                customer_id BIGINT COMMENT '客户ID'
            );
            CREATE TABLE customers (
                tenant_id BIGINT,
                id BIGINT,
                PRIMARY KEY (tenant_id, id)
            );
            """, "public");

        assertEquals(2, document.tables().size());
        var customers = document.tables().stream()
            .filter(table -> table.name().equals("customers")).findFirst().orElseThrow();
        assertEquals("public", customers.schema());
        assertTrue(customers.columns().stream().allMatch(column -> column.primary()));
        var orders = document.tables().stream()
            .filter(table -> table.name().equals("orders")).findFirst().orElseThrow();
        assertEquals("客户ID", orders.columns().stream()
            .filter(column -> column.name().equals("customer_id")).findFirst().orElseThrow().description());
    }

    @Test
    void rejectsNonCreateTableAndCreateAsSelect() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("DROP TABLE orders", "public"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
            "CREATE TABLE orders_copy AS SELECT * FROM orders", "public"
        ));
    }

    @Test
    void turnsForeignKeysIntoRealJoinConditionsInsteadOfPlaceholderRelations() {
        var document = parser.parse("""
            CREATE TABLE public.customers (
                tenant_id BIGINT,
                id BIGINT,
                PRIMARY KEY (tenant_id, id)
            );
            CREATE TABLE public.orders (
                tenant_id BIGINT,
                customer_id BIGINT,
                CONSTRAINT fk_orders_customer FOREIGN KEY (tenant_id, customer_id)
                    REFERENCES public.customers (tenant_id, id)
            );
            """, "public");

        assertEquals(1, document.relationships().size());
        var relationship = document.relationships().getFirst();
        assertEquals("public.orders", relationship.sourceTable());
        assertEquals("public.customers", relationship.targetTable());
        assertTrue(relationship.joinCondition().contains("customer_id"));
        assertTrue(relationship.joinCondition().contains("customers"));
        assertTrue(relationship.joinCondition().contains(" AND "));
    }
}
