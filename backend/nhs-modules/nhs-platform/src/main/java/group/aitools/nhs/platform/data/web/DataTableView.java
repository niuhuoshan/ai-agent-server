package group.aitools.nhs.platform.data.web;

import group.aitools.nhs.platform.data.domain.AgentDataTable;

import java.util.List;

/** One synchronized table with columns. */
public record DataTableView(
    Long id,
    String tableKey,
    String physicalSchema,
    String physicalName,
    String displayName,
    String description,
    String tableType,
    String status,
    boolean metadataPresent,
    List<DataColumnView> columns
) {

    public static DataTableView from(AgentDataTable table, List<DataColumnView> columns) {
        return new DataTableView(
            table.getId(), table.getTableKey(), table.getPhysicalSchema(), table.getPhysicalName(),
            table.getDisplayName(), table.getDescription(), table.getTableType(), table.getStatus(),
            Boolean.TRUE.equals(table.getMetadataPresent()), List.copyOf(columns)
        );
    }
}
