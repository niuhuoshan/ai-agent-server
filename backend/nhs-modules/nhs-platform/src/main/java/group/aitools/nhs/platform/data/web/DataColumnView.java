package group.aitools.nhs.platform.data.web;

import group.aitools.nhs.platform.data.domain.AgentDataColumn;

/** One synchronized column and its governance classification. */
public record DataColumnView(
    Long id,
    String columnKey,
    String physicalName,
    String displayName,
    String dataType,
    String description,
    boolean primary,
    boolean sensitive,
    String status,
    boolean metadataPresent
) {

    public static DataColumnView from(AgentDataColumn column) {
        return new DataColumnView(
            column.getId(), column.getColumnKey(), column.getPhysicalName(), column.getDisplayName(),
            column.getDataType(), column.getDescription(), Boolean.TRUE.equals(column.getIsPrimary()),
            Boolean.TRUE.equals(column.getIsSensitive()), column.getStatus(),
            Boolean.TRUE.equals(column.getMetadataPresent())
        );
    }
}
