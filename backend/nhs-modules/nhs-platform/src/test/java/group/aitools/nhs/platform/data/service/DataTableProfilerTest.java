package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class DataTableProfilerTest {

    @Test
    void readsOnlyThreeRowsAndRedactsSensitiveNamedColumnsBeforeModelInvocation() throws Exception {
        ReadOnlyJdbcConnectionFactory connectionFactory = mock(ReadOnlyJdbcConnectionFactory.class);
        MetadataProfileModelGateway modelGateway = mock(MetadataProfileModelGateway.class);
        JsonMapper jsonMapper = JsonMapper.builder().build();
        DataTableProfiler profiler = new DataTableProfiler(connectionFactory, modelGateway, jsonMapper);
        Connection connection = mock(Connection.class);
        Statement controls = mock(Statement.class);
        Statement query = mock(Statement.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet resultSet = mock(ResultSet.class);
        AgentDataSource source = source();
        AgentDataTable table = table();
        List<AgentDataColumn> columns = List.of(
            column(1L, "id", "bigint", true),
            column(2L, "email", "varchar", false)
        );
        when(connectionFactory.open(source)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(controls, query);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getIdentifierQuoteString()).thenReturn("\"");
        when(query.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, true);
        when(resultSet.getObject(1)).thenReturn(1L, 2L, 3L);
        when(resultSet.getObject(2)).thenReturn("a@example.test", "b@example.test", "c@example.test");
        when(modelGateway.analyze(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(new MetadataProfileModelGateway.Analysis(
                10L, "metadata-model", "openai_compatible", "订单表", "记录订单",
                List.of("订单"), 90, "结构明确", "business",
                List.of(
                    new MetadataProfileModelGateway.ColumnSemantic("id", "订单ID", "订单主键"),
                    new MetadataProfileModelGateway.ColumnSemantic("email", "联系邮箱", "客户联系邮箱")
                )
            ));

        var profiled = profiler.profile(
            source, table, columns, DataTableProfiler.structureHash(table, columns)
        );

        assertEquals(3, profiled.samples().size());
        assertTrue(profiled.sampleRedacted());
        assertEquals("[REDACTED]", profiled.samples().get(0).values().get(1).value());
        assertEquals("联系邮箱", profiled.columns().get(1).term());
        ArgumentCaptor<String> sampleJson = ArgumentCaptor.forClass(String.class);
        verify(modelGateway).analyze(anyString(), anyString(), sampleJson.capture(), org.mockito.ArgumentMatchers.anyList());
        assertTrue(sampleJson.getValue().contains("[REDACTED]"));
        assertTrue(!sampleJson.getValue().contains("a@example.test"));
        verify(connectionFactory).rollback(connection, source);
    }

    private AgentDataSource source() {
        AgentDataSource source = new AgentDataSource();
        source.setId(8L);
        source.setDbType("postgresql");
        source.setStatementTimeoutMs(15_000);
        return source;
    }

    private AgentDataTable table() {
        AgentDataTable table = new AgentDataTable();
        table.setId(9L);
        table.setDatasetId(1L);
        table.setPhysicalSchema("public");
        table.setPhysicalName("orders");
        table.setTableType("TABLE");
        table.setStatus("active");
        table.setMetadataPresent(true);
        return table;
    }

    private AgentDataColumn column(Long id, String name, String type, boolean primary) {
        AgentDataColumn column = new AgentDataColumn();
        column.setId(id);
        column.setTableId(9L);
        column.setPhysicalName(name);
        column.setDisplayName(name);
        column.setDataType(type);
        column.setIsPrimary(primary);
        column.setIsSensitive(false);
        column.setStatus("active");
        column.setMetadataPresent(true);
        return column;
    }
}
