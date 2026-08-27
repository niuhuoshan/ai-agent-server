package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class DatasetRowPolicySqlRewriterTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ReadOnlySqlValidator validator = new ReadOnlySqlValidator();
    private final DatasetRowPolicySqlRewriter rewriter = new DatasetRowPolicySqlRewriter(jsonMapper, validator);

    @Test
    void injectsPrincipalIdWithoutChangingExistingOrPrecedence() {
        AgentDataTable table = table(10L, "orders");
        List<AgentDataColumn> columns = List.of(
            column(11L, 10L, "id"),
            column(12L, 10L, "owner_id"),
            column(13L, 10L, "status")
        );
        String sql = "SELECT o.id FROM public.orders o WHERE o.status = 'open' OR o.status = 'new'";
        var validated = validator.validate(sql, List.of(table), columns);

        var rewritten = rewriter.apply(
            dataset("{\"rules\":[{\"tableId\":10,\"columnId\":12,\"operator\":\"eq\",\"valueSource\":\"principal_id\"}]}"),
            principal(42L, "alice"), List.of(table), columns, validated
        );

        assertTrue(rewritten.sql().contains("(o.status = 'open' OR o.status = 'new')"));
        assertTrue(rewritten.sql().contains("\"o\".\"owner_id\" = 42"));
        assertTrue(rewritten.columns().contains("public.orders.owner_id"));
    }

    @Test
    void injectsEscapedPrincipalUsernameForEveryAliasOfTheGovernedTable() {
        AgentDataTable table = table(10L, "orders");
        List<AgentDataColumn> columns = List.of(
            column(11L, 10L, "id"),
            column(12L, 10L, "owner_name")
        );
        var validated = validator.validate(
            "SELECT a.id, b.id FROM public.orders a INNER JOIN public.orders b ON a.id = b.id",
            List.of(table), columns
        );

        var rewritten = rewriter.apply(
            dataset("{\"rules\":[{\"tableId\":10,\"columnId\":12,\"operator\":\"ne\",\"valueSource\":\"principal_username\"}]}"),
            principal(42L, "o'hara"), List.of(table), columns, validated
        );

        assertTrue(rewritten.sql().contains("\"a\".\"owner_name\" <> 'o''hara'"));
        assertTrue(rewritten.sql().contains("\"b\".\"owner_name\" <> 'o''hara'"));
    }

    @Test
    void leavesValidatedSqlUntouchedWhenPolicyIsDisabled() {
        AgentDataTable table = table(10L, "orders");
        List<AgentDataColumn> columns = List.of(column(11L, 10L, "id"));
        var validated = validator.validate("SELECT id FROM public.orders", List.of(table), columns);
        AgentDataDataset dataset = dataset("{}");
        dataset.setEnableRowPolicy(false);

        assertEquals(validated, rewriter.apply(dataset, principal(42L, "alice"), List.of(table), columns, validated));
    }

    @Test
    void failsClosedForMissingOrInvalidPolicyFacts() {
        AgentDataTable table = table(10L, "orders");
        List<AgentDataColumn> columns = List.of(column(11L, 10L, "id"));
        var validated = validator.validate("SELECT id FROM public.orders", List.of(table), columns);

        assertThrows(ServiceException.class, () -> rewriter.apply(
            dataset("{}"), principal(42L, "alice"), List.of(table), columns, validated
        ));
        assertThrows(ServiceException.class, () -> rewriter.apply(
            dataset("{not-json"), principal(42L, "alice"), List.of(table), columns, validated
        ));
        assertThrows(ServiceException.class, () -> rewriter.apply(
            dataset("{\"rules\":[{\"tableId\":10,\"columnId\":999,\"operator\":\"eq\",\"valueSource\":\"principal_id\"}]}"),
            principal(42L, "alice"), List.of(table), columns, validated
        ));
    }

    private AgentDataDataset dataset(String policyJson) {
        AgentDataDataset dataset = new AgentDataDataset();
        dataset.setId(1L);
        dataset.setEnableRowPolicy(true);
        dataset.setRowPolicyJson(policyJson);
        return dataset;
    }

    private AgentDataTable table(Long id, String name) {
        AgentDataTable table = new AgentDataTable();
        table.setId(id);
        table.setTableKey(name);
        table.setPhysicalSchema("public");
        table.setPhysicalName(name);
        table.setStatus("active");
        table.setMetadataPresent(true);
        return table;
    }

    private AgentDataColumn column(Long id, Long tableId, String name) {
        AgentDataColumn column = new AgentDataColumn();
        column.setId(id);
        column.setTableId(tableId);
        column.setPhysicalName(name);
        column.setStatus("active");
        column.setMetadataPresent(true);
        column.setIsSensitive(false);
        return column;
    }

    private CurrentPrincipal principal(Long id, String username) {
        return new CurrentPrincipal(id, username, PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER));
    }
}
