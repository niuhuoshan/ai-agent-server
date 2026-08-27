package group.aitools.nhs.platform.data;

import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.service.ReadOnlySqlValidator;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class ReadOnlySqlValidatorTest {

    private final ReadOnlySqlValidator validator = new ReadOnlySqlValidator();

    @Test
    void acceptsExplicitDatasetColumnsAndSafeAggregates() {
        var validated = validator.validate(
            "SELECT o.customer_id, count(*) AS total FROM public.orders o GROUP BY o.customer_id",
            List.of(table(10L, "public", "orders", "active", true)),
            List.of(column(11L, 10L, "customer_id", false, "active", true))
        );

        assertEquals(List.of("public.orders"), validated.tables());
        assertEquals(List.of("public.orders.customer_id"), validated.columns());
    }

    @Test
    void rejectsWritesMultipleStatementsLocksAndSystemSchemas() {
        assertRejected("DELETE FROM public.orders");
        assertRejected("SELECT customer_id FROM public.orders; SELECT customer_id FROM public.orders");
        assertRejected("SELECT customer_id FROM public.orders FOR UPDATE");
        assertRejected("SELECT relname FROM pg_catalog.pg_class");
    }

    @Test
    void acceptsReadOnlyCteAndKeepsPhysicalColumnLineage() {
        var validated = validator.validate(
            "WITH scoped AS (SELECT o.customer_id, count(*) AS total "
                + "FROM public.orders o GROUP BY o.customer_id) "
                + "SELECT scoped.customer_id, scoped.total FROM scoped",
            List.of(table(10L, "public", "orders", "active", true)),
            List.of(column(11L, 10L, "customer_id", false, "active", true))
        );

        assertEquals(List.of("public.orders"), validated.tables());
        assertEquals(List.of("public.orders.customer_id"), validated.columns());
    }

    @Test
    void rejectsWriteCteUnknownOutputsAndSensitiveCteColumns() {
        assertRejected("WITH changed AS (DELETE FROM public.orders RETURNING customer_id) "
            + "SELECT customer_id FROM changed");
        assertRejected("WITH scoped AS (SELECT customer_id FROM public.orders) "
            + "SELECT scoped.missing FROM scoped");
        assertThrows(ServiceException.class, () -> validator.validate(
            "WITH scoped AS (SELECT secret FROM public.orders) SELECT secret FROM scoped",
            List.of(table(10L, "public", "orders", "active", true)),
            List.of(column(11L, 10L, "secret", true, "active", true))
        ));
    }

    @Test
    void rejectsUnqualifiedTablesWildcardsUnsafeFunctionsAndUnknownTables() {
        assertRejected("SELECT customer_id FROM orders");
        assertRejected("SELECT * FROM public.orders");
        assertRejected("SELECT pg_sleep(1) FROM public.orders");
        assertRejected("SELECT id FROM public.outside");
    }

    @Test
    void rejectsSensitiveInactiveAndMissingColumns() {
        List<AgentDataTable> tables = List.of(table(10L, "public", "orders", "active", true));
        assertThrows(ServiceException.class, () -> validator.validate(
            "SELECT secret FROM public.orders", tables,
            List.of(column(11L, 10L, "secret", true, "active", true))
        ));
        assertThrows(ServiceException.class, () -> validator.validate(
            "SELECT old_value FROM public.orders", tables,
            List.of(column(11L, 10L, "old_value", false, "inactive", true))
        ));
        assertThrows(ServiceException.class, () -> validator.validate(
            "SELECT missing FROM public.orders", tables,
            List.of(column(11L, 10L, "customer_id", false, "active", true))
        ));
    }

    private void assertRejected(String sql) {
        assertThrows(ServiceException.class, () -> validator.validate(
            sql,
            List.of(table(10L, "public", "orders", "active", true)),
            List.of(
                column(11L, 10L, "id", false, "active", true),
                column(12L, 10L, "customer_id", false, "active", true)
            )
        ));
    }

    private AgentDataTable table(Long id, String schema, String name, String status, boolean present) {
        AgentDataTable table = new AgentDataTable();
        table.setId(id);
        table.setPhysicalSchema(schema);
        table.setPhysicalName(name);
        table.setStatus(status);
        table.setMetadataPresent(present);
        return table;
    }

    private AgentDataColumn column(
        Long id,
        Long tableId,
        String name,
        boolean sensitive,
        String status,
        boolean present
    ) {
        AgentDataColumn column = new AgentDataColumn();
        column.setId(id);
        column.setTableId(tableId);
        column.setPhysicalName(name);
        column.setIsSensitive(sensitive);
        column.setStatus(status);
        column.setMetadataPresent(present);
        return column;
    }
}
